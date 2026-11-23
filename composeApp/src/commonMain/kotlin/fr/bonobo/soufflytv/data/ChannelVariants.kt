package fr.bonobo.soufflytv.data

import fr.bonobo.soufflytv.model.Channel

/*
 * Une même chaîne en plusieurs versions (« TF1 SD », « TF1 HD », « FR | TF1 FHD », « TF1 4K »…) :
 * le guide TV les regroupe sur une seule ligne et propose la qualité au lancement.
 */

/** Une version d'une chaîne : la chaîne elle-même et sa qualité (« HD », « UHD », « FHD HEVC »…). */
data class ChannelVariant(val channel: Channel, val quality: String, val rank: Int)

/** Une ligne du guide : nom affiché (« TF1 ») et ses versions, de la plus légère à la meilleure. */
data class ChannelGroup(val key: String, val name: String, val variants: List<ChannelVariant>) {
    val main: Channel get() = variants.first().channel
}

/** Qualité lue dans le nom : libellé et rang (SD 1, sans mention 2, HD 3, FHD 4, UHD 5, 8K 6). */
fun qualityOf(name: String): Pair<String, Int> {
    val words = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
    val (label, rank) = when {
        "8k" in words -> "8K" to 6
        words.any { it == "4k" || it == "uhd" || it == "2160p" || it == "2160" } -> "UHD" to 5
        words.any { it == "fhd" || it == "1080p" || it == "1080" || it == "1080i" } -> "FHD" to 4
        words.any { it == "hd" || it == "720p" || it == "720" || it == "hq" } -> "HD" to 3
        words.any { it == "sd" || it == "576p" || it == "480p" || it == "576" || it == "lq" } -> "SD" to 1
        else -> "Standard" to 2
    }
    val extras = buildList {
        if (words.any { it == "hevc" || it == "h265" || it == "x265" }) add("HEVC")
        if (words.any { it == "50fps" || it == "60fps" }) add("50 fps")
        if ("hdr" in words || "hdr10" in words) add("HDR")
        if ("backup" in words) add("secours")
    }
    return (if (extras.isEmpty()) label else "$label ${extras.joinToString(" ")}") to rank
}

/** Nom affiché d'un groupe : « |FR| TF1 FHD (backup) » → « TF1 ». */
fun variantDisplayName(name: String): String {
    var s = name.replace(COUNTRY_PREFIX, "")
    s = s.replace(BRACKETS, " ")
    val kept = s.split(Regex("\\s+")).filter { word ->
        val w = word.lowercase().filter { it.isLetterOrDigit() || it == '+' }
        w.isNotEmpty() && w !in QUALITY_WORDS && w !in RESOLUTION_WORDS
    }
    return kept.joinToString(" ").trim(' ', '-', '|', ':', '–').ifEmpty { name.trim() }
}

private val RESOLUTION_WORDS = setOf("2160p", "2160", "1080p", "1080i", "1080", "720p", "720", "576p", "576", "480p")

/**
 * Regroupe les versions d'une même chaîne (même nom de base que pour le partage du guide),
 * dans l'ordre de la liste. Les versions sont triées de la plus légère à la meilleure ;
 * deux versions de même qualité sont numérotées (« HD », « HD 2 »).
 */
fun groupVariants(channels: List<Channel>): List<ChannelGroup> {
    val order = LinkedHashMap<String, MutableList<Channel>>()
    for (ch in channels) {
        // « 1080p », « 720p »… ne comptent pas dans le nom (sinon « RMC Sport 1 1080p » ≠ « RMC Sport 1 4K »)
        val base = epgBaseName(ch.name.split(Regex("\\s+")).filterNot { it.lowercase() in RESOLUTION_WORDS }.joinToString(" "))
        val key = if (base.length >= 2) base else "id:${ch.id}"
        order.getOrPut(key) { mutableListOf() }.add(ch)
    }
    return order.map { (key, list) ->
        val unique = list.distinctBy { it.id }
        val variants = unique.map { ch -> qualityOf(ch.name).let { (q, r) -> ChannelVariant(ch, q, r) } }
            .sortedWith(compareBy<ChannelVariant> { it.rank }.thenBy { it.quality })
        val seen = HashMap<String, Int>()
        val labelled = variants.map { v ->
            val n = (seen[v.quality] ?: 0) + 1
            seen[v.quality] = n
            if (n == 1) v else v.copy(quality = "${v.quality} $n")
        }
        ChannelGroup(key, variantDisplayName(unique.first().name), labelled)
    }
}

/** Version à lancer pour une qualité donnée (même libellé, sinon même rang, sinon la première). */
fun ChannelGroup.variantFor(quality: String?): ChannelVariant {
    if (quality.isNullOrEmpty()) return variants.first()
    variants.firstOrNull { it.quality == quality }?.let { return it }
    val rank = qualityOf(quality).second
    return variants.firstOrNull { it.rank == rank } ?: variants.first()
}
