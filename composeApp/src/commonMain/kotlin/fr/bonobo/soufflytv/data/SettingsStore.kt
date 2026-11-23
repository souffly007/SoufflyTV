package fr.bonobo.soufflytv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.bonobo.soufflytv.platform.applyDnsMode
import fr.bonobo.soufflytv.platform.readAppFile
import fr.bonobo.soufflytv.platform.writeAppFile
import fr.bonobo.soufflytv.player.AspectMode
import fr.bonobo.soufflytv.player.BufferMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Settings(
    val hideAdult: Boolean = false,
    val buffer: BufferMode = BufferMode.NORMAL,
    val aspect: AspectMode = AspectMode.FIT,
    /** Android : passer en paysage pendant la lecture. */
    val autoLandscape: Boolean = true,
    /** Android : image dans l'image quand on quitte l'app pendant la lecture. */
    val autoPip: Boolean = true,
    /** Afficher l'émission en cours dans les listes de chaînes (guide XMLTV). */
    val epgInLists: Boolean = true,
    /** Enchaîner automatiquement l'épisode suivant. */
    val autoNextEpisode: Boolean = true,
    /** Au lancement, reprendre directement la dernière chaîne regardée. */
    val resumeLastChannel: Boolean = false,
    /** Accueil : bandeau « Reprendre » avec les dernières chaînes. */
    val showResumeRow: Boolean = false,
    /** Choisir les pistes d'audiodescription quand elles existent (sinon, la piste normale). */
    val audioDescription: Boolean = false,
    /** Ordinateur : plein écran dès le lancement d'une chaîne ou d'un film. */
    val autoFullscreen: Boolean = false,
    /** Couleur qui montre où l'on se trouve à la télécommande. */
    val focusColor: FocusColor = FocusColor.YELLOW,
    /** TV en direct, grand écran : la chaîne survolée se lance en petit au-dessus de la liste. */
    val livePreview: Boolean = true,
    /** Serveur DNS utilisé pour joindre les sources (contourne le blocage par le DNS de la box). */
    val dns: DnsMode = DnsMode.SYSTEM,
)

/**
 * DNS chiffré (DNS-over-HTTPS) : l'adresse des serveurs est demandée directement à Cloudflare,
 * Quad9 ou Google, sans passer par le DNS de la box ou de l'opérateur.
 */
enum class DnsMode(val label: String, val detail: String) {
    SYSTEM("Appareil", "DNS de la box ou de l'opérateur (réglage du système)"),
    CLOUDFLARE("Cloudflare", "1.1.1.1 — rapide, sans filtrage"),
    QUAD9("Quad9", "9.9.9.9 — bloque en plus les sites malveillants"),
    GOOGLE("Google", "8.8.8.8"),
}

/** Résultat du test : adresse donnée par le DNS de l'appareil et par le DNS sécurisé. */
data class DnsCheck(val host: String, val system: String, val secure: String, val differs: Boolean)

/** Couleur du « curseur » de la télécommande : bien distincte du cyan qui marque la sélection. */
enum class FocusColor(val label: String, val argb: Long) {
    YELLOW("Jaune", 0xFFFFD600),
    ORANGE("Orange", 0xFFFF9100),
    WHITE("Blanc", 0xFFFFFFFF),
    CYAN("Cyan", 0xFF00D4EC),
}

class SettingsStore(private val scope: CoroutineScope) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    var value by mutableStateOf(Settings())
        private set

    suspend fun load() {
        val text = readAppFile(FILE) ?: return
        value = try {
            json.decodeFromString(Settings.serializer(), text)
        } catch (e: Exception) {
            Settings()
        }
        applyDnsMode(value.dns)
    }

    fun update(transform: (Settings) -> Settings) {
        value = transform(value)
        applyDnsMode(value.dns)
        val snapshot = value
        scope.launch { writeAppFile(FILE, json.encodeToString(Settings.serializer(), snapshot)) }
    }

    private companion object {
        const val FILE = "settings.json"
    }
}
