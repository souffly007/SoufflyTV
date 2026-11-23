package fr.bonobo.soufflytv.platform

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Relais local pour VLC quand le DNS sécurisé est activé (ordinateur).
 *
 * VLC cherche lui-même l'adresse des serveurs, avec le DNS du système : il lirait donc un serveur bloqué
 * par la box. On lui donne à la place un lien vers ce relais (http://127.0.0.1:port/…), qui télécharge
 * la vidéo par le client commun (DNS chiffré) et la lui transmet au fur et à mesure.
 * Les listes HLS (.m3u8) sont réécrites pour que leurs segments passent aussi par le relais.
 */
internal object StreamProxy {

    private const val BUFFER = 64 * 1024

    /** En-têtes (User-Agent, Referer…) de chaque lecture, retrouvés par leur jeton. */
    private val sessions: MutableMap<String, Map<String, String>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Map<String, String>>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?) = size > 64
        }
    )

    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "soufflytv-relais").apply { isDaemon = true }
    }

    private val client: OkHttpClient by lazy {
        SecureDns.client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private var server: ServerSocket? = null

    @Synchronized
    private fun port(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        server = socket
        Thread({ acceptLoop(socket) }, "soufflytv-relais-accueil").apply { isDaemon = true }.start()
        return socket.localPort
    }

    /** Lien à donner à VLC pour [url] ; les autres protocoles (rtmp, udp, fichiers…) restent tels quels. */
    fun wrap(url: String, headers: Map<String, String>): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val token = UUID.randomUUID().toString().replace("-", "")
        sessions[token] = headers
        return local(token, parsed)
    }

    private fun local(token: String, url: HttpUrl): String {
        // le nom du fichier d'origine reste à la fin du chemin : VLC s'en sert pour reconnaître le format
        val name = url.pathSegments.lastOrNull()?.filter { it.isLetterOrDigit() || it in "._-" }?.take(60)
            ?.ifEmpty { null } ?: "flux"
        return "http://127.0.0.1:${port()}/$token/$name?u=" + URLEncoder.encode(url.toString(), "UTF-8")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            workers.execute { serve(connection) }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream(), BUFFER)
                val requestLine = readLine(input) ?: return
                val incoming = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) incoming[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0)?.uppercase() ?: return
                val target = parts.getOrNull(1) ?: return
                if (method != "GET" && method != "HEAD") return reply(output, 405, "Method Not Allowed")

                val token = target.removePrefix("/").substringBefore('/').substringBefore('?')
                val headers = sessions[token] ?: return reply(output, 404, "Not Found")
                val upstream = target.substringAfter("?u=", "").substringBefore('&')
                    .let { URLDecoder.decode(it, "UTF-8") }
                    .toHttpUrlOrNull() ?: return reply(output, 400, "Bad Request")

                val request = Request.Builder().url(upstream).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                    if ("User-Agent" !in headers) incoming["user-agent"]?.let { header("User-Agent", it) }
                    incoming["range"]?.let { header("Range", it) }
                    if (method == "HEAD") head()
                }.build()

                client.newCall(request).execute().use { response ->
                    if (method == "GET" && isPlaylist(response)) {
                        sendPlaylist(output, token, response)
                    } else {
                        sendStream(output, response, withBody = method == "GET")
                    }
                }
            } catch (e: IOException) {
                // VLC a fermé la connexion (arrêt, changement de chaîne, saut dans la vidéo) ou serveur injoignable
            } catch (e: IllegalArgumentException) {
                // lien illisible
            }
        }
    }

    private fun isPlaylist(response: Response): Boolean {
        if (!response.isSuccessful) return false
        val type = response.header("Content-Type").orEmpty().lowercase()
        if ("mpegurl" in type) return true
        if (response.request.url.encodedPath.lowercase().endsWith(".m3u8")) return true
        if (type.startsWith("video/") || type.startsWith("audio/")) return false
        val length = response.body?.contentLength() ?: -1
        if (length > 4_000_000) return false
        return runCatching { response.peekBody(7).string() == "#EXTM3U" }.getOrDefault(false)
    }

    /** Liste HLS : chaque lien (segments, variantes, clés, sous-titres) est renvoyé vers le relais. */
    private fun sendPlaylist(output: OutputStream, token: String, response: Response) {
        val base = response.request.url // après les redirections
        val text = response.body?.string().orEmpty()
        val uriAttribute = Regex("URI=\"([^\"]+)\"")
        val rewritten = text.lineSequence().joinToString("\n") { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> line
                line.startsWith("#") -> uriAttribute.replace(line) { match ->
                    val absolute = base.resolve(match.groupValues[1])
                    if (absolute != null) "URI=\"${local(token, absolute)}\"" else match.value
                }
                else -> base.resolve(line.trim())?.let { local(token, it) } ?: line
            }
        }
        val bytes = rewritten.toByteArray(Charsets.UTF_8)
        writeHead(
            output,
            200,
            "OK",
            listOf("Content-Type" to "application/vnd.apple.mpegurl", "Content-Length" to bytes.size.toString(), "Cache-Control" to "no-cache"),
        )
        output.write(bytes)
        output.flush()
    }

    /** Vidéo (direct ou film) : statut et en-têtes utiles transmis, puis les données au fil de l'eau. */
    private fun sendStream(output: OutputStream, response: Response, withBody: Boolean) {
        val kept = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")
            .mapNotNull { name -> response.header(name)?.let { name to it } }
        writeHead(output, response.code, response.message.ifEmpty { "OK" }, kept)
        output.flush()
        if (!withBody) return
        val body: InputStream = response.body?.byteStream() ?: return
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = body.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            // on pousse tout de suite : pour le direct, VLC ne doit pas attendre que le tampon se remplisse
            if (body.available() == 0) output.flush()
        }
        output.flush()
    }

    private fun reply(output: OutputStream, code: Int, message: String) {
        writeHead(output, code, message, listOf("Content-Length" to "0"))
        output.flush()
    }

    private fun writeHead(output: OutputStream, code: Int, message: String, headers: List<Pair<String, String>>) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(message).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    /** Une ligne de la requête de VLC (terminée par \r\n), ou null si la connexion est fermée. */
    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
            if (bytes.size() > 16 * 1024) return null
        }
        return bytes.toString("ISO-8859-1")
    }
}
