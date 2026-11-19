package fr.bonobo.soufflytv.data

import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.ItemType

class M3uPlaylist(
    val channels: List<Channel>,
    /** Guides TV annoncés dans l'en-tête (#EXTM3U url-tvg="…"). */
    val epgUrls: List<String>,
)

/**
 * Lecture d'une playlist M3U / M3U8 « étendue » :
 *
 *   #EXTM3U url-tvg="http://…/guide.xml.gz"
 *   #EXTINF:-1 tvg-id="tf1.fr" tvg-logo="http://…/tf1.png" group-title="France",TF1 HD
 *   #EXTVLCOPT:http-user-agent=Mozilla/5.0
 *   http://serveur/flux/1.ts
 */
object M3uParser {

    const val NO_GROUP = "Sans catégorie"

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(text: String): M3uPlaylist {
        val result = ArrayList<Channel>()
        val epgUrls = LinkedHashSet<String>()
        var title: String? = null
        var attrs: Map<String, String> = emptyMap()
        var extGroup: String? = null
        var headers = HashMap<String, String>()

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    attrRegex.findAll(line).forEach { m ->
                        val key = m.groupValues[1].lowercase()
                        if (key == "url-tvg" || key == "x-tvg-url" || key == "tvg-url") {
                            m.groupValues[2].split(',').map { it.trim() }.filter { it.startsWith("http", ignoreCase = true) }
                                .forEach { epgUrls += it }
                        }
                    }
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val comma = titleComma(line)
                    val header = if (comma >= 0) line.substring(0, comma) else line
                    title = if (comma >= 0) line.substring(comma + 1).trim() else null
                    attrs = attrRegex.findAll(header)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
                    attrs["user-agent"]?.takeIf { it.isNotEmpty() }?.let { headers["User-Agent"] = it }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    extGroup = line.substringAfter(':').trim()

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val key = option.substringBefore('=').trim().lowercase()
                    val value = option.substringAfter('=', "").trim()
                    if (value.isNotEmpty()) when (key) {
                        "http-user-agent" -> headers["User-Agent"] = value
                        "http-referrer", "http-referer" -> headers["Referer"] = value
                    }
                }

                line.startsWith("#") -> Unit // autres directives ignorées

                else -> {
                    val name = title?.takeIf { it.isNotBlank() }
                        ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                        ?: line.substringAfterLast('/')
                    val group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                        ?: extGroup?.takeIf { it.isNotBlank() }
                        ?: NO_GROUP
                    result += Channel(
                        // Identifiant stable d'un téléchargement à l'autre (favoris, historique)
                        id = "m3u:" + "$line|$name".hashCode().toUInt().toString(16),
                        name = name,
                        logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http", ignoreCase = true) },
                        categoryId = group,
                        stream = line,
                        number = attrs["tvg-chno"]?.toIntOrNull(),
                        headers = headers,
                        type = guessType(line),
                        epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                    )
                    title = null
                    attrs = emptyMap()
                    extGroup = null
                    headers = HashMap()
                }
            }
        }
        return M3uPlaylist(result, epgUrls.toList())
    }

    /** Les playlists Xtream exportées en M3U mélangent direct, films et séries. */
    private fun guessType(url: String): ItemType {
        val u = url.lowercase()
        return when {
            "/movie/" in u -> ItemType.MOVIE
            "/series/" in u -> ItemType.EPISODE
            u.substringBefore('?').let { it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".avi") } -> ItemType.MOVIE
            else -> ItemType.LIVE
        }
    }

    /** Virgule qui sépare les attributs du titre (en ignorant celles entre guillemets). */
    private fun titleComma(line: String): Int {
        var inQuotes = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }
}
