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

/** Renvoie une fonction qui ouvre le sélecteur de fichiers du système. */
@Composable
expect fun rememberFilePicker(onPicked: (PickedFile?) -> Unit): () -> Unit

/** Touches dédiées CH+ / CH- de la télécommande : +1, -1, sinon 0. */
expect fun KeyEvent.channelKeyDelta(): Int
