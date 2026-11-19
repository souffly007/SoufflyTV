package fr.bonobo.soufflytv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.Section
import fr.bonobo.soufflytv.platform.nowMillis
import fr.bonobo.soufflytv.platform.readAppFile
import fr.bonobo.soufflytv.platform.writeAppFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LibraryEntry(
    val sourceId: String,
    val channel: Channel,
    val at: Long,
)

@Serializable
internal data class LibraryFile(
    val favorites: List<LibraryEntry> = emptyList(),
    val recents: List<LibraryEntry> = emptyList(),
    /** Position de reprise des films/épisodes : "sourceId|channelId" → ms. */
    val positions: Map<String, Long> = emptyMap(),
    /** Catégories masquées : "sourceId|RUBRIQUE|categoryId". */
    val hidden: List<String> = emptyList(),
)

/** Favoris, dernières chaînes regardées, positions de reprise et catégories masquées (library.json). */
class LibraryStore(private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true }

    var favorites by mutableStateOf<List<LibraryEntry>>(emptyList())
        private set
    var recents by mutableStateOf<List<LibraryEntry>>(emptyList())
        private set

    /** Catégories masquées : "sourceId|RUBRIQUE|categoryId". */
    var hidden by mutableStateOf<Set<String>>(emptySet())
        private set

    private val positions = HashMap<String, Long>()
    private var saveJob: Job? = null

    suspend fun load() {
        val text = readAppFile(FILE) ?: return
        try {
            val data = json.decodeFromString(LibraryFile.serializer(), text)
            favorites = data.favorites
            recents = data.recents
            positions.putAll(data.positions)
            hidden = data.hidden.toSet()
        } catch (e: Exception) {
            // bibliothèque illisible : on repart de zéro sans bloquer l'app
        }
    }

    fun isFavorite(sourceId: String, channelId: String): Boolean =
        favorites.any { it.sourceId == sourceId && it.channel.id == channelId }

    fun toggleFavorite(sourceId: String, channel: Channel) {
        favorites = if (isFavorite(sourceId, channel.id)) {
            favorites.filterNot { it.sourceId == sourceId && it.channel.id == channel.id }
        } else {
            listOf(LibraryEntry(sourceId, channel, nowMillis())) + favorites
        }
        scheduleSave()
    }

    fun addRecent(sourceId: String, channel: Channel) {
        recents = (listOf(LibraryEntry(sourceId, channel, nowMillis())) +
            recents.filterNot { it.sourceId == sourceId && it.channel.id == channel.id })
            .take(MAX_RECENTS)
        scheduleSave()
    }

    fun position(key: String): Long? = positions[key]

    fun setPosition(key: String, ms: Long) {
        if (ms <= 0) positions.remove(key) else positions[key] = ms
        scheduleSave()
    }

    private fun hiddenKey(sourceId: String, section: Section, categoryId: String) = "$sourceId|${section.name}|$categoryId"

    fun isHidden(sourceId: String, section: Section, categoryId: String): Boolean =
        hiddenKey(sourceId, section, categoryId) in hidden

    /** Identifiants des catégories masquées d'une rubrique. */
    fun hiddenIds(sourceId: String, section: Section): Set<String> {
        val prefix = "$sourceId|${section.name}|"
        return hidden.asSequence().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
    }

    /** Masque ou réaffiche plusieurs catégories d'un coup. */
    fun setHidden(sourceId: String, section: Section, categoryIds: Collection<String>, hide: Boolean) {
        val keys = categoryIds.map { hiddenKey(sourceId, section, it) }
        hidden = if (hide) hidden + keys else hidden - keys.toSet()
        scheduleSave()
    }

    fun clearHistory() {
        recents = emptyList()
        positions.clear()
        scheduleSave()
    }

    fun removeSource(sourceId: String) {
        favorites = favorites.filterNot { it.sourceId == sourceId }
        recents = recents.filterNot { it.sourceId == sourceId }
        positions.keys.removeAll { it.startsWith("$sourceId|") }
        hidden = hidden.filterNot { it.startsWith("$sourceId|") }.toSet()
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val data = LibraryFile(favorites, recents, positions.toMap(), hidden.toList())
            writeAppFile(FILE, json.encodeToString(LibraryFile.serializer(), data))
        }
    }

    private companion object {
        const val FILE = "library.json"
        const val MAX_RECENTS = 60
    }
}
