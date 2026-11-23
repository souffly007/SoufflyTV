package fr.bonobo.soufflytv.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AndroidApp {
    lateinit var context: Context
}

actual val isAndroid: Boolean = true
actual val isTvBuild: Boolean = fr.bonobo.soufflytv.BuildConfig.IS_TV
actual val playerOverlay: Boolean = true

private fun dir(): File = AndroidApp.context.filesDir

private fun atomicWrite(target: File, bytes: ByteArray) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(target)) {
        target.delete()
        tmp.renameTo(target)
    }
}

actual suspend fun readAppFile(name: String): String? = withContext(Dispatchers.IO) {
    File(dir(), name).takeIf { it.exists() }?.readText()
}

actual suspend fun writeAppFile(name: String, content: String) = withContext(Dispatchers.IO) {
    atomicWrite(File(dir(), name), content.toByteArray(Charsets.UTF_8))
}

actual suspend fun deleteAppFile(name: String) = withContext(Dispatchers.IO) {
    File(dir(), name).delete()
    Unit
}

// ---------- Fichier chiffré (AES-256-GCM, clé dans l'Android Keystore) ----------

private const val KEY_ALIAS = "soufflytv_sources"
private const val PREFIX = "SFTV1:"

private fun secretKey(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(
        KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
    )
    return generator.generateKey()
}

private fun encrypt(plain: String): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
    val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    return PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
}

private fun decrypt(text: String): String {
    val parts = text.removePrefix(PREFIX).trim().split(':', limit = 2)
    val iv = Base64.decode(parts[0], Base64.NO_WRAP)
    val data = Base64.decode(parts[1], Base64.NO_WRAP)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
    return String(cipher.doFinal(data), Charsets.UTF_8)
}

actual suspend fun readSecureFile(name: String): String? = withContext(Dispatchers.IO) {
    val file = File(dir(), name)
    if (!file.exists()) return@withContext null
    val text = file.readText()
    if (!text.startsWith(PREFIX)) return@withContext text // ancien fichier en clair (v0.1)
    try {
        decrypt(text)
    } catch (e: Exception) {
        null
    }
}

actual suspend fun writeSecureFile(name: String, content: String) = withContext(Dispatchers.IO) {
    val payload = try {
        encrypt(content)
    } catch (e: Exception) {
        content // Keystore indisponible (rare) : on garde au moins les données, dans le dossier privé
    }
    atomicWrite(File(dir(), name), payload.toByteArray(Charsets.UTF_8))
}

// ---------- Réseau ----------

actual suspend fun streamText(url: String, headers: Map<String, String>, onChunk: (String) -> Unit) =
    streamTextJvm(url, headers, onChunk)

// ---------- Télécommande ----------

actual fun KeyEvent.channelKeyDelta(): Int = when (nativeKeyEvent.keyCode) {
    android.view.KeyEvent.KEYCODE_CHANNEL_UP,
    android.view.KeyEvent.KEYCODE_PLUS,
    android.view.KeyEvent.KEYCODE_NUMPAD_ADD -> 1
    android.view.KeyEvent.KEYCODE_CHANNEL_DOWN,
    android.view.KeyEvent.KEYCODE_MINUS,
    android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> -1
    else -> 0
}

// ---------- Heure ----------

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

// ---------- Plein écran ----------

@Composable
actual fun FullscreenEffect(landscape: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, landscape) {
        val activity = view.context.findActivity()
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val previousOrientation = activity?.requestedOrientation
        if (landscape && activity != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (landscape && activity != null && previousOrientation != null) {
                activity.requestedOrientation = previousOrientation
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ---------- Sélecteur de fichier ----------

@Composable
actual fun rememberFilePicker(title: String, onPicked: (PickedFile?) -> Unit): () -> Boolean {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            callback(null)
        } else {
            scope.launch {
                val picked = withContext(Dispatchers.IO) {
                    try {
                        val resolver = context.contentResolver
                        val name = displayName(context, uri) ?: "fichier"
                        val text = resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        text?.let { PickedFile(name, it) }
                    } catch (e: Exception) {
                        null
                    }
                }
                callback(picked)
            }
        }
    }
    return remember(launcher) {
        {
            try {
                launcher.launch(arrayOf("*/*"))
                true
            } catch (e: ActivityNotFoundException) {
                // Pas d'explorateur de fichiers (fréquent sur Android TV)
                false
            }
        }
    }
}

@Composable
actual fun rememberFileSaver(onSaved: (SaveResult) -> Unit): (fileName: String, content: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onSaved)
    val pending = remember { arrayOfNulls<String>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = pending[0]
        pending[0] = null
        if (uri == null || content == null) {
            callback(SaveResult.Cancelled)
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
                            ?: error("écriture impossible")
                        SaveResult.Saved(displayName(context, uri) ?: "fichier enregistré")
                    } catch (e: Exception) {
                        SaveResult.Failed("Enregistrement impossible : ${e.message}")
                    }
                }
                callback(result)
            }
        }
    }
    return remember(launcher) {
        { fileName, content ->
            pending[0] = content
            try {
                launcher.launch(fileName)
            } catch (e: ActivityNotFoundException) {
                // Téléviseur sans explorateur : directement dans Téléchargements
                pending[0] = null
                scope.launch { callback(withContext(Dispatchers.IO) { saveToDownloads(context, fileName, content) }) }
            }
        }
    }
}

@Composable
actual fun rememberUrlOpener(): (url: String) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { url ->
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (e: ActivityNotFoundException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

private fun saveToDownloads(context: Context, fileName: String, content: String): SaveResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return SaveResult.Failed("Aucun explorateur de fichiers sur cet appareil pour choisir où enregistrer")
    }
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("dossier inaccessible")
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } ?: error("écriture impossible")
        SaveResult.Saved("Téléchargements/$fileName")
    } catch (e: Exception) {
        SaveResult.Failed("Enregistrement impossible : ${e.message}")
    }
}
