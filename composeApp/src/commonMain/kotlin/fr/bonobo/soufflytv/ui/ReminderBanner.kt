package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.platform.formatClock
import fr.bonobo.soufflytv.ui.theme.Cm
import kotlinx.coroutines.delay

/**
 * Bandeau « Ça commence » d'un rappel du guide TV, en haut à droite, par-dessus n'importe quel écran
 * (y compris pendant la lecture d'une autre chaîne). Télécommande : le focus va sur « Regarder ».
 */
@Composable
fun ReminderBanner(state: AppState) {
    val reminder = state.dueReminder ?: return
    val watch = remember(reminder.key) { FocusRequester() }
    LaunchedEffect(reminder.key) {
        repeat(10) {
            if (runCatching { watch.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(80)
        }
    }
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.TopEnd) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .background(Cm.SurfaceHigh, RoundedCornerShape(16.dp))
                .border(2.dp, Cm.Cyan, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Text("⏰ ÇA COMMENCE", color = Cm.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(reminder.title, color = Cm.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Sur ${reminder.channelName} à ${formatClock(reminder.start)}", color = Cm.TextMuted, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { state.watchReminder(reminder) },
                    colors = ButtonDefaults.buttonColors(containerColor = Cm.Cyan, contentColor = Cm.OnCyan),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.focusRequester(watch),
                ) { Text("▶ Regarder", fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = { state.dismissReminder(reminder) }, shape = RoundedCornerShape(10.dp)) {
                    Text("Ignorer", color = Cm.Text)
                }
            }
        }
    }
}
