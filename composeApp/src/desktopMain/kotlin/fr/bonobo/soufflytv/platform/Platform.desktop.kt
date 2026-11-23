package fr.bonobo.soufflytv.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

actual val isAndroid: Boolean = false
actual val isTvBuild: Boolean = false
actual val playerOverlay: Boolean = false

/**
 * Dossier de données :
 *  - Linux   : $XDG_DATA_HOME/soufflytv (par défaut ~/.local/share/soufflytv)
 *  - Windows : %APPDATA%\SoufflyTV
 *  - macOS   : ~/Library/Application Support/SoufflyTV
 */
private val dataDir: File by lazy {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    val dir = when {
        os.contains("win") -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "SoufflyTV")
        os.contains("mac") -> File(home, "Library/Application Support/SoufflyTV")
        else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "soufflytv")
    }
    dir.apply { mkdirs() }
}

private fun atomicWrite(target: File, content: String) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.writeText(content)
    try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

actual suspend fun readAppFile(name: String): String? = withContext(Dispatchers.IO) {
    File(dataDir, name).takeIf { it.exists() }?.readText()
}

actual suspend fun writeAppFile(name: String, content: String) = withContext(Dispatchers.IO) {
    atomicWrite(File(dataDir, name), content)
}

actual suspend fun deleteAppFile(name: String) = withContext(Dispatchers.IO) {
    File(dataDir, name).delete()
    Unit
}

actual suspend fun readSecureFile(name: String): String? = readAppFile(name)

/** Sur ordinateur, le fichier n'est lisible que par l'utilisateur (droits 600). */
actual suspend fun writeSecureFile(name: String, content: String) = withContext(Dispatchers.IO) {
    val file = File(dataDir, name)
    atomicWrite(file, content)
    runCatching {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }
    Unit
}

actual suspend fun streamText(url: String, headers: Map<String, String>, onChunk: (String) -> Unit) =
    streamTextJvm(url, headers, onChunk)

/** Pavé numérique + / − (le + et le − du clavier principal passent par le caractère tapé). */
actual fun KeyEvent.channelKeyDelta(): Int = when (key) {
    Key.NumPadAdd -> 1
    Key.NumPadSubtract -> -1
    else -> 0
}

private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun formatClock(epochMillis: Long): String =
    clockFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d", java.util.Locale.FRENCH)

actual fun formatZoned(epochMillis: Long, zoneId: String?, pattern: String): String {
    val zone = zoneId?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    return DateTimeFormatter.ofPattern(pattern, java.util.Locale.ROOT).format(Instant.ofEpochMilli(epochMillis).atZone(zone))
}

actual fun formatDay(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    return when (java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(zone), day)) {
        0L -> "Aujourd'hui"
        1L -> "Demain"
        -1L -> "Hier"
        else -> dayFormatter.format(day)
    }
}

@Composable
actual fun FullscreenEffect(landscape: Boolean) = Unit

@Composable
actual fun rememberFilePicker(title: String, onPicked: (PickedFile?) -> Unit): () -> Boolean {
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onPicked)
    return remember {
        {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.isVisible = true // fenêtre modale
            val file = dialog.file?.let { File(dialog.directory, it) }
            if (file == null) {
                callback(null)
            } else {
                scope.launch {
                    val picked = withContext(Dispatchers.IO) {
                        runCatching { PickedFile(file.name, file.readText()) }.getOrNull()
                    }
                    callback(picked)
                }
            }
            true
        }
    }
}

@Composable
actual fun rememberFileSaver(onSaved: (SaveResult) -> Unit): (fileName: String, content: String) -> Unit {
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onSaved)
    return remember {
        { fileName, content ->
            val dialog = FileDialog(null as Frame?, "Enregistrer la sauvegarde", FileDialog.SAVE)
            dialog.file = fileName
            dialog.isVisible = true // fenêtre modale
            val file = dialog.file?.let { File(dialog.directory, it) }
            if (file == null) {
                callback(SaveResult.Cancelled)
            } else {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { file.writeText(content) }.fold(
                            onSuccess = { SaveResult.Saved(file.absolutePath) },
                            onFailure = { SaveResult.Failed("Enregistrement impossible : ${it.message}") },
                        )
                    }
                    callback(result)
                }
            }
        }
    }
}

@Composable
actual fun rememberUrlOpener(): (url: String) -> Boolean = remember {
    { url ->
        val linux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")
        runCatching {
            if (linux) {
                // xdg-open passe par le portail dans le Flatpak : le navigateur par défaut s'ouvre
                ProcessBuilder("xdg-open", url).start()
            } else {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            }
        }.isSuccess
    }
}
