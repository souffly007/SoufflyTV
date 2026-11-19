package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.platform.isAndroid
import fr.bonobo.soufflytv.platform.isTvBuild
import fr.bonobo.soufflytv.player.AspectMode
import fr.bonobo.soufflytv.player.BufferMode
import fr.bonobo.soufflytv.ui.theme.Cm

@Composable
fun SettingsScreen(state: AppState) {
    val settings = state.settings.value
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Réglages", onBack = { state.back() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 8.dp)) {

                SectionLabel("LECTURE", Modifier.padding(start = 14.dp, top = 8.dp, bottom = 6.dp))
                Text(
                    "Mémoire tampon du direct",
                    color = Cm.Text,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Text(
                    "Plus élevée = moins de coupures, mais démarrage plus lent",
                    color = Cm.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BufferMode.entries.forEach { mode ->
                        Pill(mode.label, selected = settings.buffer == mode) {
                            state.settings.update { it.copy(buffer = mode) }
                        }
                    }
                }
                Text("Format d'image par défaut", color = Cm.Text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp))
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AspectMode.entries.forEach { mode ->
                        Pill(mode.label, selected = settings.aspect == mode) {
                            state.settings.update { it.copy(aspect = mode) }
                        }
                    }
                }
                SwitchRow(
                    "Démarrer sur la dernière chaîne",
                    "À l'ouverture, relance directement la dernière chaîne en direct regardée",
                    settings.resumeLastChannel,
                ) { v -> state.settings.update { it.copy(resumeLastChannel = v) } }
                SwitchRow(
                    "Épisode suivant automatique",
                    "Enchaîne l'épisode suivant à la fin d'un épisode",
                    settings.autoNextEpisode,
                ) { v -> state.settings.update { it.copy(autoNextEpisode = v) } }

                if (isAndroid && !isTvBuild) {
                    SwitchRow(
                        "Paysage pendant la lecture",
                        "Tourne l'écran automatiquement à l'ouverture d'une chaîne",
                        settings.autoLandscape,
                    ) { v -> state.settings.update { it.copy(autoLandscape = v) } }
                    SwitchRow(
                        "Image dans l'image",
                        "Continue la lecture en petite fenêtre quand tu quittes l'app",
                        settings.autoPip,
                    ) { v -> state.settings.update { it.copy(autoPip = v) } }
                }

                Spacer(Modifier.height(12.dp))
                SectionLabel("AFFICHAGE", Modifier.padding(start = 14.dp, top = 8.dp, bottom = 6.dp))
                SwitchRow(
                    "Bandeau « Reprendre » sur l'accueil",
                    "Affiche les dernières chaînes regardées au-dessus des sources",
                    settings.showResumeRow,
                ) { v -> state.settings.update { it.copy(showResumeRow = v) } }
                SwitchRow(
                    "Programme en cours dans les listes",
                    "Avec un guide TV XMLTV (playlists M3U)",
                    settings.epgInLists,
                ) { v -> state.settings.update { it.copy(epgInLists = v) } }
                SwitchRow(
                    "Masquer les catégories adultes",
                    "Catégories dont le nom contient « adult », « XXX », « +18 »…",
                    settings.hideAdult,
                ) { v -> state.settings.update { it.copy(hideAdult = v) } }

                Spacer(Modifier.height(12.dp))
                SectionLabel("DONNÉES", Modifier.padding(start = 14.dp, top = 8.dp, bottom = 6.dp))
                OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.padding(horizontal = 14.dp)) {
                    Text("Effacer l'historique et les reprises")
                }

                Spacer(Modifier.height(24.dp))
                SectionLabel("À PROPOS", Modifier.padding(start = 14.dp, bottom = 6.dp))
                Text(
                    "SoufflyTV 0.5 (${if (isTvBuild) "version TV" else if (isAndroid) "version smartphone" else "version ordinateur"}) — lecteur IPTV libre sous licence GPL v3.\n" +
                        "Aucune chaîne ni liste n'est fournie : l'application lit uniquement les sources que tu ajoutes.\n" +
                        "Lecture : Media3 (Android), libVLC (ordinateur).",
                    color = Cm.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Cm.SurfaceHigh,
            title = { Text("Effacer l'historique ?") },
            text = { Text("Les chaînes récentes et les positions de reprise seront supprimées. Les favoris sont conservés.", color = Cm.TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    state.library.clearHistory()
                    confirmClear = false
                }) { Text("Effacer", color = Cm.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }
}
