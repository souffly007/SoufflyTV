package fr.bonobo.soufflytv.platform

import fr.bonobo.soufflytv.data.DnsCheck
import fr.bonobo.soufflytv.data.DnsMode
import fr.bonobo.soufflytv.data.IptvException
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/*
 * Réseau commun Android / ordinateur (même fichier dans androidMain et desktopMain).
 *
 * DNS sécurisé : quand il est activé, l'adresse des serveurs (listes, guide, images, vidéo) est demandée
 * en HTTPS à Cloudflare, Quad9 ou Google, au lieu du DNS de la box — qui bloque parfois des serveurs IPTV.
 */
internal object SecureDns : Dns {

    @Volatile
    var mode: DnsMode = DnsMode.SYSTEM
        private set

    /** Client de démarrage : il joint le serveur DNS par son adresse IP, sans avoir besoin de DNS. */
    private val bootstrap: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // court : si un VPN (DNSphere…) ou le réseau bloque le DNS chiffré, on repasse vite sur celui de l'appareil
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val resolvers = ConcurrentHashMap<DnsMode, Dns>()

    /** Petit cache (5 min) : la vidéo HLS ouvre beaucoup de connexions vers le même serveur. */
    private class Cached(val addresses: List<InetAddress>, val until: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private const val CACHE_MS = 5 * 60_000L

    /** DNS chiffré injoignable : on ne le réessaie qu'au bout d'une minute (pas d'attente à chaque connexion). */
    @Volatile
    private var unreachableUntil = 0L
    private const val RETRY_MS = 60_000L

    /** Client partagé par toute l'application : connexions et DNS communs. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(this)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun select(newMode: DnsMode) {
        if (newMode != mode) {
            mode = newMode
            cache.clear()
            unreachableUntil = 0L
            // les connexions ouvertes avec l'ancien DNS sont fermées
            runCatching { client.connectionPool.evictAll() }
        }
    }

    private fun resolver(target: DnsMode): Dns =
        if (target == DnsMode.SYSTEM) Dns.SYSTEM else resolvers.getOrPut(target) { build(target) }

    private fun build(target: DnsMode): Dns {
        val (url, ips) = when (target) {
            DnsMode.CLOUDFLARE -> "https://cloudflare-dns.com/dns-query" to
                listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
            DnsMode.QUAD9 -> "https://dns.quad9.net/dns-query" to
                listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
            DnsMode.GOOGLE -> "https://dns.google/dns-query" to
                listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
            DnsMode.SYSTEM -> error("pas de DNS chiffré")
        }
        return DnsOverHttps.Builder()
            .client(bootstrap)
            .url(url.toHttpUrl())
            // adresses écrites en chiffres : aucune requête DNS pour les lire
            .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
            .includeIPv6(true)
            .build()
    }

    private fun isIpLiteral(host: String): Boolean =
        ':' in host || host.all { it.isDigit() || it == '.' }

    override fun lookup(hostname: String): List<InetAddress> {
        val current = mode
        if (current == DnsMode.SYSTEM || isIpLiteral(hostname) || hostname.equals("localhost", ignoreCase = true)) {
            return Dns.SYSTEM.lookup(hostname)
        }
        val now = System.currentTimeMillis()
        cache[hostname]?.takeIf { it.until > now }?.let { return it.addresses }
        if (now < unreachableUntil) return Dns.SYSTEM.lookup(hostname)
        return try {
            resolver(current).lookup(hostname).also { cache[hostname] = Cached(it, now + CACHE_MS) }
        } catch (e: UnknownHostException) {
            // cause réseau = serveur DNS chiffré injoignable (VPN comme DNSphere, réseau qui le bloque…)
            if (e.cause != null) unreachableUntil = System.currentTimeMillis() + RETRY_MS
            Dns.SYSTEM.lookup(hostname)
        }
    }

    /** Adresses données par un DNS précis, sans cache ni repli (pour le test des réglages). */
    fun lookupWith(target: DnsMode, host: String): List<InetAddress> = resolver(target).lookup(host)
}

actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
    // propre file d'attente : fermer ce client (Ktor arrête alors ses fils) ne touche pas aux autres
    engine { preconfigured = SecureDns.client.newBuilder().dispatcher(Dispatcher()).build() }
    block()
}

actual fun applyDnsMode(mode: DnsMode) = SecureDns.select(mode)

actual suspend fun checkDns(host: String, mode: DnsMode): DnsCheck = withContext(Dispatchers.IO) {
    fun describe(result: Result<List<InetAddress>>): String = result.fold(
        onSuccess = { list -> list.mapNotNull { it.hostAddress }.distinct().take(3).joinToString(", ").ifEmpty { "aucune adresse" } },
        onFailure = {
            when {
                it is UnknownHostException && it.cause != null -> "injoignable"
                it is UnknownHostException -> "introuvable"
                else -> "erreur (${it.message ?: it::class.simpleName})"
            }
        },
    )
    val system = runCatching { Dns.SYSTEM.lookup(host) }
    val secure = runCatching { SecureDns.lookupWith(if (mode == DnsMode.SYSTEM) DnsMode.CLOUDFLARE else mode, host) }
    val systemSet = system.getOrNull()?.mapNotNull { it.hostAddress }?.toSet().orEmpty()
    val secureSet = secure.getOrNull()?.mapNotNull { it.hostAddress }?.toSet().orEmpty()
    DnsCheck(
        host = host,
        system = describe(system),
        secure = describe(secure),
        differs = secureSet.isNotEmpty() && systemSet.intersect(secureSet).isEmpty(),
    )
}

/**
 * Téléchargement en flux (guide XMLTV) par le client commun (DNS choisi, redirections http ↔ https),
 * décompresse le gzip (détecté par sa signature) et transmet le texte par morceaux.
 */
internal suspend fun streamTextJvm(url: String, headers: Map<String, String>, onChunk: (String) -> Unit) =
    withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        SecureDns.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IptvException("Guide TV : erreur HTTP ${response.code}")
            var input: InputStream = BufferedInputStream(response.body!!.byteStream(), 64 * 1024)
            input.mark(4)
            val b1 = input.read()
            val b2 = input.read()
            input.reset()
            if (b1 == 0x1f && b2 == 0x8b) input = GZIPInputStream(input, 64 * 1024)
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(32 * 1024)
                while (true) {
                    ensureActive()
                    val n = reader.read(buffer)
                    if (n < 0) break
                    onChunk(String(buffer, 0, n))
                }
            }
        }
        Unit
    }
