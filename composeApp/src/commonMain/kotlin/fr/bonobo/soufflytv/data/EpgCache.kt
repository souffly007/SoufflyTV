package fr.bonobo.soufflytv.data

import fr.bonobo.soufflytv.model.Programme
import fr.bonobo.soufflytv.platform.deleteAppFile
import fr.bonobo.soufflytv.platform.nowMillis
import fr.bonobo.soufflytv.platform.readAppFile
import fr.bonobo.soufflytv.platform.writeAppFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal class CachedProgramme(val s: Long, val e: Long, val t: String)

@Serializable
internal class EpgCacheFile(val savedAt: Long, val channels: Map<String, List<CachedProgramme>>)

/**
 * Guide TV enregistré sur l'appareil (un fichier par source) :
 * il s'affiche dès l'ouverture de l'app, puis il est rafraîchi en arrière-plan toutes les 6 h.
 */
object EpgCache {

    /** Au-delà, on retélécharge le guide (en gardant l'ancien affiché pendant ce temps). */
    const val REFRESH_MS = 6 * HOUR_MS

    /** Au-delà, le guide enregistré ne couvre plus assez l'avenir : on l'ignore. */
    private const val MAX_AGE_MS = 30 * HOUR_MS

    private val json = Json { ignoreUnknownKeys = true }

    private fun fileName(sourceId: String) = "epg-$sourceId.json"

    /** Guide enregistré et sa date, ou null s'il n'existe pas ou est trop ancien. */
    suspend fun load(sourceId: String): Pair<Long, Map<String, List<Programme>>>? {
        val text = readAppFile(fileName(sourceId)) ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val file = json.decodeFromString(EpgCacheFile.serializer(), text)
                if (nowMillis() - file.savedAt > MAX_AGE_MS) return@withContext null
                file.savedAt to file.channels.mapValues { (_, list) -> list.map { Programme(it.s, it.e, it.t) } }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Enregistre le guide (titres seulement, émissions terminées depuis plus d'une heure retirées). */
    suspend fun save(sourceId: String, savedAt: Long, byChannel: Map<String, List<Programme>>) {
        val text = withContext(Dispatchers.Default) {
            val limit = nowMillis() - HOUR_MS
            val compact = byChannel.mapValues { (_, list) ->
                list.filter { it.stop >= limit }.map { CachedProgramme(it.start, it.stop, it.title) }
            }.filterValues { it.isNotEmpty() }
            json.encodeToString(EpgCacheFile.serializer(), EpgCacheFile(savedAt, compact))
        }
        writeAppFile(fileName(sourceId), text)
    }

    suspend fun delete(sourceId: String) = deleteAppFile(fileName(sourceId))
}
