package fr.bonobo.soufflytv.data

import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.Programme
import fr.bonobo.soufflytv.platform.formatZoned

/*
 * Replay (catch-up) : relire une émission passée, quand le fournisseur garde des archives.
 *  - Xtream : chaînes marquées tv_archive, lien …/timeshift/utilisateur/mot de passe/durée/début/id.ts
 *  - M3U : attributs catchup / catchup-days / catchup-source (mêmes conventions que Kodi IPTV Simple)
 *  - Stalker : chaînes avec archives, lien demandé au portail
 */

/** Formatage de date utilisé pour les liens (remplaçable dans les tests). */
internal typealias ZonedFormatter = (epochMillis: Long, zoneId: String?, pattern: String) -> String

private const val DAY_MS = 24 * 60 * 60_000L

/** Émission qu'on peut relire : déjà commencée, et encore dans la période d'archives de la chaîne. */
fun isReplayable(channel: Channel, programme: Programme, now: Long): Boolean =
    channel.archiveDays > 0 &&
        programme.start < now - 60_000 &&
        programme.start >= now - channel.archiveDays * DAY_MS

/** Copie de la chaîne qui représente le replay d'une émission (lue comme un film par le lecteur). */
fun Channel.replayOf(programme: Programme): Channel = copy(
    id = "$id@${programme.start / 1000}",
    name = programme.title,
    plot = programme.description,
    replayStart = programme.start,
    replayStop = programme.stop,
    replayId = programme.id,
)

/** Durée de l'émission en minutes (arrondie au-dessus), au moins 1. */
internal fun replayMinutes(start: Long, stop: Long): Long = ((stop - start + 59_999) / 60_000).coerceAtLeast(1)

/**
 * Lien Xtream : …/timeshift/{utilisateur}/{mot de passe}/{durée en min}/{AAAA-MM-JJ:HH-MM}/{id}.{ext}
 * L'heure de début est celle du fuseau du serveur.
 */
internal fun xtreamTimeshiftUrl(
    base: String,
    user: String,
    pass: String,
    streamId: String,
    start: Long,
    stop: Long,
    serverZone: String?,
    ext: String,
    format: ZonedFormatter = ::formatZoned,
): String {
    val begin = format(start, serverZone, "yyyy-MM-dd:HH-mm")
    return "$base/timeshift/$user/$pass/${replayMinutes(start, stop)}/$begin/$streamId.$ext"
}

/** Lien de replay d'une chaîne M3U d'après son type « catchup » ; null si on ne sait pas le construire. */
internal fun m3uCatchupUrl(channel: Channel, start: Long, stop: Long, now: Long, format: ZonedFormatter = ::formatZoned): String? {
    val stream = channel.stream
    val source = channel.catchupSource?.trim().orEmpty()
    val type = channel.catchup?.trim()?.lowercase().orEmpty()
    fun fill(template: String) = fillCatchupTemplate(template, start, stop, now, format)
    return when (type) {
        "append" -> if (source.isEmpty()) null else stream + fill(source)
        "shift", "timeshift" -> {
            val sep = if ('?' in stream) "&" else "?"
            "$stream${sep}utc=${start / 1000}&lutc=${now / 1000}"
        }
        "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonicUrl(stream, start, stop, type)
        "xc" -> xcUrlFromLive(stream, start, stop, format)
        // « default » ou absent : le lien complet est donné par catchup-source
        else -> when {
            source.startsWith("http", ignoreCase = true) || source.startsWith("rtmp", ignoreCase = true) -> fill(source)
            source.isNotEmpty() -> stream + fill(source) // morceau à ajouter au lien de la chaîne
            type.isEmpty() && channel.archiveDays > 0 -> xcUrlFromLive(stream, start, stop, format)
            else -> null
        }
    }
}

/**
 * Remplace les repères d'un modèle de lien de replay :
 * {utc} / ${start} début (s), {utcend} / ${end} fin (s), {lutc} / ${now} / ${timestamp} maintenant (s),
 * {duration} durée (s), {duration:60} durée divisée par 60, {offset} / ${offset} écart début → maintenant (s),
 * {Y} {m} {d} {H} {M} {S} date et heure du début (UTC), {utc:Y-m-d H:M:S} / {utcend:…} au format demandé.
 */
internal fun fillCatchupTemplate(template: String, start: Long, stop: Long, now: Long, format: ZonedFormatter = ::formatZoned): String {
    val s = start / 1000
    val e = stop / 1000
    val n = now / 1000
    val duration = (e - s).coerceAtLeast(0)
    val offset = (n - s).coerceAtLeast(0)
    var out = template
    // formats personnalisés {utc:…} {utcend:…} {start:…} {end:…}
    out = Regex("""\$?\{(utc|utcend|start|end|lutc|now):([^}]+)\}""").replace(out) { m ->
        val time = when (m.groupValues[1]) {
            "utc", "start" -> start
            "utcend", "end" -> stop
            else -> now
        }
        format(time, "UTC", kodiToJavaPattern(m.groupValues[2]))
    }
    // durées / écarts divisés : {duration:60}, {offset:60}
    out = Regex("""\$?\{(duration|offset):(\d+)\}""").replace(out) { m ->
        val div = m.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: 1
        ((if (m.groupValues[1] == "duration") duration else offset) / div).toString()
    }
    val simple = mapOf(
        "utc" to s, "start" to s, "utcend" to e, "end" to e, "lutc" to n, "now" to n, "timestamp" to n,
        "duration" to duration, "offset" to offset,
    )
    out = Regex("""\$?\{([a-z]+)\}""").replace(out) { m ->
        simple[m.groupValues[1]]?.toString() ?: m.value
    }
    // {Y} {m} {d} {H} {M} {S} : début en UTC
    val parts = mapOf("Y" to "yyyy", "m" to "MM", "d" to "dd", "H" to "HH", "M" to "mm", "S" to "ss")
    out = Regex("""\{([YmdHMS])\}""").replace(out) { m -> format(start, "UTC", parts.getValue(m.groupValues[1])) }
    return out
}

/** « Y-m-d H:M:S » (style Kodi / strftime sans %) → « yyyy-MM-dd HH:mm:ss » (java.time). */
internal fun kodiToJavaPattern(kodi: String): String {
    val map = mapOf('Y' to "yyyy", 'm' to "MM", 'd' to "dd", 'H' to "HH", 'M' to "mm", 'S' to "ss")
    val out = StringBuilder()
    val literal = StringBuilder()
    fun flush() {
        if (literal.isNotEmpty()) {
            out.append('\'').append(literal.toString().replace("'", "''")).append('\'')
            literal.clear()
        }
    }
    for (c in kodi.replace("%", "")) {
        val p = map[c]
        if (p != null) {
            flush()
            out.append(p)
        } else {
            literal.append(c)
        }
    }
    flush()
    return out.toString()
}

/**
 * Flussonic : …/chaine/index.m3u8 → …/chaine/index-{début}-{durée}.m3u8 ;
 * …/chaine/mpegts (ou .ts) → …/chaine/timeshift_abs-{début}.ts
 */
internal fun flussonicUrl(stream: String, start: Long, stop: Long, type: String): String? {
    val query = stream.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
    val path = stream.substringBefore('?')
    val dir = path.substringBeforeLast('/', "")
    if (dir.isEmpty()) return null
    val last = path.substringAfterLast('/')
    val s = start / 1000
    val dur = ((stop - start) / 1000).coerceAtLeast(60)
    val hls = type == "flussonic-hls" || (type != "flussonic-ts" && last.endsWith(".m3u8", ignoreCase = true))
    return if (hls) {
        val name = last.removeSuffix(".m3u8").removeSuffix(".M3U8").ifEmpty { "index" }
        "$dir/$name-$s-$dur.m3u8$query"
    } else {
        "$dir/timeshift_abs-$s.ts$query"
    }
}

/**
 * Lien de direct façon Xtream (…/live/u/p/id.ts ou …/u/p/id) → lien de replay …/timeshift/u/p/durée/début/id.ts
 * (fuseau de l'appareil, faute de connaître celui du serveur).
 */
internal fun xcUrlFromLive(stream: String, start: Long, stop: Long, format: ZonedFormatter = ::formatZoned): String? {
    val match = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.(\w+))?$""").find(stream.substringBefore('?'))
        ?: return null
    val (host, user, pass, id, ext) = match.destructured
    return xtreamTimeshiftUrl(host, user, pass, id, start, stop, null, ext.ifEmpty { "ts" }, format)
}
