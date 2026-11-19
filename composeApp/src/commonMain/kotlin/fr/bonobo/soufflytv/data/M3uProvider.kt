package fr.bonobo.soufflytv.data

import fr.bonobo.soufflytv.model.Category
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.Programme
import fr.bonobo.soufflytv.model.Section
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.model.StreamRequest
import fr.bonobo.soufflytv.platform.nowMillis
import fr.bonobo.soufflytv.platform.readAppFile
import fr.bonobo.soufflytv.platform.streamText
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class M3uProvider(
    private val source: Source.M3u,
    private val http: HttpClient,
) : IptvProvider {

    override val sections = listOf(Section.LIVE)

    private val mutex = Mutex()
    private var playlist: M3uPlaylist? = null

    private val epgMutex = Mutex()
    private var epgByChannel: Map<String, List<Programme>>? = null

    private suspend fun load(): M3uPlaylist = mutex.withLock {
        playlist ?: run {
            val text = if (source.localFile != null) {
                readAppFile(source.localFile) ?: throw IptvException("Fichier de playlist introuvable : importe-le à nouveau")
            } else {
                download()
            }
            if (!text.contains("#EXTINF", ignoreCase = true) && !text.trimStart().startsWith("#EXTM3U")) {
                throw IptvException("Ce contenu n'est pas une playlist M3U")
            }
            val parsed = withContext(Dispatchers.Default) { M3uParser.parse(text) }
            if (parsed.channels.isEmpty()) throw IptvException("La playlist est vide")
            playlist = parsed
            parsed
        }
    }

    private suspend fun download(): String {
        val response = http.get(normalizeUrl(source.url)) {
            header("User-Agent", source.userAgent ?: DEFAULT_USER_AGENT)
        }
        if (!response.status.isSuccess()) {
            throw IptvException("Playlist inaccessible (HTTP ${response.status.value})")
        }
        return response.bodyAsText()
    }

    override suspend fun categories(section: Section): List<Category> =
        load().channels.mapNotNull { it.categoryId }.distinct().map { Category(it, it) }

    override suspend fun channels(section: Section, categoryId: String?): List<Channel> {
        val list = load().channels
        return if (categoryId == null) list else list.filter { it.categoryId == categoryId }
    }

    override suspend fun resolve(channel: Channel): StreamRequest {
        val headers = buildMap {
            put("User-Agent", channel.headers["User-Agent"] ?: source.userAgent ?: DEFAULT_USER_AGENT)
            channel.headers["Referer"]?.let { put("Referer", it) }
        }
        return StreamRequest(channel.stream, channel.name, headers)
    }

    // ---------- Guide TV (XMLTV) ----------

    private fun epgUrls(): List<String> {
        val custom = source.epgUrl?.split(',', ' ', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return custom.ifEmpty { playlist?.epgUrls.orEmpty() }
    }

    override val hasLocalEpg: Boolean get() = epgUrls().isNotEmpty()

    private var epgSavedAt = 0L

    /** Guide enregistré sur l'appareil : affiché dès l'ouverture, sans attendre le réseau. */
    override suspend fun loadCachedEpg(): Boolean = epgMutex.withLock {
        if (epgByChannel != null) return@withLock true
        val cached = EpgCache.load(source.id) ?: return@withLock false
        epgSavedAt = cached.first
        epgByChannel = cached.second
        true
    }

    /** Télécharge le guide s'il manque ou a plus de 6 h, puis l'enregistre. */
    override suspend fun preloadEpg(): Unit = epgMutex.withLock {
        val now = nowMillis()
        if (epgByChannel != null && now - epgSavedAt < EpgCache.REFRESH_MS) return@withLock
        val channels = load().channels
        val urls = epgUrls()
        if (urls.isEmpty()) {
            if (epgByChannel == null) epgByChannel = emptyMap()
            epgSavedAt = now
            return@withLock
        }
        val parser = XmltvParser(
            wantedIds = channels.mapNotNull { it.epgId?.lowercase() }.toSet(),
            wantedNames = channels.map { normalizeChannelName(it.name) }.toSet(),
            from = now - 3 * HOUR_MS,
            to = now + 36 * HOUR_MS,
        )
        val headers = mapOf("User-Agent" to (source.userAgent ?: DEFAULT_USER_AGENT))
        for (url in urls) {
            try {
                streamText(normalizeUrl(url), headers) { parser.feed(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // un guide injoignable ne doit pas bloquer la lecture
            }
        }
        val programmes = withContext(Dispatchers.Default) { parser.finish() }
        val map = HashMap<String, List<Programme>>()
        for (ch in channels) {
            val key = ch.epgId?.lowercase()?.takeIf { it in programmes }
                ?: parser.idForName(normalizeChannelName(ch.name))
            val list = key?.let { programmes[it] }
            if (!list.isNullOrEmpty()) map[ch.id] = list
        }
        shareEpgBetweenVariants(channels, map)
        if (map.isNotEmpty()) {
            epgByChannel = map
            epgSavedAt = now
            EpgCache.save(source.id, now, map)
        } else {
            if (epgByChannel == null) epgByChannel = emptyMap()
            epgSavedAt = now - EpgCache.REFRESH_MS + 30 * MINUTE_MS // nouvel essai dans 30 min
        }
        Unit
    }

    override suspend fun epg(channel: Channel): List<Programme> {
        // guide déjà en mémoire ou enregistré : réponse immédiate, sinon on le télécharge
        if (epgByChannel == null && !loadCachedEpg()) preloadEpg()
        return epgByChannel?.get(channel.id).orEmpty()
    }

    override fun epgNow(channel: Channel): Programme? {
        val now = nowMillis()
        return epgByChannel?.get(channel.id)?.firstOrNull { it.isOnAir(now) }
    }
}
