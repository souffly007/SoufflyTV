package fr.bonobo.soufflytv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import fr.bonobo.soufflytv.model.StreamRequest

sealed interface PlayerStatus {
    data object Loading : PlayerStatus
    data object Playing : PlayerStatus
    data object Ended : PlayerStatus
    data class Error(val message: String) : PlayerStatus
}

enum class AspectMode(val label: String) {
    FIT("Original"),
    WIDE("16:9"),
    ZOOM("Zoom");

    fun next(): AspectMode = entries[(ordinal + 1) % entries.size]
}

enum class BufferMode(val label: String) {
    LOW("Faible"),
    NORMAL("Normal"),
    HIGH("Élevé"),
}

data class PlayerOptions(
    val live: Boolean,
    val buffer: BufferMode,
)

data class TrackOption(
    val id: String,
    val label: String,
    val selected: Boolean,
    /** Code langue de la piste (« fra », « qad »…), quand le flux l'indique. */
    val language: String? = null,
    /** Piste d'audiodescription : une voix décrit l'image (pour les personnes malvoyantes). */
    val audioDescription: Boolean = false,
)

private val AUDIO_DESCRIPTION_WORDS = listOf(
    "audiodescription", "audio description", "audio-description", "audiodesc",
    "malvoyant", "visually impaired", "described video", "video description", "[qad]",
)
private val AUDIO_DESCRIPTION_TAG = Regex("""(^|[\s\[(\-_/|·:])(ad|qad)($|[\s\])\-_/|·:])""")

/**
 * Reconnaît une piste d'audiodescription à son code langue (« qad », utilisé par les chaînes françaises)
 * ou à son nom (« Audiodescription », « Français AD », « [qad] »…).
 */
fun isAudioDescription(label: String?, language: String?): Boolean {
    if (language?.trim()?.lowercase() == "qad") return true
    val text = label?.lowercase() ?: return false
    return AUDIO_DESCRIPTION_WORDS.any { it in text } || AUDIO_DESCRIPTION_TAG.containsMatchIn(text)
}

/**
 * Piste audio à choisir selon le réglage d'audiodescription, ou null s'il n'y a rien à changer
 * (la piste en cours convient déjà, ou il n'existe pas d'autre piste du type voulu).
 */
fun pickAudioTrack(tracks: List<TrackOption>, wantAudioDescription: Boolean): TrackOption? {
    val current = tracks.firstOrNull { it.selected } ?: return null
    if (current.audioDescription == wantAudioDescription) return null
    val candidates = tracks.filter { it.audioDescription == wantAudioDescription }
    if (candidates.isEmpty()) return null
    // même langue que la piste en cours si possible (Français → Français AD, et inversement)
    return candidates.firstOrNull { current.language != null && it.language == current.language } ?: candidates.first()
}

/** Ce que chaque lecteur natif sait faire. */
interface PlayerImpl {
    fun togglePause()
    fun seekTo(ms: Long)
    fun selectAudio(id: String)
    /** null = sous-titres désactivés. */
    fun selectText(id: String?)
}

/** Pont entre l'interface commune et le lecteur natif (Media3 ou libVLC). */
class PlayerController {
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var audioTracks by mutableStateOf<List<TrackOption>>(emptyList())
    var textTracks by mutableStateOf<List<TrackOption>>(emptyList())
    var aspect by mutableStateOf(AspectMode.FIT)

    private var impl: PlayerImpl? = null

    fun attach(player: PlayerImpl) {
        impl = player
    }

    /** Les dernières valeurs (position, durée) sont gardées pour la sauvegarde de reprise. */
    fun detach() {
        impl = null
        isPlaying = false
    }

    fun togglePause() {
        impl?.togglePause()
    }

    fun seekTo(ms: Long) {
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val target = ms.coerceIn(0L, max)
        impl?.seekTo(target)
        positionMs = target
    }

    fun seekBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

    fun selectAudio(id: String) {
        impl?.selectAudio(id)
    }

    fun selectText(id: String?) {
        impl?.selectText(id)
    }
}

/**
 * Lecteur vidéo natif :
 *  - Android : Media3 / ExoPlayer
 *  - Linux / Windows : libVLC via vlcj
 */
@Composable
expect fun VideoPlayer(
    request: StreamRequest,
    controller: PlayerController,
    options: PlayerOptions,
    modifier: Modifier,
    onStatus: (PlayerStatus) -> Unit,
)
