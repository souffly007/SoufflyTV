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
    /** TV : fréquence d'image automatique (l'écran suit la vidéo : 50 Hz, 59,94 Hz, 23,976 Hz…). */
    val autoFrameRate: Boolean = false,
    /** Serveur DNS utilisé pour joindre les sources (contourne le blocage par le DNS de la box). */
    val dns: DnsMode = DnsMode.SYSTEM,
    /** Guide TV : dernière qualité choisie (SD, HD, FHD, UHD…), proposée en premier la fois suivante. */
    val preferredQuality: String = "",
    /** Contrôle parental : actif, empreinte du code PIN, et ce qu'il protège. */
    val parental: Boolean = false,
    val pinHash: String = "",
    val lockAdult: Boolean = true,
    val lockSettings: Boolean = true,
    val lockAccounts: Boolean = true,
    /** Taille du texte de toute l'interface. */
    val textSize: TextSize = TextSize.NORMAL,
    /** Guide TV : décalage horaire (minutes, ±) et fréquence de mise à jour (heures). */
    val epgOffsetMinutes: Int = 0,
    val epgRefreshHours: Int = 6,
    /** Rappels : prévenir tant de minutes avant le début de l'émission. */
    val reminderMinutes: Int = 2,
)

/** Taille du texte : petite TV, TV vue de loin, grand écran de PC… */
enum class TextSize(val label: String, val scale: Float) {
    SMALL("Petite", 0.9f),
    NORMAL("Normale", 1f),
    LARGE("Grande", 1.15f),
    XLARGE("Très grande", 1.3f),
}

/** Applique les réglages lus par le reste de l'appli hors de l'interface (DNS, guide TV). */
private fun applyGlobals(s: Settings) {
    applyDnsMode(s.dns)
    EpgPrefs.offsetMs = s.epgOffsetMinutes * 60_000L
    EpgPrefs.refreshMs = s.epgRefreshHours.coerceIn(1, 48) * HOUR_MS
}

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
        applyGlobals(value)
    }

    fun update(transform: (Settings) -> Settings) {
        value = transform(value)
        applyGlobals(value)
        val snapshot = value
        scope.launch { writeAppFile(FILE, json.encodeToString(Settings.serializer(), snapshot)) }
    }

    private companion object {
        const val FILE = "settings.json"
    }
}
