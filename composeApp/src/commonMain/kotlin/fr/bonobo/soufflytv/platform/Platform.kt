package fr.bonobo.soufflytv.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent

/** Lit un fichier du dossier privé de l'application (null s'il n'existe pas). */
expect suspend fun readAppFile(name: String): String?

/** Écrit un fichier du dossier privé de l'application (écriture atomique). */
expect suspend fun writeAppFile(name: String, content: String)

expect suspend fun deleteAppFile(name: String)

/** Fichier contenant des identifiants : chiffré (Android Keystore) ou protégé (ordinateur). */
expect suspend fun readSecureFile(name: String): String?

expect suspend fun writeSecureFile(name: String, content: String)

/**
 * Télécharge un texte potentiellement énorme (guide XMLTV) morceau par morceau,
 * en décompressant le gzip si besoin, sans tout garder en mémoire.
 */
expect suspend fun streamText(url: String, headers: Map<String, String>, onChunk: (String) -> Unit)

/** Heure actuelle (ms depuis 1970, UTC). */
expect fun nowMillis(): Long

/** « 18:45 » dans le fuseau de l'appareil. */
expect fun formatClock(epochMillis: Long): String

expect val isAndroid: Boolean

/** APK « tv » : interface toujours en grand écran, pas de rotation ni d'image dans l'image. */
expect val isTvBuild: Boolean

/** true : commandes du lecteur par-dessus la vidéo ; false : au-dessus et en dessous (libVLC). */
expect val playerOverlay: Boolean

/** Plein écran pendant la lecture, avec passage en paysage si demandé (Android). */
@Composable
expect fun FullscreenEffect(landscape: Boolean)

class PickedFile(val name: String, val content: String)

/**
 * Renvoie une fonction qui ouvre le sélecteur de fichiers du système.
 * Elle renvoie false si l'appareil n'en a pas (beaucoup de téléviseurs Android).
 */
@Composable
expect fun rememberFilePicker(title: String = "Choisir un fichier", onPicked: (PickedFile?) -> Unit): () -> Boolean

sealed interface SaveResult {
    /** [where] : nom ou emplacement du fichier enregistré, à montrer à l'utilisateur. */
    data class Saved(val where: String) : SaveResult
    data object Cancelled : SaveResult
    data class Failed(val message: String) : SaveResult
}

/**
 * Renvoie une fonction qui enregistre un fichier texte là où l'utilisateur le choisit.
 * Sans explorateur de fichiers (téléviseurs), Android le range dans Téléchargements.
 */
@Composable
expect fun rememberFileSaver(onSaved: (SaveResult) -> Unit): (fileName: String, content: String) -> Unit

/**
 * Renvoie une fonction qui ouvre un lien hors de l'application (bande-annonce YouTube…) :
 * l'appli YouTube sur Android et Android TV, le navigateur sur PC. Renvoie false en cas d'échec.
 */
@Composable
expect fun rememberUrlOpener(): (url: String) -> Boolean

/** Sauvegardes : clé tirée du mot de passe (PBKDF2-HMAC-SHA256), chiffrement AES-256-GCM. */
expect fun passwordEncrypt(data: ByteArray, password: String, salt: ByteArray, iv: ByteArray, iterations: Int): ByteArray

/** Lève une exception si le mot de passe est faux ou le fichier modifié (contrôle GCM). */
expect fun passwordDecrypt(data: ByteArray, password: String, salt: ByteArray, iv: ByteArray, iterations: Int): ByteArray

/** Octets aléatoires sûrs (sel, vecteur d'initialisation). */
expect fun secureRandomBytes(size: Int): ByteArray

/** Touches dédiées CH+ / CH- de la télécommande : +1, -1, sinon 0. */
expect fun KeyEvent.channelKeyDelta(): Int

/** Client HTTP de l'application (moteur OkHttp, avec le DNS choisi dans les réglages). */
expect fun platformHttpClient(block: io.ktor.client.HttpClientConfig<*>.() -> Unit): io.ktor.client.HttpClient

/** Change le DNS utilisé par toutes les connexions (listes, guide, images, vidéo). */
expect fun applyDnsMode(mode: fr.bonobo.soufflytv.data.DnsMode)

/** Compare l'adresse de [host] donnée par le DNS de l'appareil et par [mode]. */
expect suspend fun checkDns(host: String, mode: fr.bonobo.soufflytv.data.DnsMode): fr.bonobo.soufflytv.data.DnsCheck
