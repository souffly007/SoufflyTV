package fr.bonobo.soufflytv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

/** Contenu de la bibliothèque (fichier library.json, et sauvegarde complète). */
@Serializable
data class LibraryFile(
    val favorites: List<LibraryEntry> = emptyList(),
    val recents: List<LibraryEntry> = emptyList(),
    /** Position de reprise des films/épisodes : "sourceId|channelId" → ms. */
    val positions: Map<String, Long> = emptyMap(),
    /** Catégories masquées : "sourceId|RUBRIQUE|categoryId". */
    val hidden: List<String> = emptyList(),
    /** Durée des films/épisodes commencés (barre de progression) : "sourceId|channelId" → ms. */
    val durations: Map<String, Long> = emptyMap(),
    /** Films/épisodes vus jusqu'au bout : "sourceId|channelId". */
    val watched: List<String> = emptyList(),
    /** Dernière fois qu'un film/épisode a été regardé (reprise d'une série) : "sourceId|channelId" → date. */
    val lastPlayed: Map<String, Long> = emptyMap(),
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
    private val durations = HashMap<String, Long>()
    private val lastPlayed = HashMap<String, Long>()

    /** Films/épisodes vus jusqu'au bout : "sourceId|channelId". */
    var watched by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Change à chaque enregistrement de progression : les écrans qui la lisent se mettent à jour. */
    private var progressVersion by mutableIntStateOf(0)
    private var saveJob: Job? = null

    suspend fun load() {
        val text = readAppFile(FILE) ?: return
        try {
            val data = json.decodeFromString(LibraryFile.serializer(), text)
            favorites = data.favorites
            recents = data.recents
            positions.putAll(data.positions)
            hidden = data.hidden.toSet()
            durations.putAll(data.durations)
            watched = data.watched.toSet()
            lastPlayed.putAll(data.lastPlayed)
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

    fun position(key: String): Long? {
        progressVersion // lecture d'un état : recomposition quand la progression change
        return positions[key]
    }

    fun setPosition(key: String, ms: Long) {
        if (ms <= 0) positions.remove(key) else positions[key] = ms
        progressVersion++
        scheduleSave()
    }

    /** Part déjà regardée (0 à 1) d'un film ou d'un épisode commencé, ou null. */
    fun progress(key: String): Float? {
        progressVersion
        val position = positions[key] ?: return null
        val duration = durations[key]?.takeIf { it > 0 } ?: return null
        return (position.toFloat() / duration).coerceIn(0f, 1f)
    }

    fun isWatched(key: String): Boolean = key in watched

    /** Date à laquelle ce film ou cet épisode a été regardé pour la dernière fois, ou null. */
    fun lastPlayed(key: String): Long? {
        progressVersion
        return lastPlayed[key]
    }

    /**
     * Enregistre où l'on en est. Dans la dernière minute (ou les 5 derniers %),
     * le film ou l'épisode est marqué « vu » et sa position oubliée.
     */
    fun saveProgress(key: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val finished = positionMs > durationMs - 60_000 || positionMs > durationMs * 0.95
        if (finished) {
            positions.remove(key)
            watched = watched + key
        } else if (positionMs > 0) {
            positions[key] = positionMs
        }
        durations[key] = durationMs
        lastPlayed[key] = nowMillis()
        progressVersion++
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
        durations.clear()
        lastPlayed.clear()
        watched = emptySet()
        progressVersion++
        scheduleSave()
    }

    fun removeSource(sourceId: String) {
        favorites = favorites.filterNot { it.sourceId == sourceId }
        recents = recents.filterNot { it.sourceId == sourceId }
        positions.keys.removeAll { it.startsWith("$sourceId|") }
        durations.keys.removeAll { it.startsWith("$sourceId|") }
        lastPlayed.keys.removeAll { it.startsWith("$sourceId|") }
        watched = watched.filterNot { it.startsWith("$sourceId|") }.toSet()
        hidden = hidden.filterNot { it.startsWith("$sourceId|") }.toSet()
        scheduleSave()
    }

    /** Copie de toute la bibliothèque (sauvegarde complète). */
    fun snapshot(): LibraryFile = LibraryFile(
        favorites = favorites,
        recents = recents,
        positions = positions.toMap(),
        hidden = hidden.toList(),
        durations = durations.toMap(),
        watched = watched.toList(),
        lastPlayed = lastPlayed.toMap(),
    )

    /**
     * Restauration d'une sauvegarde complète : on ajoute sans rien perdre (favoris, historique,
     * catégories masquées, films et épisodes vus) ; pour une reprise, la plus récente l'emporte.
     */
    fun merge(other: LibraryFile) {
        favorites = (favorites + other.favorites).distinctBy { it.sourceId to it.channel.id }
        recents = (recents + other.recents).sortedByDescending { it.at }
            .distinctBy { it.sourceId to it.channel.id }
            .take(MAX_RECENTS)
        other.lastPlayed.forEach { (key, at) ->
            if (at >= (lastPlayed[key] ?: 0L)) {
                lastPlayed[key] = at
                val position = other.positions[key]
                if (position != null) positions[key] = position else positions.remove(key)
            }
        }
        other.positions.forEach { (key, position) -> if (key !in other.lastPlayed) positions.getOrPut(key) { position } }
        other.durations.forEach { (key, duration) -> durations.getOrPut(key) { duration } }
        watched = watched + other.watched
        hidden = hidden + other.hidden
        progressVersion++
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            writeAppFile(FILE, json.encodeToString(LibraryFile.serializer(), snapshot()))
        }
    }

    private companion object {
        const val FILE = "library.json"
        const val MAX_RECENTS = 60
    }
}
