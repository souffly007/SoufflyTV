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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.data.FocusColor
import fr.bonobo.soufflytv.platform.isAndroid
import fr.bonobo.soufflytv.platform.isTvBuild
import fr.bonobo.soufflytv.player.AspectMode
import fr.bonobo.soufflytv.player.BufferMode
import fr.bonobo.soufflytv.ui.theme.Cm

@Composable
fun SettingsScreen(state: AppState) {
    val settings = state.settings.value
    var confirmClear by remember { mutableStateOf(false) }

    // Contenu de chaque rubrique
    val lecture: @Composable () -> Unit = {
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
                    "Audiodescription",
                    "Une voix décrit l'image, pour les personnes malvoyantes. Désactivée : la piste normale est choisie. Aussi via le bouton AD du lecteur",
                    settings.audioDescription,
                ) { v -> state.settings.update { it.copy(audioDescription = v) } }
                SwitchRow(
                    "Épisode suivant automatique",
                    "Enchaîne l'épisode suivant à la fin d'un épisode",
                    settings.autoNextEpisode,
                ) { v -> state.settings.update { it.copy(autoNextEpisode = v) } }

                if (!isAndroid) {
                    SwitchRow(
                        "Plein écran automatique",
                        "Passe en plein écran au lancement d'une chaîne ou d'un film. F ou double-clic : plein écran, Échap : en sortir",
                        settings.autoFullscreen,
                    ) { v -> state.settings.update { it.copy(autoFullscreen = v) } }
                }
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

    }
    val affichage: @Composable () -> Unit = {
                Text(
                    "Couleur du curseur",
                    color = Cm.Text,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Text(
                    "Cadre qui montre où tu te trouves à la télécommande ou au clavier",
                    color = Cm.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FocusColor.entries.forEach { color ->
                        Pill(color.label, selected = settings.focusColor == color) {
                            state.settings.update { it.copy(focusColor = color) }
                        }
                    }
                }
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

    }
    val comptes: @Composable () -> Unit = {
                AccountsBackupSection(state)
    }
    val donnees: @Composable () -> Unit = {
                OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.padding(horizontal = 14.dp)) {
                    Text("Effacer l'historique et les reprises")
                }
    }
    val aPropos: @Composable () -> Unit = {
                Text(
                    "SoufflyTV 0.8 (${if (isTvBuild) "version TV" else if (isAndroid) "version smartphone" else "version ordinateur"}) — lecteur IPTV libre sous licence GPL v3.\n" +
                        "Aucune chaîne ni liste n'est fournie : l'application lit uniquement les sources que tu ajoutes.\n" +
                        "Lecture : Media3 (Android), libVLC (ordinateur).",
                    color = Cm.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
    }

    val rubrics = listOf(
        SettingsRubric("Lecture", AppIcons.Play, lecture),
        SettingsRubric("Affichage", AppIcons.Aspect, affichage),
        SettingsRubric("Comptes", AppIcons.Library, comptes),
        SettingsRubric("Données", AppIcons.History, donnees),
        SettingsRubric("À propos", AppIcons.Settings, aPropos),
    )

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Réglages", onBack = { state.back() })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 840.dp) {
                // Grand écran : rubriques à gauche, contenu à droite
                var selected by remember { mutableIntStateOf(0) }
                val firstFocus = remember { FocusRequester() }
                var firstFocused by remember { mutableStateOf(false) }
                KeepFocus(firstFocus, Unit, isFocused = { firstFocused })
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(250.dp).fillMaxHeight().padding(12.dp)) {
                        rubrics.forEachIndexed { i, rubric ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                    // à la télécommande, passer sur une rubrique l'affiche déjà
                                    .onFocusChanged {
                                        if (i == 0) firstFocused = it.isFocused
                                        if (it.isFocused) selected = i
                                    }
                                    .tvClickable(selected = i == selected) { selected = i }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(rubric.icon, contentDescription = null, tint = if (i == selected) Cm.Cyan else Cm.TextMuted, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    rubric.title,
                                    color = if (i == selected) Cm.Cyan else Cm.Text,
                                    fontSize = 15.sp,
                                    fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Cm.Outline))
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp, horizontal = 12.dp),
                    ) {
                        Column(Modifier.widthIn(max = 720.dp)) {
                            SectionLabel(rubrics[selected].title.uppercase(), Modifier.padding(start = 14.dp, bottom = 8.dp))
                            rubrics[selected].content()
                        }
                    }
                }
            } else {
                // Téléphone : toutes les rubriques à la suite
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 8.dp)) {
                        rubrics.forEachIndexed { i, rubric ->
                            if (i > 0) Spacer(Modifier.height(16.dp))
                            SectionLabel(rubric.title.uppercase(), Modifier.padding(start = 14.dp, top = 8.dp, bottom = 6.dp))
                            rubric.content()
                        }
                    }
                }
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


private class SettingsRubric(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val content: @Composable () -> Unit,
)
