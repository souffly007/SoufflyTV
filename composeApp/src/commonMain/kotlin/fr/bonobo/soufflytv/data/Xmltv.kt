package fr.bonobo.soufflytv.data

import fr.bonobo.soufflytv.model.Programme

const val HOUR_MS = 3_600_000L
const val MINUTE_MS = 60_000L

/**
 * Lecture en flux d'un guide XMLTV :
 *
 *   <channel id="tf1.fr"><display-name>TF1</display-name></channel>
 *   <programme start="20261118204500 +0100" stop="…" channel="tf1.fr"><title>…</title><desc>…</desc></programme>
 *
 * Les données arrivent par morceaux : on ne garde que les émissions des chaînes voulues
 * et dans la fenêtre de temps voulue, pour rester léger même avec un fichier de 200 Mo.
 */
class XmltvParser(
    /** tvg-id recherchés, en minuscules (null = tout garder). */
    private val wantedIds: Set<String>?,
    /** Noms de chaînes normalisés (voir [normalizeChannelName]) pour l'association par nom. */
    private val wantedNames: Set<String>,
    private val from: Long,
    private val to: Long,
) {
    private val buffer = StringBuilder()
    private val programmes = HashMap<String, MutableList<Programme>>()
    private val nameToId = HashMap<String, String>()
    private val idsFromNames = HashSet<String>()

    fun feed(chunk: String) {
        buffer.append(chunk)
        drain()
    }

    /** Termine la lecture et renvoie les émissions par identifiant XMLTV (minuscules), triées. */
    fun finish(): Map<String, List<Programme>> {
        drain()
        buffer.clear()
        return programmes.mapValues { (_, list) -> list.sortedBy { it.start }.distinctBy { it.start } }
    }

    /** Identifiant XMLTV d'une chaîne retrouvée par son nom. */
    fun idForName(normalizedName: String): String? = nameToId[normalizedName]

    private fun drain() {
        var pos = 0
        var keepFrom = -1
        while (true) {
            val p = buffer.indexOf("<programme", pos)
            val c = buffer.indexOf("<channel", pos)
            val start = when {
                p < 0 -> c
                c < 0 -> p
                else -> minOf(p, c)
            }
            if (start < 0) break
            val isProgramme = start == p
            val endTag = if (isProgramme) "</programme>" else "</channel>"
            val end = buffer.indexOf(endTag, start)
            if (end < 0) {
                keepFrom = start // élément incomplet : on attend la suite
                break
            }
            val element = buffer.substring(start, end + endTag.length)
            if (isProgramme) handleProgramme(element) else handleChannel(element)
            pos = end + endTag.length
        }
        if (keepFrom < 0) keepFrom = maxOf(pos, buffer.length - 16) // un début de balise peut être coupé
        if (keepFrom > 0) {
            val rest = buffer.substring(keepFrom)
            buffer.clear()
            buffer.append(rest)
        }
    }

    private fun handleChannel(element: String) {
        val open = element.substring(0, element.indexOf('>').let { if (it < 0) element.length else it + 1 })
        val id = ATTR_ID.find(open)?.groupValues?.get(1)?.trim()?.lowercase() ?: return
        DISPLAY_NAME.findAll(element).forEach { m ->
            val name = normalizeChannelName(xmlUnescape(m.groupValues[1]))
            if (name.isNotEmpty() && (wantedIds == null || name in wantedNames) && name !in nameToId) {
                nameToId[name] = id
                idsFromNames += id
            }
        }
    }

    private fun handleProgramme(element: String) {
        val open = element.substring(0, element.indexOf('>').let { if (it < 0) element.length else it + 1 })
        val channel = ATTR_CHANNEL.find(open)?.groupValues?.get(1)?.trim()?.lowercase() ?: return
        if (wantedIds != null && channel !in wantedIds && channel !in idsFromNames) return
        val start = ATTR_START.find(open)?.groupValues?.get(1)?.let(::parseXmltvTime) ?: return
        val stop = ATTR_STOP.find(open)?.groupValues?.get(1)?.let(::parseXmltvTime) ?: (start + HOUR_MS)
        if (stop < from || start > to) return
        val title = TITLE.find(element)?.groupValues?.get(1)?.let(::xmlUnescape)?.trim()
        if (title.isNullOrEmpty()) return
        val desc = DESC.find(element)?.groupValues?.get(1)?.let(::xmlUnescape)?.trim()?.takeIf { it.isNotEmpty() }
        programmes.getOrPut(channel) { ArrayList() } += Programme(start, stop, title, desc)
    }

    private companion object {
        val ATTR_ID = Regex("""\sid\s*=\s*["']([^"']*)["']""")
        val ATTR_CHANNEL = Regex("""\schannel\s*=\s*["']([^"']*)["']""")
        val ATTR_START = Regex("""\sstart\s*=\s*["']([^"']*)["']""")
        val ATTR_STOP = Regex("""\sstop\s*=\s*["']([^"']*)["']""")
        val DISPLAY_NAME = Regex("""<display-name[^>]*>([\s\S]*?)</display-name>""")
        val TITLE = Regex("""<title[^>]*>([\s\S]*?)</title>""")
        val DESC = Regex("""<desc[^>]*>([\s\S]*?)</desc>""")
    }
}

/** Nom de chaîne simplifié pour comparer « TF1 HD » et « tf1 » : minuscules, lettres et chiffres. */
fun normalizeChannelName(name: String): String {
    val base = name.lowercase().filter { it.isLetterOrDigit() }
    for (suffix in listOf("uhd", "fhd", "hd", "sd", "4k")) {
        if (base.length > suffix.length + 1 && base.endsWith(suffix)) return base.dropLast(suffix.length)
    }
    return base
}

/** "20261118204500 +0100" → millisecondes UTC. */
fun parseXmltvTime(value: String): Long? {
    val t = value.trim()
    if (t.length < 12) return null
    val year = t.substring(0, 4).toIntOrNull() ?: return null
    val month = t.substring(4, 6).toIntOrNull() ?: return null
    val day = t.substring(6, 8).toIntOrNull() ?: return null
    val hour = t.substring(8, 10).toIntOrNull() ?: return null
    val minute = t.substring(10, 12).toIntOrNull() ?: return null
    val second = if (t.length >= 14) t.substring(12, 14).toIntOrNull() ?: 0 else 0
    if (month !in 1..12 || day !in 1..31) return null
    var epochSeconds = daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second
    val zone = t.substring(minOf(14, t.length)).trim()
    if (zone.length >= 5 && (zone[0] == '+' || zone[0] == '-')) {
        val h = zone.substring(1, 3).toIntOrNull() ?: 0
        val m = zone.substring(3, 5).toIntOrNull() ?: 0
        val offset = h * 3_600L + m * 60L
        epochSeconds -= if (zone[0] == '+') offset else -offset
    }
    return epochSeconds * 1000L
}

/** Nombre de jours depuis le 1er janvier 1970 (algorithme de Howard Hinnant). */
internal fun daysFromCivil(y0: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}

internal fun xmlUnescape(raw: String): String {
    var s = raw.trim()
    if (s.startsWith("<![CDATA[") && s.endsWith("]]>")) return s.substring(9, s.length - 3)
    if ('&' !in s) return s
    s = NUMERIC_ENTITY.replace(s) { m ->
        val code = m.groupValues[1]
        val value = if (code.startsWith("x") || code.startsWith("X")) code.substring(1).toIntOrNull(16) else code.toIntOrNull()
        value?.let { codePointToString(it) } ?: m.value
    }
    return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}

private val NUMERIC_ENTITY = Regex("&#([xX]?[0-9a-fA-F]+);")

private fun codePointToString(cp: Int): String = when {
    cp < 0 -> ""
    cp < 0x10000 -> cp.toChar().toString()
    else -> {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
}

/** « 1:02:03 » ou « 12:34 » */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    fun two(v: Long) = if (v < 10) "0$v" else "$v"
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

// ---------- Même chaîne, plusieurs versions (SD / HD / FHD / 4K…) ----------

internal val COUNTRY_PREFIX = Regex("""^[\s\[(|#*■•►▶★]*[A-Za-z]{2,3}\s*[:|\])\-–■•►▶★]+\s*""")
internal val BRACKETS = Regex("""[(\[{][^)\]}]*[)\]}]""")
internal val QUALITY_WORDS = setOf(
    "sd", "hd", "fhd", "uhd", "qhd", "hq", "lq", "4k", "8k", "hdr", "hdr10", "dolby", "vision",
    "hevc", "h264", "h265", "x264", "x265", "50fps", "60fps", "fps", "raw", "backup", "vip",
    "multi", "multisub", "vf", "vo", "vostfr", "low", "high", "plus", "full", "direct", "live",
)

/**
 * Nom « de base » d'une chaîne : « |FR| TF1 4K HDR UHD (Résolution Exclus) » → « tf1 ».
 * Sert à partager le guide entre les versions d'une même chaîne.
 */
fun epgBaseName(name: String): String {
    var s = name.replace(COUNTRY_PREFIX, "")
    s = s.replace(BRACKETS, " ")
    val words = s.lowercase()
        .split(Regex("[^\\p{L}\\p{N}+]+"))
        .filter { it.isNotEmpty() && it !in QUALITY_WORDS }
    return words.joinToString("").filter { it.isLetterOrDigit() }
}

/**
 * Complète le guide : une chaîne sans programme reprend celui d'une autre version
 * de la même chaîne (même nom de base) qui en a un.
 */
fun shareEpgBetweenVariants(
    channels: List<fr.bonobo.soufflytv.model.Channel>,
    byChannel: MutableMap<String, List<Programme>>,
) {
    val byBase = HashMap<String, List<Programme>>()
    for (ch in channels) {
        val list = byChannel[ch.id] ?: continue
        val base = epgBaseName(ch.name)
        if (base.length >= 2 && base !in byBase) byBase[base] = list
    }
    for (ch in channels) {
        if (ch.id in byChannel) continue
        val base = epgBaseName(ch.name)
        byBase[base]?.let { byChannel[ch.id] = it }
    }
}
