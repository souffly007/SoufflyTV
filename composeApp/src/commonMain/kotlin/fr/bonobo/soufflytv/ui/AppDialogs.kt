package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import fr.bonobo.soufflytv.platform.isAndroid
import fr.bonobo.soufflytv.ui.theme.Cm

/*
 * Boîtes de dialogue.
 * Android : fenêtres de dialogue classiques.
 * Ordinateur : dessinées dans la fenêtre de l'application, par-dessus l'écran. Sous Linux, les dialogues
 * en fenêtre séparée (nécessaires au-dessus de la vidéo libVLC) ne s'affichent pas toujours.
 */

class DialogEntry(content: @Composable () -> Unit, onDismiss: () -> Unit) {
    var content by mutableStateOf(content)
    var onDismiss by mutableStateOf(onDismiss)
}

/** Dialogues ouverts sur ordinateur, dessinés par [DialogLayer] tout en haut de la fenêtre. */
class DialogHost {
    val entries = mutableStateListOf<DialogEntry>()

    /** Échap : ferme le dialogue du dessus ; false s'il n'y en a pas. */
    fun dismissTop(): Boolean {
        val top = entries.lastOrNull() ?: return false
        top.onDismiss()
        return true
    }
}

val LocalDialogHost = staticCompositionLocalOf<DialogHost?> { null }

/** Dialogue : fenêtre sur Android, calque dans la fenêtre sur ordinateur. */
@Composable
fun AppDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalDialogHost.current
    if (isAndroid || host == null) {
        Dialog(onDismissRequest = onDismissRequest) { content() }
        return
    }
    val entry = remember { DialogEntry(content, onDismissRequest) }
    SideEffect {
        entry.content = content
        entry.onDismiss = onDismissRequest
    }
    DisposableEffect(host, entry) {
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}

/** À placer en dernier dans la fenêtre : voile sombre + dialogues (un clic à côté ferme). */
@Composable
fun DialogLayer(host: DialogHost) {
    host.entries.forEach { entry ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .pointerInput(entry) { detectTapGestures { entry.onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            // les clics dans le dialogue ne le ferment pas
            Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) {
                entry.content()
            }
        }
    }
}

/** Question avec Confirmer / Annuler, même rendu sur Android et sur ordinateur. */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    if (isAndroid || LocalDialogHost.current == null) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            containerColor = Cm.SurfaceHigh,
            title = { Text(title) },
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
        )
        return
    }
    AppDialog(onDismissRequest) {
        Column(
            Modifier
                .widthIn(min = 320.dp, max = 520.dp)
                .background(Cm.SurfaceHigh, RoundedCornerShape(24.dp))
                .border(1.dp, Cm.Outline, RoundedCornerShape(24.dp))
                .padding(24.dp),
        ) {
            Text(title, color = Cm.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            text()
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                dismissButton()
                confirmButton()
            }
        }
    }
}
