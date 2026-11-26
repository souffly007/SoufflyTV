package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.ui.theme.Cm
import kotlinx.coroutines.delay

/*
 * Saisie du code parental : 4 chiffres, avec le pavé à l'écran (télécommande, doigt, souris)
 * ou les touches chiffres de la télécommande et du clavier.
 */

const val PIN_LENGTH = 4

private val DIGIT_KEYS = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private val NUMPAD_KEYS = listOf(
    Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4,
    Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
)

/** Fenêtre de saisie d'un code à 4 chiffres ; [onComplete] reçoit le code dès le 4e chiffre. */
@Composable
fun PinDialog(
    title: String,
    subtitle: String?,
    error: String?,
    onDismiss: () -> Unit,
    onComplete: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }
    fun press(digit: Char) {
        if (pin.length >= PIN_LENGTH) return
        pin += digit
        if (pin.length == PIN_LENGTH) {
            val code = pin
            pin = ""
            onComplete(code)
        }
    }
    fun erase() {
        pin = pin.dropLast(1)
    }

    AppDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .background(Cm.Surface, RoundedCornerShape(18.dp))
                .border(1.dp, Cm.Cyan.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val digit = DIGIT_KEYS.indexOf(event.key).takeIf { it >= 0 } ?: NUMPAD_KEYS.indexOf(event.key)
                    when {
                        digit >= 0 -> {
                            press('0' + digit)
                            true
                        }
                        event.key == Key.Backspace || event.key == Key.Delete -> {
                            erase()
                            true
                        }
                        else -> false
                    }
                }
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(AppIcons.Lock, contentDescription = null, tint = Cm.Cyan, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, color = Cm.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Text(subtitle, color = Cm.TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            // points : un par chiffre déjà saisi
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(PIN_LENGTH) { i ->
                    Box(
                        Modifier
                            .size(16.dp)
                            .background(if (i < pin.length) Cm.Cyan else Color.Transparent, CircleShape)
                            .border(2.dp, Cm.Cyan, CircleShape),
                    )
                }
            }
            Text(
                error ?: " ",
                color = Cm.Error,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
            val rows = listOf("123", "456", "789", "⌫0✕")
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                    row.forEach { c ->
                        val modifier = if (c == '1') Modifier.focusRequester(first) else Modifier
                        Box(
                            modifier
                                .size(58.dp)
                                .background(Cm.SurfaceHigher, CircleShape)
                                .tvClickable(shape = CircleShape) {
                                    when (c) {
                                        '⌫' -> erase()
                                        '✕' -> onDismiss()
                                        else -> press(c)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                c.toString(),
                                color = if (c == '✕') Cm.TextMuted else Cm.Text,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Demande du code en cours (catégorie adulte, réglages, comptes) : affichée par-dessus tout l'écran. */
@Composable
fun ParentalPromptHost(state: AppState) {
    val prompt = state.parental.prompt ?: return
    var error by remember(prompt) { mutableStateOf<String?>(null) }
    PinDialog(
        title = "Code parental",
        subtitle = prompt.reason,
        error = error,
        onDismiss = { state.parental.cancel() },
        onComplete = { code ->
            if (!state.parental.submit(code)) error = "Code incorrect, réessaie"
        },
    )
}
