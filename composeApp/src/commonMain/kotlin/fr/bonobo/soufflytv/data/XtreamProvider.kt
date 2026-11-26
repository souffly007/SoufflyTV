package fr.bonobo.soufflytv.data

import fr.bonobo.soufflytv.model.Category
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.ItemType
import fr.bonobo.soufflytv.model.MovieInfo
import fr.bonobo.soufflytv.model.Programme
import fr.bonobo.soufflytv.model.Season
import fr.bonobo.soufflytv.model.Section
import fr.bonobo.soufflytv.model.SeriesInfo
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.model.StreamRequest
import fr.bonobo.soufflytv.platform.nowMillis
import fr.bonobo.soufflytv.platform.streamText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * API « Xtream Codes » : player_api.php?username=…&password=…&action=…
 */
class XtreamProvider(
    private val source: Source.Xtream,
    private val http: HttpClient,
) : IptvProvider {

    override val sections = listOf(Section.LIVE, Section.MOVIES, Section.SERIES)

    private val base = normalizeUrl(source.server)
        .substringBefore('?')
        .removeSuffix("/")
        .removeSuffix("/player_api.php")
        .removeSuffix("/get.php")
        .removeSuffix("/")

    private val userPath = source.username.encodeURLParameter()
    private val passPath = source.password.encodeURLParameter()

    /**
     * Lien d'image : complet (http…), sans protocole (//hôte/…), chemin TMDB (/t/p/…),
     * chemin du serveur (/images/…) ou hôte sans protocole (image.tmdb.org/…). null si vide.
     */
    private fun imageUrl(raw: String?): String? {
        val v = raw?.trim()?.replace("\\/", "/")?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return when {
            v.startsWith("http://", ignoreCase = true) || v.startsWith("https://", ignoreCase = true) -> v
            v.startsWith("//") -> "https:$v"
            v.startsWith("/t/p/") -> "https://image.tmdb.org$v"
            v.startsWith("/") -> "$base$v"
            Regex("^[A-Za-z0-9.-]+\\.[A-Za-z]{2,}/").containsMatchIn(v) -> "https://$v"
            else -> null
        }
    }

    /** Extension des flux live autorisée par le compte (ts ou m3u8). */
    private var liveExt: String? = null

    /** Fuseau horaire du serveur (server_info.timezone) : l'heure de début d'un replay s'y exprime. */
    private var serverZone: String? = null

    private val categoryCache = HashMap<Section, List<Category>>()
    private val channelCache = HashMap<Pair<Section, String?>, List<Channel>>()
    private val seriesCache = HashMap<String, SeriesInfo>()
    private val epgCache = HashMap<String, Pair<Long, List<Programme>>>()

    /** Guide complet (xmltv.php) : émissions par identifiant de chaîne. */
    private val xmltvMutex = Mutex()
    private var xmltv: Map<String, List<Programme>>? = null

    private suspend fun api(action: String?, vararg extra: Pair<String, String>): JsonElement {
        val text = apiText(action, *extra)
        return withContext(Dispatchers.Default) { parseJsonOrNull(text) }
            ?: throw IptvException("Réponse du serveur illisible (est-ce bien un serveur Xtream ?)")
    }

    /** Réponse brute de player_api.php (texte). */
    private suspend fun apiText(action: String?, vararg extra: Pair<String, String>): String {
        val response = http.get("$base/player_api.php") {
            header("User-Agent", DEFAULT_USER_AGENT)
            parameter("username", source.username)
            parameter("password", source.password)
            if (action != null) parameter("action", action)
            extra.forEach { (k, v) -> parameter(k, v) }
        }
        if (!response.status.isSuccess()) {
            throw IptvException(
                if (response.status.value == 401 || response.status.value == 403) "Identifiants refusés par le serveur"
                else "Serveur Xtream : erreur HTTP ${response.status.value}"
            )
        }
        return response.bodyAsText()
    }

    private suspend fun ensureLogin() {
        if (liveExt != null) return
        val info = api(null) as? JsonObject ?: throw IptvException("Réponse inattendue du serveur")
        val user = info["user_info"] as? JsonObject ?: throw IptvException("Identifiants refusés par le serveur")
        if (user.s("auth") != "1") throw IptvException("Identifiants refusés par le serveur")
        val status = user.s("status")
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw IptvException("Compte inactif (statut : $status)")
        }
        serverZone = (info["server_info"] as? JsonObject)?.s("timezone")
        val formats = (user["allowed_output_formats"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        liveExt = when {
            formats.isEmpty() || "ts" in formats -> "ts"
            "m3u8" in formats -> "m3u8"
            else -> formats.first()
        }
    }

    override suspend fun categories(section: Section): List<Category> {
        categoryCache[section]?.let { return it }
        ensureLogin()
        val action = when (section) {
            Section.LIVE -> "get_live_categories"
            Section.MOVIES -> "get_vod_categories"
            Section.SERIES -> "get_series_categories"
        }
        val json = api(action)
        val list = withContext(Dispatchers.Default) {
            json.asObjects().mapNotNull { o ->
                val id = o.s("category_id") ?: return@mapNotNull null
                Category(id, o.s("category_name") ?: "Sans nom")
            }
        }
        categoryCache[section] = list
        return list
    }

    /** Une seule grosse liste lue à la fois : évite de cumuler plusieurs pics de mémoire. */
    private val listLock = Mutex()

    override suspend fun channels(section: Section, categoryId: String?): List<Channel> {
        val key = section to categoryId
        channelCache[key]?.let { return it }
        // liste complète déjà en mémoire : une catégorie s'en déduit, sans nouvelle requête ni copie en double
        if (categoryId != null) {
            channelCache[section to null]?.let { all -> return all.filter { it.categoryId == categoryId } }
        }
        return listLock.withLock {
            channelCache[key]?.let { return@withLock it }
            ensureLogin()
            val action = when (section) {
                Section.LIVE -> "get_live_streams"
                Section.MOVIES -> "get_vod_streams"
                Section.SERIES -> "get_series"
            }
            val text = if (categoryId == null) apiText(action) else apiText(action, "category_id" to categoryId)
            val ext = liveExt ?: "ts"
            val list = withContext(Dispatchers.Default) {
                val items = decodeXtItems(text)
                    ?: parseJsonOrNull(text)?.asObjects()?.map { XtItem.from(it) }
                    ?: throw IptvException("Réponse du serveur illisible (est-ce bien un serveur Xtream ?)")
                items.mapNotNull { toChannel(it, section, ext) }
            }
            channelCache[key] = list
            list
        }
    }

    private fun toChannel(o: XtItem, section: Section, ext: String): Channel? = when (section) {
        Section.LIVE -> o.streamId.clean()?.let { id ->
            Channel(
                id = "xt-live-$id",
                name = o.name.clean() ?: "Chaîne $id",
                logo = imageUrl(o.streamIcon.clean()),
                categoryId = o.categoryId.clean(),
                stream = "$base/live/$userPath/$passPath/$id.$ext",
                number = o.num.clean()?.toIntOrNull(),
                type = ItemType.LIVE,
                epgId = id,
                tvgId = o.epgChannelId.clean(),
                // Replay : tv_archive = 1, tv_archive_duration = nombre de jours gardés
                archiveDays = if (o.tvArchive.clean() == "1") {
                    (o.tvArchiveDuration.clean()?.toIntOrNull() ?: 1).coerceIn(1, 30)
                } else 0,
            )
        }
        Section.MOVIES -> o.streamId.clean()?.let { id ->
            val extension = o.containerExtension.clean() ?: "mp4"
            Channel(
                id = "xt-movie-$id",
                name = o.name.clean() ?: "Film $id",
                logo = imageUrl(o.streamIcon.clean()),
                categoryId = o.categoryId.clean(),
                stream = "$base/movie/$userPath/$passPath/$id.$extension",
                number = o.num.clean()?.toIntOrNull(),
                type = ItemType.MOVIE,
                plot = o.plot.clean(),
                rating = o.rating.clean(),
                added = o.added.clean()?.toLongOrNull()?.takeIf { it > 0 }?.times(1000),
            )
        }
        Section.SERIES -> o.seriesId.clean()?.let { id ->
            Channel(
                id = "xt-series-$id",
                name = o.name.clean() ?: "Série $id",
                logo = imageUrl(o.cover.clean()),
                categoryId = o.categoryId.clean(),
                stream = id,
                number = o.num.clean()?.toIntOrNull(),
                type = ItemType.SERIES,
                plot = o.plot.clean(),
                rating = o.rating.clean(),
                added = o.lastModified.clean()?.toLongOrNull()?.takeIf { it > 0 }?.times(1000),
            )
        }
    }

    override suspend fun resolve(channel: Channel): StreamRequest =
        StreamRequest(channel.stream, channel.name, mapOf("User-Agent" to DEFAULT_USER_AGENT))

    // ---------- Séries ----------

    private val movieCache = HashMap<String, MovieInfo>()

    /** Fiche complète du film (get_vod_info) ; en cas d'échec, ce que la liste fournit. */
    override suspend fun movieInfo(movie: Channel): MovieInfo {
        movieCache[movie.id]?.let { return it }
        val basic = MovieInfo(movie.name, movie.plot, movie.logo)
        val vodId = movie.id.removePrefix("xt-movie-").takeIf { it != movie.id } ?: return basic
        val json = try {
            ensureLogin()
            api("get_vod_info", "vod_id" to vodId) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val info = json?.get("info") as? JsonObject ?: return basic
        fun link(value: String?) = imageUrl(value)
        val backdrop = when (val b = info["backdrop_path"]) {
            is JsonArray -> (b.firstOrNull() as? JsonPrimitive)?.content
            is JsonPrimitive -> b.content
            else -> null
        }
        val minutes = info.s("duration_secs")?.toIntOrNull()?.takeIf { it > 0 }?.let { (it + 30) / 60 }
            ?: parseRuntimeMinutes(info.s("duration"))
            ?: info.s("episode_run_time")?.toIntOrNull()
        val result = MovieInfo(
            name = movie.name,
            plot = info.s("plot") ?: info.s("description") ?: movie.plot,
            cover = link(info.s("movie_image")) ?: link(info.s("cover_big")) ?: movie.logo,
            backdrop = link(backdrop),
            meta = movieMetaLine(
                info.s("releasedate") ?: info.s("release_date") ?: info.s("year"),
                info.s("genre"),
                minutes,
                info.s("rating"),
            ),
            director = info.s("director")?.takeIf { it.isNotBlank() },
            cast = (info.s("cast") ?: info.s("actors"))?.takeIf { it.isNotBlank() },
            trailer = trailerUrl(info.s("youtube_trailer") ?: info.s("trailer")),
        )
        movieCache[movie.id] = result
        return result
    }

    /** Une seule requête (get_series_info), gardée en cache pour l'ouverture de la série. */
    override suspend fun seriesSummary(series: Channel): MovieInfo {
        val info = try {
            seriesInfo(series)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return super.seriesSummary(series)
        }
        val seasons = info.seasons.size
        return MovieInfo(
            name = series.name,
            plot = info.plot,
            cover = info.cover,
            backdrop = info.backdrop,
            meta = listOfNotNull(info.meta, if (seasons > 1) "$seasons saisons" else "1 saison").joinToString(" · "),
            director = info.director,
            cast = info.cast,
            trailer = info.trailer,
        )
    }

    override suspend fun seriesInfo(series: Channel): SeriesInfo {
        seriesCache[series.stream]?.let { return it }
        ensureLogin()
        val json = api("get_series_info", "series_id" to series.stream) as? JsonObject
            ?: throw IptvException("Série introuvable")
        val info = json["info"] as? JsonObject
        val seasons = withContext(Dispatchers.Default) {
            val bySeason = LinkedHashMap<Int, MutableList<Channel>>()
            fun add(o: JsonObject, seasonHint: Int?) {
                val (season, episode) = episodeOf(o, seasonHint) ?: return
                bySeason.getOrPut(season) { ArrayList() } += episode
            }
            when (val episodes = json["episodes"]) {
                is JsonObject -> episodes.forEach { (key, value) ->
                    value.asObjects().forEach { add(it, key.toIntOrNull()) }
                }
                is JsonArray -> episodes.forEach { el ->
                    when (el) {
                        is JsonArray -> el.asObjects().forEach { add(it, null) }
                        is JsonObject -> add(el, null)
                        else -> Unit
                    }
                }
                else -> Unit
            }
            bySeason.entries.sortedBy { it.key }.map { (n, list) -> Season(n, list.sortedBy { it.number ?: 0 }) }
        }
        if (seasons.isEmpty()) throw IptvException("Aucun épisode disponible pour cette série")
        val meta = movieMetaLine(
            info?.s("releaseDate") ?: info?.s("release_date") ?: info?.s("year"),
            info?.s("genre"),
            null,
            info?.s("rating") ?: series.rating,
        )
        val backdrop = when (val b = info?.get("backdrop_path")) {
            is JsonArray -> (b.firstOrNull() as? JsonPrimitive)?.content
            is JsonPrimitive -> b.content
            else -> null
        }.let(::imageUrl)
        val result = SeriesInfo(
            name = info?.s("name") ?: series.name,
            plot = info?.s("plot") ?: series.plot,
            cover = imageUrl(info?.s("cover")) ?: series.logo,
            meta = meta,
            seasons = seasons,
            backdrop = backdrop,
            director = info?.s("director")?.takeIf { it.isNotBlank() },
            cast = (info?.s("cast") ?: info?.s("actors"))?.takeIf { it.isNotBlank() },
            trailer = trailerUrl(info?.s("youtube_trailer") ?: info?.s("trailer")),
        )
        seriesCache[series.stream] = result
        return result
    }

    private fun episodeOf(o: JsonObject, seasonHint: Int?): Pair<Int, Channel>? {
        val id = o.s("id") ?: return null
        val season = o.s("season")?.toIntOrNull() ?: seasonHint ?: 1
        val ext = o.s("container_extension") ?: "mp4"
        val info = o["info"] as? JsonObject
        val number = o.s("episode_num")?.toIntOrNull()
        return season to Channel(
            id = "xt-ep-$id",
            name = o.s("title") ?: "Épisode ${number ?: ""}".trim(),
            logo = imageUrl(info?.s("movie_image")),
            stream = "$base/series/$userPath/$passPath/$id.$ext",
            number = number,
            type = ItemType.EPISODE,
            plot = info?.s("plot"),
        )
    }

    // ---------- Guide TV ----------

    /** Le serveur Xtream fournit un guide XMLTV complet : on peut l'afficher dans les listes. */
    override val hasLocalEpg: Boolean get() = true

    private var xmltvSavedAt = 0L

    /** Guide enregistré sur l'appareil : affiché dès l'ouverture, sans attendre le réseau. */
    override suspend fun loadCachedEpg(): Boolean = xmltvMutex.withLock {
        if (xmltv != null) return@withLock true
        val cached = EpgCache.load(source.id) ?: return@withLock false
        xmltvSavedAt = cached.first
        xmltv = cached.second
        true
    }

    /** Télécharge le guide complet s'il manque ou a plus de 6 h, puis l'enregistre. */
    override suspend fun preloadEpg(): Unit = xmltvMutex.withLock {
        val now = nowMillis()
        if (xmltv != null && now - xmltvSavedAt < EpgCache.refreshMs) return@withLock
        val channels = try {
            channels(Section.LIVE, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
        val map = HashMap<String, List<Programme>>()
        val ids = channels.mapNotNull { it.tvgId?.lowercase() }.toSet()
        if (ids.isNotEmpty()) {
            val parser = XmltvParser(ids, emptySet(), now - 3 * HOUR_MS, now + 36 * HOUR_MS)
            try {
                streamText(
                    "$base/xmltv.php?username=$userPath&password=$passPath",
                    mapOf("User-Agent" to DEFAULT_USER_AGENT),
                ) { parser.feed(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // guide complet indisponible : le lecteur se rabat sur get_short_epg
            }
            val programmes = withContext(Dispatchers.Default) { parser.finish() }
            for (ch in channels) {
                val list = ch.tvgId?.lowercase()?.let { programmes[it] }
                if (!list.isNullOrEmpty()) map[ch.id] = list
            }
        }
        // TF1 4K sans guide reprend celui de TF1 HD, etc.
        shareEpgBetweenVariants(channels, map)
        if (map.isNotEmpty()) {
            xmltv = map
            xmltvSavedAt = now
            EpgCache.save(source.id, now, map)
        } else {
            if (xmltv == null) xmltv = emptyMap()
            xmltvSavedAt = now - EpgCache.refreshMs + 30 * MINUTE_MS // nouvel essai dans 30 min
        }
        Unit
    }

    override fun epgNow(channel: Channel): Programme? {
        val now = nowMillis()
        return xmltv?.get(channel.id)?.firstOrNull { it.isOnAir(now) }
    }

    // ---------- Replay ----------

    private val replayCache = HashMap<String, Pair<Long, List<Programme>>>()

    /**
     * Émissions archivées : get_simple_data_table donne tout le guide connu de la chaîne,
     * avec has_archive = 1 pour celles qu'on peut relire. Repli : le guide, limité à la période d'archives.
     */
    override suspend fun replayProgrammes(channel: Channel): List<Programme> {
        if (channel.archiveDays <= 0) return emptyList()
        val streamId = channel.epgId ?: return emptyList()
        replayCache[streamId]?.let { (time, list) -> if (nowMillis() - time < 10 * MINUTE_MS) return list }
        val now = nowMillis()
        val json = try {
            ensureLogin()
            api("get_simple_data_table", "stream_id" to streamId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        val listings = (json as? JsonObject)?.get("epg_listings").asObjects()
        val fromTable = listings.mapNotNull { o ->
            val start = o.s("start_timestamp")?.toLongOrNull()?.times(1000)?.plus(EpgPrefs.offsetMs) ?: return@mapNotNull null
            val stop = o.s("stop_timestamp")?.toLongOrNull()?.times(1000)?.plus(EpgPrefs.offsetMs) ?: (start + HOUR_MS)
            val title = decodeBase64OrRaw(o.s("title")) ?: return@mapNotNull null
            val archived = o.s("has_archive")
            val programme = Programme(start, stop, title, decodeBase64OrRaw(o.s("description")))
            programme.takeIf { (archived == null || archived == "1") && isReplayable(channel, it, now) }
        }.distinctBy { it.start }.sortedBy { it.start }
        val list = fromTable.ifEmpty { epg(channel).filter { isReplayable(channel, it, now) } }
        replayCache[streamId] = now to list
        return list
    }

    override suspend fun resolveReplay(replay: Channel): StreamRequest {
        val start = replay.replayStart ?: throw IptvException("Émission inconnue")
        val stop = replay.replayStop ?: (start + HOUR_MS)
        val streamId = replay.epgId ?: throw IptvException("Chaîne inconnue")
        ensureLogin()
        val url = xtreamTimeshiftUrl(base, userPath, passPath, streamId, start, stop, serverZone, liveExt ?: "ts")
        return StreamRequest(url, replay.name, mapOf("User-Agent" to DEFAULT_USER_AGENT))
    }

    override suspend fun epg(channel: Channel): List<Programme> {
        if (channel.type != ItemType.LIVE) return emptyList()
        xmltv?.get(channel.id)?.let { if (it.isNotEmpty()) return it }
        val streamId = channel.epgId ?: return emptyList()
        epgCache[streamId]?.let { (time, list) -> if (nowMillis() - time < 10 * MINUTE_MS) return list }
        val json = try {
            api("get_short_epg", "stream_id" to streamId, "limit" to "8")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return emptyList()
        }
        val list = (json as? JsonObject)?.get("epg_listings").asObjects().mapNotNull { o ->
            val start = o.s("start_timestamp")?.toLongOrNull()?.times(1000)?.plus(EpgPrefs.offsetMs) ?: return@mapNotNull null
            val stop = o.s("stop_timestamp")?.toLongOrNull()?.times(1000)?.plus(EpgPrefs.offsetMs) ?: (start + HOUR_MS)
            val title = decodeBase64OrRaw(o.s("title")) ?: return@mapNotNull null
            Programme(start, stop, title, decodeBase64OrRaw(o.s("description")))
        }.sortedBy { it.start }
        epgCache[streamId] = nowMillis() to list
        return list
    }
}
