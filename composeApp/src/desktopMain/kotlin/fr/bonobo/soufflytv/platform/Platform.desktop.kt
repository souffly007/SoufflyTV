package fr.bonobo.soufflytv.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import fr.bonobo.soufflytv.data.IptvException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

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
    withContext(Dispatchers.IO) {
        var current = url
        var connection: HttpURLConnection? = null
        try {
            for (hop in 0 until 6) {
                val c = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept-Encoding", "gzip")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val code = c.responseCode
                if (code in 300..399) {
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    if (location.isNullOrBlank()) throw IptvException("Guide TV : redirection invalide")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code !in 200..299) {
                    c.disconnect()
                    throw IptvException("Guide TV : erreur HTTP $code")
                }
                connection = c
                break
            }
            val c = connection ?: throw IptvException("Guide TV : trop de redirections")
            var input: InputStream = BufferedInputStream(c.inputStream, 64 * 1024)
            input.mark(4)
            val b1 = input.read()
            val b2 = input.read()
            input.reset()
            if (b1 == 0x1f && b2 == 0x8b) input = GZIPInputStream(input, 64 * 1024)
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(32 * 1024)
                while (true) {
                    ensureActive()
                    val n = reader.read(buffer)
                    if (n < 0) break
                    onChunk(String(buffer, 0, n))
                }
            }
        } finally {
            connection?.disconnect()
        }
        Unit
    }

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
