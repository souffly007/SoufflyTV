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
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Portail Stalker / Ministra (boîtiers MAG) : l'application se présente comme un MAG250
 * identifié par son adresse MAC.
 *
 * Étapes : handshake → jeton, get_profile, listes, puis create_link à chaque lecture.
 *
 * Séries : deux organisations existent selon les portails.
 *  - Module « series » (Ministra récent) : type=series, puis saisons et épisodes via movie_id / season_id.
 *  - Ancien style : les séries sont des éléments VOD avec is_series=1 et une liste d'épisodes « series ».
 */
class StalkerProvider(
    private val source: Source.Stalker,
    private val http: HttpClient,
) : IptvProvider {

    override val sections = listOf(Section.LIVE, Section.MOVIES, Section.SERIES)

    private val mac = normalizeMac(source.mac)
    private val referer = normalizeUrl(source.portal).let { if (it.endsWith("/")) it else "$it/" }
    private val mutex = Mutex()

    private var endpoint: String? = null
    private var token: String? = null
    private var genres: List<Category>? = null
    private var vodCategories: List<Category>? = null
    private var seriesCategories: List<Category>? = null
    /** true : le portail a un module « series » ; false : séries mélangées à la VOD. */
    private var seriesModule: Boolean? = null
    private var allChannels: List<Channel>? = null
    private val vodCache = HashMap<String, List<Channel>>()
    /** Fiches des films, remplies en lisant les listes (le portail y donne déjà tout). */
    private val movieInfos = HashMap<String, MovieInfo>()
    private val seriesCache = HashMap<String, List<Channel>>()
    private val seriesInfoCache = HashMap<String, SeriesInfo>()
    private val epgCache = HashMap<String, Pair<Long, List<Programme>>>()

    // ---------- HTTP ----------

    private fun headers(withAuth: Boolean): Map<String, String> = buildMap {
        put("User-Agent", MAG_USER_AGENT)
        put("X-User-Agent", "Model: MAG250; Link: WiFi")
        put("Cookie", "mac=${mac.encodeURLParameter()}; stb_lang=fr; timezone=${"Europe/Paris".encodeURLParameter()}")
        put("Referer", referer)
        if (withAuth) token?.let { put("Authorization", "Bearer $it") }
    }

    private suspend fun rawGet(url: String, params: List<Pair<String, String>>, withAuth: Boolean): JsonElement? {
        val response = http.get(url) {
            params.forEach { (k, v) -> parameter(k, v) }
            headers(withAuth).forEach { (k, v) -> header(k, v) }
        }
        if (!response.status.isSuccess()) {
            throw IptvException("Portail : erreur HTTP ${response.status.value}")
        }
        return parseJsonOrNull(response.bodyAsText())
    }

    private fun JsonElement.js(): JsonElement? = (this as? JsonObject)?.get("js")

    /** Liste de numéros, envoyée en tableau [1,2,3] ou en texte "1,2,3". */
    private fun JsonObject.intList(key: String): List<Int> = when (val v = this[key]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.toIntOrNull() }
        is JsonNull -> emptyList()
        is JsonPrimitive -> v.content.split(',').mapNotNull { it.trim().toIntOrNull() }
        else -> emptyList()
    }

    // ---------- Authentification ----------

    private suspend fun authenticate() {
        var lastError: Throwable? = null
        for (candidate in stalkerEndpoints(source.portal)) {
            try {
                token = null
                val hs = rawGet(
                    candidate,
                    listOf(
                        "type" to "stb", "action" to "handshake", "token" to "",
                        "prehash" to "0", "JsHttpRequest" to "1-xml",
                    ),
                    withAuth = false,
                )?.js() as? JsonObject
                val newToken = hs?.s("token") ?: continue
                endpoint = candidate
                token = newToken
                loadProfile(candidate)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IptvException) {
                if (token != null) throw e // portail trouvé mais refus explicite (MAC bloquée…)
                lastError = e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        endpoint = null
        token = null
        throw IptvException(
            "Portail injoignable ou adresse MAC refusée" +
                (lastError?.let { " (${it.toUserMessage()})" } ?: "")
        )
    }

    private suspend fun loadProfile(url: String) {
        val profile = rawGet(
            url,
            listOf(
                "type" to "stb", "action" to "get_profile", "hd" to "1",
                "ver" to "ImageDescription: 0.2.18-r23-250; PORTAL version: 5.6.2; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c",
                "num_banks" to "2", "sn" to source.serialNumber.orEmpty(), "stb_type" to "MAG250",
                "client_type" to "STB", "image_version" to "218", "video_out" to "hdmi",
                "device_id" to source.deviceId.orEmpty(), "device_id2" to source.deviceId2.orEmpty(),
                "signature" to source.signature.orEmpty(), "auth_second_step" to "1",
                "hw_version" to "1.7-BD-00", "not_valid_token" to "0", "api_signature" to "262",
                "prehash" to "", "JsHttpRequest" to "1-xml",
            ),
            withAuth = true,
        )?.js() as? JsonObject
        profile?.s("token")?.let { token = it }
        if (profile?.s("blocked") == "1") throw IptvException("Adresse MAC bloquée par le portail")
    }

    /** Appel authentifié, avec une reconnexion automatique si le jeton a expiré. */
    private suspend fun api(params: List<Pair<String, String>>): JsonElement? = mutex.withLock {
        if (token == null || endpoint == null) authenticate()
        val full = params + ("JsHttpRequest" to "1-xml")
        val first = try {
            rawGet(endpoint!!, full, withAuth = true)?.js()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        if (first != null && first !is JsonNull) return@withLock first
        authenticate()
        rawGet(endpoint!!, full, withAuth = true)?.js()
    }

    /** Appel facultatif : une action que le portail ne connaît pas ne doit pas faire échouer l'écran. */
    private suspend fun apiOrNull(params: List<Pair<String, String>>): JsonElement? = try {
        api(params)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    /** Adresse de base des logos relatifs (…/stalker_portal/misc/logos/320/). */
    private fun logoUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.startsWith("http", ignoreCase = true)) return raw
        val ep = endpoint ?: return null
        val portalBase = ep.substringBefore("/server/load.php").substringBeforeLast("/portal.php")
        return "$portalBase/misc/logos/320/${raw.trimStart('/')}"
    }

    private fun toCategories(json: JsonElement?): List<Category> =
        json.asObjects().mapNotNull { o ->
            val id = o.s("id") ?: return@mapNotNull null
            if (id == "*") return@mapNotNull null
            Category(id, o.s("title") ?: o.s("name") ?: "Catégorie $id")
        }

    // ---------- Catégories ----------

    override suspend fun categories(section: Section): List<Category> = when (section) {
        Section.LIVE -> genres
            ?: toCategories(api(listOf("type" to "itv", "action" to "get_genres"))).also { genres = it }

        Section.MOVIES -> vodCategories
            ?: toCategories(api(listOf("type" to "vod", "action" to "get_categories"))).also { vodCategories = it }

        Section.SERIES -> seriesCategories ?: run {
            val module = toCategories(apiOrNull(listOf("type" to "series", "action" to "get_categories")))
            seriesModule = module.isNotEmpty()
            // Sans module « series », les séries sont rangées dans les catégories VOD
            val list = module.ifEmpty { categories(Section.MOVIES) }
            seriesCategories = list
            list
        }
    }

    override suspend fun channels(section: Section, categoryId: String?): List<Channel> = when (section) {
        Section.LIVE -> {
            val list = loadAllChannels()
            if (categoryId == null) list else list.filter { it.categoryId == categoryId }
        }
        Section.MOVIES -> loadVod(categoryId ?: "*")
        Section.SERIES -> loadSeriesList(categoryId ?: "*")
    }

    // ---------- TV en direct ----------

    private suspend fun loadAllChannels(): List<Channel> {
        allChannels?.let { return it }
        val js = api(listOf("type" to "itv", "action" to "get_all_channels"))
        var list = (js as? JsonObject)?.get("data").asObjects().mapNotNull(::toChannel)
        if (list.isEmpty()) {
            list = loadPaged(maxPages = 300) { page ->
                listOf(
                    "type" to "itv", "action" to "get_ordered_list", "genre" to "*",
                    "force_ch_link_check" to "", "fav" to "0", "sortby" to "number",
                    "hd" to "0", "p" to page.toString(),
                )
            }.mapNotNull(::toChannel)
        }
        allChannels = list
        return list
    }

    private fun toChannel(o: JsonObject): Channel? {
        val id = o.s("id") ?: return null
        val cmd = o.s("cmd") ?: return null
        return Channel(
            id = "stk-$id",
            name = o.s("name") ?: "Chaîne $id",
            logo = logoUrl(o.s("logo")),
            categoryId = o.s("tv_genre_id"),
            stream = cmd,
            number = o.s("number")?.toIntOrNull(),
            type = ItemType.LIVE,
            epgId = id,
            archiveDays = stalkerArchiveDays(o),
        )
    }

    /** Replay : enable_tv_archive / tv_archive = 1, tv_archive_duration en heures. */
    private fun stalkerArchiveDays(o: JsonObject): Int {
        val enabled = o.s("enable_tv_archive") == "1" || o.s("tv_archive") == "1"
        if (!enabled) return 0
        val hours = o.s("tv_archive_duration")?.toIntOrNull() ?: 0
        return if (hours <= 0) 3 else ((hours + 23) / 24).coerceIn(1, 30)
    }

    // ---------- Films ----------

    private suspend fun loadVod(category: String): List<Channel> {
        vodCache[category]?.let { return it }
        val list = loadPaged(maxPages = if (category == "*") 40 else 150) { page ->
            listOf(
                "type" to "vod", "action" to "get_ordered_list", "category" to category,
                "fav" to "0", "sortby" to "added", "hd" to "0", "p" to page.toString(),
            )
        }.mapNotNull { o ->
            // Ancien style : une série rangée dans la VOD s'ouvre sur ses épisodes
            if (isSeriesItem(o)) return@mapNotNull seriesChannel(o, module = false)
            val id = o.s("id") ?: return@mapNotNull null
            val cmd = o.s("cmd") ?: return@mapNotNull null
            val channel = Channel(
                id = "stk-vod-$id",
                name = o.s("name") ?: "Film $id",
                logo = logoUrl(o.s("screenshot_uri")),
                categoryId = o.s("category_id") ?: category,
                stream = cmd,
                type = ItemType.MOVIE,
                plot = o.s("description"),
                rating = o.s("rating_imdb"),
            )
            movieInfos[channel.id] = MovieInfo(
                name = channel.name,
                plot = channel.plot,
                cover = channel.logo,
                meta = movieMetaLine(o.s("year"), o.s("genres_str"), o.s("time")?.toIntOrNull(), o.s("rating_imdb")),
                director = o.s("director")?.takeIf { it.isNotBlank() },
                cast = o.s("actors")?.takeIf { it.isNotBlank() },
            )
            channel
        }
        vodCache[category] = list
        return list
    }

    override suspend fun movieInfo(movie: Channel): MovieInfo =
        movieInfos[movie.id] ?: MovieInfo(movie.name, movie.plot, movie.logo)

    // ---------- Séries ----------

    private fun isSeriesItem(o: JsonObject): Boolean =
        o.s("is_series") == "1" || o.s("is_series") == "true" || o.intList("series").isNotEmpty()

    private fun seriesChannel(o: JsonObject, module: Boolean): Channel? {
        val id = o.s("id") ?: return null
        return Channel(
            id = if (module) "stk-ser-$id" else "stk-vser-$id",
            name = o.s("name") ?: "Série $id",
            logo = logoUrl(o.s("screenshot_uri")),
            categoryId = o.s("category_id"),
            // module : identifiant de la série ; ancien style : commande de lecture commune aux épisodes
            stream = if (module) id else (o.s("cmd") ?: id),
            type = ItemType.SERIES,
            // ancien style : numéros d'épisodes « 1,2,3… »
            epgId = if (module) null else o.intList("series").joinToString(",").ifEmpty { null },
            plot = o.s("description"),
        )
    }

    private suspend fun loadSeriesList(category: String): List<Channel> {
        seriesCache[category]?.let { return it }
        if (seriesModule == null) categories(Section.SERIES)
        val module = seriesModule == true
        val list = loadPaged(maxPages = if (category == "*") 40 else 150) { page ->
            listOf(
                "type" to if (module) "series" else "vod", "action" to "get_ordered_list",
                "category" to category, "fav" to "0", "sortby" to "added", "hd" to "0",
                "p" to page.toString(),
            )
        }.mapNotNull { o ->
            when {
                module -> seriesChannel(o, module = true)
                isSeriesItem(o) -> seriesChannel(o, module = false)
                else -> null
            }
        }
        seriesCache[category] = list
        return list
    }

    override suspend fun seriesInfo(series: Channel): SeriesInfo {
        seriesInfoCache[series.id]?.let { return it }
        val numbers = series.epgId?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val seasons = if (series.id.startsWith("stk-vser-") && numbers.isNotEmpty()) {
            // Ancien style : une saison, même commande pour tous les épisodes, numéro passé en « series »
            listOf(
                Season(1, numbers.map { n ->
                    episodeChannel(series, 1, n, "Épisode $n", series.stream, n.toString(), null)
                })
            )
        } else {
            loadSeasons(series)
        }
        if (seasons.isEmpty()) throw IptvException("Aucun épisode disponible pour cette série")
        val info = SeriesInfo(
            name = series.name,
            plot = series.plot,
            cover = series.logo,
            meta = "${seasons.size} saison(s) · ${seasons.sumOf { it.episodes.size }} épisode(s)",
            seasons = seasons,
        )
        seriesInfoCache[series.id] = info
        return info
    }

    /** Saisons puis épisodes via movie_id / season_id. */
    private suspend fun loadSeasons(series: Channel): List<Season> {
        val rawId = series.id.removePrefix("stk-ser-").removePrefix("stk-vser-")
        val type = if (series.id.startsWith("stk-ser-")) "series" else "vod"
        val seasonItems = loadPaged(maxPages = 20) { page ->
            listOf(
                "type" to type, "action" to "get_ordered_list", "movie_id" to rawId,
                "season_id" to "0", "episode_id" to "0", "p" to page.toString(),
            )
        }
        val seasons = ArrayList<Season>()
        for ((index, season) in seasonItems.withIndex()) {
            val seasonNumber = season.s("season_number")?.toIntOrNull()
                ?: Regex("\\d+").find(season.s("name").orEmpty())?.value?.toIntOrNull()
                ?: (index + 1)
            val seasonCmd = season.s("cmd")
            val episodeNumbers = season.intList("series")

            val episodes: List<Channel> = if (episodeNumbers.isNotEmpty() && seasonCmd != null) {
                // La saison porte la liste des épisodes et une commande commune
                episodeNumbers.map { n ->
                    episodeChannel(series, seasonNumber, n, "Épisode $n", seasonCmd, n.toString(), null)
                }
            } else {
                val seasonId = season.s("id") ?: continue
                loadPaged(maxPages = 20) { page ->
                    listOf(
                        "type" to type, "action" to "get_ordered_list", "movie_id" to rawId,
                        "season_id" to seasonId, "episode_id" to "0", "p" to page.toString(),
                    )
                }.mapIndexedNotNull { i, ep ->
                    val cmd = ep.s("cmd") ?: return@mapIndexedNotNull null
                    val seriesParam = ep.intList("series").firstOrNull()
                    val n = ep.s("series_number")?.toIntOrNull() ?: seriesParam ?: (i + 1)
                    episodeChannel(
                        series, seasonNumber, n, ep.s("name") ?: "Épisode $n", cmd,
                        seriesParam?.toString(), logoUrl(ep.s("screenshot_uri")),
                    )
                }
            }
            if (episodes.isNotEmpty()) seasons += Season(seasonNumber, episodes.sortedBy { it.number ?: 0 })
        }
        return seasons.sortedBy { it.number }
    }

    private fun episodeChannel(
        series: Channel,
        season: Int,
        number: Int,
        title: String,
        cmd: String,
        seriesParam: String?,
        logo: String?,
    ) = Channel(
        id = "${series.id}-s$season-e$number",
        name = title,
        logo = logo ?: series.logo,
        stream = cmd,
        number = number,
        type = ItemType.EPISODE,
        // numéro d'épisode à transmettre au portail lors du create_link
        epgId = seriesParam,
        plot = series.plot,
    )

    // ---------- Pagination ----------

    /** Listes paginées du portail : on enchaîne les pages jusqu'à total_items. */
    private suspend fun loadPaged(maxPages: Int, params: (Int) -> List<Pair<String, String>>): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var page = 1
        while (page <= maxPages) {
            val js = api(params(page)) as? JsonObject ?: break
            val data = js["data"].asObjects()
            if (data.isEmpty()) break
            out += data
            val total = js.s("total_items")?.toIntOrNull() ?: break
            if (out.size >= total) break
            page++
        }
        return out
    }

    // ---------- Lecture ----------

    override suspend fun resolve(channel: Channel): StreamRequest {
        val linked = try {
            (api(
                listOf(
                    "type" to if (channel.type == ItemType.LIVE) "itv" else "vod",
                    "action" to "create_link", "cmd" to channel.stream,
                    "series" to if (channel.type == ItemType.EPISODE) channel.epgId.orEmpty() else "",
                    "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0",
                )
            ) as? JsonObject)?.s("cmd")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IptvException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        val url = extractStreamUrl(linked ?: channel.stream)
            ?: throw IptvException("Le portail n'a pas fourni de lien de lecture")
        if ("://localhost" in url) throw IptvException("Le portail n'a pas fourni de lien de lecture valide")
        return StreamRequest(url, channel.name, mapOf("User-Agent" to MAG_USER_AGENT))
    }

    // ---------- Replay ----------

    private val replayCache = HashMap<String, Pair<Long, List<Programme>>>()

    /**
     * Émissions archivées : get_all_program_for_ch si le portail le connaît, sinon
     * get_simple_data_table jour par jour (mark_archive = 1 pour celles qu'on peut relire).
     */
    override suspend fun replayProgrammes(channel: Channel): List<Programme> {
        if (channel.archiveDays <= 0) return emptyList()
        val chId = channel.epgId ?: return emptyList()
        replayCache[chId]?.let { (time, list) -> if (nowMillis() - time < 10 * MINUTE_MS) return list }
        val now = nowMillis()
        fun parse(items: List<JsonObject>) = items.mapNotNull { o ->
            val start = o.s("start_timestamp")?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            val stop = o.s("stop_timestamp")?.toLongOrNull()?.times(1000) ?: (start + HOUR_MS)
            val title = o.s("name") ?: return@mapNotNull null
            val archived = o.s("mark_archive")
            Programme(start, stop, title, o.s("descr"), id = o.s("id"))
                .takeIf { (archived == null || archived == "1") && isReplayable(channel, it, now) }
        }
        fun items(js: JsonElement?): List<JsonObject> =
            if (js is JsonObject && js["data"] != null) js["data"].asObjects() else js.asObjects()

        var list = parse(items(apiOrNull(listOf("type" to "epg", "action" to "get_all_program_for_ch", "ch_id" to chId))))
        if (list.isEmpty()) {
            val found = ArrayList<Programme>()
            for (day in 0 until channel.archiveDays.coerceAtMost(7)) {
                val date = fr.bonobo.soufflytv.platform.formatZoned(now - day * 24 * HOUR_MS, null, "yyyy-MM-dd")
                for (page in 1..10) {
                    val js = apiOrNull(
                        listOf("type" to "epg", "action" to "get_simple_data_table", "ch_id" to chId, "date" to date, "p" to page.toString())
                    )
                    val pageItems = items(js)
                    if (pageItems.isEmpty()) break
                    found += parse(pageItems)
                    val total = (js as? JsonObject)?.s("total_items")?.toIntOrNull()
                    val perPage = (js as? JsonObject)?.s("max_page_items")?.toIntOrNull()
                    if (total == null || perPage == null || page * perPage >= total) break
                }
            }
            list = found
        }
        list = list.filter { it.id != null }.distinctBy { it.start }.sortedBy { it.start }
        replayCache[chId] = now to list
        return list
    }

    override suspend fun resolveReplay(replay: Channel): StreamRequest {
        val programmeId = replay.replayId ?: throw IptvException("Le portail n'a pas donné d'identifiant pour cette émission")
        val linked = (api(
            listOf(
                "type" to "tv_archive", "action" to "create_link", "cmd" to "auto /media/$programmeId.mpg",
                "series" to "", "forced_storage" to "", "disable_ad" to "0", "download" to "0",
            )
        ) as? JsonObject)?.s("cmd")
        val url = linked?.let(::extractStreamUrl)
            ?: throw IptvException("Le portail n'a pas fourni de lien de replay")
        return StreamRequest(url, replay.name, mapOf("User-Agent" to MAG_USER_AGENT))
    }

    // ---------- Guide TV ----------

    override suspend fun epg(channel: Channel): List<Programme> {
        if (channel.type != ItemType.LIVE) return emptyList()
        val chId = channel.epgId ?: return emptyList()
        epgCache[chId]?.let { (time, list) -> if (nowMillis() - time < 10 * MINUTE_MS) return list }
        val js = try {
            api(listOf("type" to "itv", "action" to "get_short_epg", "ch_id" to chId, "size" to "8"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return emptyList()
        }
        val items = if (js is JsonObject && js["data"] != null) js["data"].asObjects() else js.asObjects()
        val list = items.mapNotNull { o ->
            val start = o.s("start_timestamp")?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            val stop = o.s("stop_timestamp")?.toLongOrNull()?.times(1000) ?: (start + HOUR_MS)
            Programme(start, stop, o.s("name") ?: return@mapNotNull null, o.s("descr"))
        }.sortedBy { it.start }
        epgCache[chId] = nowMillis() to list
        return list
    }
}

/** "ffmpeg http://hôte/flux" → "http://hôte/flux" */
internal fun extractStreamUrl(cmd: String): String? =
    cmd.trim().split(' ').lastOrNull { part ->
        val p = part.lowercase()
        p.startsWith("http://") || p.startsWith("https://") || p.startsWith("rtmp://") ||
            p.startsWith("rtsp://") || p.startsWith("udp://")
    }

/**
 * Adresses possibles du point d'entrée à partir de ce que l'utilisateur a saisi
 * (« http://hôte:port/c/ », « http://hôte/stalker_portal/c/ », ou directement « …/portal.php »).
 */
internal fun stalkerEndpoints(portal: String): List<String> {
    val raw = normalizeUrl(portal).substringBefore('?')
    if (raw.endsWith(".php")) return listOf(raw)
    val url = runCatching { Url(raw) }.getOrNull() ?: return listOf(raw)
    val origin = originOf(url)
    val path = url.encodedPath.trimEnd('/')
    val list = LinkedHashSet<String>()
    if (path.endsWith("/c")) {
        val prefix = path.removeSuffix("/c")
        list += "$origin$prefix/server/load.php"
        list += "$origin$prefix/portal.php"
    } else if (path.isNotEmpty()) {
        list += "$origin$path/server/load.php"
        list += "$origin$path/portal.php"
    }
    list += "$origin/stalker_portal/server/load.php"
    list += "$origin/portal.php"
    list += "$origin/server/load.php"
    return list.toList()
}
