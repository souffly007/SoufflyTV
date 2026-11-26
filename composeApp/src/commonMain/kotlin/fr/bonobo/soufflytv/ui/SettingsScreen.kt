package fr.bonobo.soufflytv.ui

import fr.bonobo.soufflytv.data.TextSize
import fr.bonobo.soufflytv.Screen
import androidx.compose.runtime.collectAsState
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import fr.bonobo.soufflytv.platform.checkDns
import fr.bonobo.soufflytv.data.serverHost
import fr.bonobo.soufflytv.data.DnsMode
import fr.bonobo.soufflytv.data.DnsCheck
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
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
                if (isAndroid) {
                    SwitchRow(
                        "Aperçu des chaînes",
                        "TV en direct sur grand écran : la chaîne survolée se lance en petit au-dessus de la liste",
                        settings.livePreview,
                    ) { v -> state.settings.update { it.copy(livePreview = v) } }
                }
                if (isTvBuild) {
                    SwitchRow(
                        "Fréquence d'image automatique (AFR)",
                        "La TV passe à la fréquence de la vidéo (50 Hz, 59,94 Hz, 23,976 Hz…) pour une image plus fluide. " +
                            "Un écran noir d'une ou deux secondes est normal au changement",
                        settings.autoFrameRate,
                    ) { v -> state.settings.update { it.copy(autoFrameRate = v) } }
                }
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
                Text("Taille du texte", color = Cm.Text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp))
                Text(
                    "Pour une petite TV ou une TV vue de loin : tous les textes de SoufflyTV grandissent ou rapetissent",
                    color = Cm.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextSize.entries.forEach { size ->
                        Pill(size.label, selected = settings.textSize == size) {
                            state.settings.update { it.copy(textSize = size) }
                        }
                    }
                }
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
    val reseau: @Composable () -> Unit = {
                DnsSection(state)
    }
    val filtres: @Composable () -> Unit = {
                FiltersSection(state)
    }
    val guide: @Composable () -> Unit = {
                GuideSettingsSection(state)
    }
    val parentalRubric: @Composable () -> Unit = {
                ParentalSection(state)
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
                    "SoufflyTV 0.9 (${if (isTvBuild) "version TV" else if (isAndroid) "version smartphone" else "version ordinateur"}) — lecteur IPTV libre sous licence GPL v3.\n" +
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
        SettingsRubric("Guide TV", AppIcons.Guide, guide),
        SettingsRubric("Filtres", AppIcons.Filter, filtres),
        SettingsRubric("Contrôle parental", AppIcons.Lock, parentalRubric),
        SettingsRubric("Réseau", AppIcons.Dns, reseau),
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
                    // Rubriques : la colonne défile (petit écran, grand texte), le focus l'entraîne
                    Column(Modifier.width(250.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
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
        AppAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = "Effacer l'historique ?",
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

/**
 * Réseau : DNS sécurisé (Cloudflare, Quad9, Google) et test sur les serveurs des comptes enregistrés,
 * pour voir si la box donne une autre adresse (signe d'un blocage).
 */
@Composable
private fun DnsSection(state: AppState) {
    val settings = state.settings.value
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<DnsCheck>>(emptyList()) }

    Text("DNS sécurisé", color = Cm.Text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp))
    Text(
        "Certaines box bloquent des serveurs IPTV par leur DNS. Avec un DNS sécurisé (chiffré), SoufflyTV demande " +
            "l'adresse des serveurs directement à Cloudflare, Quad9 ou Google : listes, guide TV, affiches et vidéo.",
        color = Cm.TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
    Text(
        "Tu utilises déjà un VPN ou un bloqueur DNS (DNSphere…) ? Il gère le DNS lui-même : laisse « Appareil ».",
        color = Cm.TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp),
    )
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DnsMode.entries.forEach { mode ->
            Pill(mode.label, selected = settings.dns == mode) {
                state.settings.update { it.copy(dns = mode) }
                results = emptyList()
            }
        }
    }
    Text(
        settings.dns.detail + if (settings.dns != DnsMode.SYSTEM && !isAndroid) {
            ". Sur ordinateur, la vidéo passe alors par un petit relais interne à SoufflyTV."
        } else "",
        color = Cm.TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        enabled = !testing,
        onClick = {
            testing = true
            results = emptyList()
            scope.launch {
                val hosts = state.sources.value.mapNotNull { it.serverHost() }.distinct().take(6)
                    .ifEmpty { listOf("cloudflare.com") }
                val mode = state.settings.value.dns
                results = hosts.map { host ->
                    runCatching { checkDns(host, mode) }.getOrElse { DnsCheck(host, "erreur", "erreur", false) }
                }
                testing = false
            }
        },
        modifier = Modifier.padding(horizontal = 14.dp),
    ) {
        Text(if (testing) "Test en cours…" else "Tester avec mes comptes")
    }
    val secureName = if (settings.dns == DnsMode.SYSTEM) DnsMode.CLOUDFLARE.label else settings.dns.label
    results.forEach { check ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(check.host, color = Cm.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Box / appareil : ${check.system}", color = Cm.TextMuted, fontSize = 12.sp)
            Text("$secureName : ${check.secure}", color = Cm.TextMuted, fontSize = 12.sp)
            when {
                check.secure == "injoignable" || check.secure.startsWith("erreur") ->
                    Text(
                        "Le DNS sécurisé ne répond pas sur ce réseau : un VPN ou un bloqueur DNS (DNSphere…) le bloque sans doute. " +
                            "SoufflyTV utilise alors celui de l'appareil ; dans ce cas, laisse « Appareil ».",
                        color = Cm.TextMuted,
                        fontSize = 12.sp,
                    )
                check.system == "introuvable" && check.secure != "introuvable" ->
                    Text("⚠ Introuvable avec le DNS de la box : probablement bloqué. Le DNS sécurisé règle le problème.", color = Cm.Error, fontSize = 12.sp)
                check.differs ->
                    Text(
                        "⚠ Adresses différentes : la box redirige peut-être ce serveur (blocage). " +
                            "Si les chaînes ne se lancent pas, active le DNS sécurisé.",
                        color = Cm.Error,
                        fontSize = 12.sp,
                    )
                else -> Text("✓ Même réponse : pas de blocage par le DNS.", color = Cm.Success, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Filtres : pour chaque compte et chaque rubrique (TV en direct, Films, Séries), choix des catégories
 * à masquer. Avant dans la barre latérale ; ici, la barre reste plus courte sur TV et ordinateur.
 */
@Composable
private fun FiltersSection(state: AppState) {
    val sources by state.sources.collectAsState()
    Text("Catégories affichées", color = Cm.Text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp))
    Text(
        "Masque les catégories qui ne t'intéressent pas (langues, pays, adultes…) : elles disparaissent des listes, " +
            "du guide TV et de la recherche.",
        color = Cm.TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
    if (sources.isEmpty()) {
        Text("Aucun compte pour le moment.", color = Cm.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        return
    }
    sources.forEach { source ->
        val sections = remember(source) { state.provider(source).sections }
        Text(
            source.name,
            color = Cm.Cyan,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sections.forEach { section ->
                val hidden = state.library.hiddenIds(source.id, section).size
                OutlinedButton(onClick = { state.navigate(Screen.ManageCategories(source, section)) }) {
                    Icon(AppIcons.Filter, contentDescription = null, tint = Cm.Cyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        section.label + if (hidden > 0) " · $hidden masquée(s)" else "",
                        color = Cm.Text,
                    )
                }
            }
        }
    }
}

/** « Aucun », « +1 h », « −30 min », « +1 h 30 ». */
private fun offsetLabel(minutes: Int): String {
    if (minutes == 0) return "Aucun"
    val sign = if (minutes > 0) "+" else "−"
    val m = kotlin.math.abs(minutes)
    return when {
        m < 60 -> "$sign$m min"
        m % 60 == 0 -> "$sign${m / 60} h"
        else -> "$sign${m / 60} h ${(m % 60).toString().padStart(2, '0')}"
    }
}

/** Guide TV : décalage horaire, fréquence de mise à jour, et délai des rappels. */
@Composable
private fun GuideSettingsSection(state: AppState) {
    val settings = state.settings.value
    var notice by remember { mutableStateOf<String?>(null) }

    Text("Décalage horaire du guide", color = Cm.Text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp))
    Text(
        "Si les émissions du guide sont en avance ou en retard sur ce qui passe vraiment (fournisseur réglé sur un autre fuseau)",
        color = Cm.TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
    fun setOffset(minutes: Int) {
        state.settings.update { it.copy(epgOffsetMinutes = minutes.coerceIn(-12 * 60, 12 * 60)) }
        // les guides sont relus avec les nouvelles heures
        state.reloadGuides(clearCache = false)
    }
    Row(
        Modifier.padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = { setOffset(settings.epgOffsetMinutes - 30) }) { Text("− 30 min", color = Cm.Text) }
        Text(
            offsetLabel(settings.epgOffsetMinutes),
            color = if (settings.epgOffsetMinutes == 0) Cm.TextMuted else Cm.Cyan,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(90.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        OutlinedButton(onClick = { setOffset(settings.epgOffsetMinutes + 30) }) { Text("+ 30 min", color = Cm.Text) }
        if (settings.epgOffsetMinutes != 0) {
            OutlinedButton(onClick = { setOffset(0) }) { Text("Remettre à zéro", color = Cm.Text) }
        }
    }

    Text("Mise à jour du guide", color = Cm.Text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp))
    Text(
        "Le guide enregistré s'affiche tout de suite ; il est retéléchargé en arrière-plan à cet intervalle",
        color = Cm.TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(6, 12, 24).forEach { hours ->
            Pill("Toutes les $hours h", selected = settings.epgRefreshHours == hours) {
                state.settings.update { it.copy(epgRefreshHours = hours) }
            }
        }
    }
    OutlinedButton(
        onClick = {
            state.reloadGuides(clearCache = true)
            notice = "Le guide sera retéléchargé à l'ouverture de chaque compte"
        },
        modifier = Modifier.padding(horizontal = 14.dp),
    ) { Text("Mettre à jour le guide maintenant") }
    notice?.let { Text(it, color = Cm.Success, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }

    Spacer(Modifier.height(12.dp))
    Text("Rappels", color = Cm.Text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp))
    Text(
        "Quand prévenir avant le début d'une émission (bandeau dans l'appli, notification sur smartphone)",
        color = Cm.TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 2, 5, 10).forEach { minutes ->
            Pill("$minutes min avant", selected = settings.reminderMinutes == minutes) {
                state.settings.update { it.copy(reminderMinutes = minutes) }
                state.reminders.rescheduleAll()
            }
        }
    }
}

/** Contrôle parental : code PIN et ce qu'il protège. */
@Composable
private fun ParentalSection(state: AppState) {
    val settings = state.settings.value
    val parental = state.parental
    // Choix d'un code : null = rien ; "" = saisie du nouveau code ; sinon = confirmation de ce code
    var setup by remember { mutableStateOf<String?>(null) }
    var setupError by remember { mutableStateOf<String?>(null) }

    Text(
        "Un code à 4 chiffres protège les catégories adultes, les réglages et les comptes. " +
            "Une fois saisi, plus rien n'est demandé jusqu'à la fermeture de SoufflyTV.",
        color = Cm.TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
    if (!parental.enabled) {
        OutlinedButton(
            onClick = {
                setup = ""
                setupError = null
            },
            modifier = Modifier.padding(14.dp),
        ) {
            Icon(AppIcons.Lock, contentDescription = null, tint = Cm.Cyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Activer : choisir un code", color = Cm.Text)
        }
    } else {
        Text(
            if (parental.unlocked) "Actif · déverrouillé jusqu'à la fermeture de l'appli" else "Actif · verrouillé",
            color = Cm.Cyan,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        SwitchRow(
            "Catégories adultes",
            "Affichées avec un cadenas ; le code est demandé pour les ouvrir. Cachées ailleurs (accueil, recherche, guide) tant que c'est verrouillé",
            settings.lockAdult,
        ) { v -> state.settings.update { it.copy(lockAdult = v) } }
        SwitchRow("Réglages", "Le code est demandé pour ouvrir les réglages et les filtres", settings.lockSettings) { v ->
            state.settings.update { it.copy(lockSettings = v) }
        }
        SwitchRow("Comptes", "Le code est demandé pour ajouter, modifier ou supprimer un compte", settings.lockAccounts) { v ->
            state.settings.update { it.copy(lockAccounts = v) }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = { parental.lockNow() }) { Text("Verrouiller maintenant", color = Cm.Text) }
            OutlinedButton(onClick = {
                parental.ask("Code actuel, pour en choisir un nouveau") {
                    setup = ""
                    setupError = null
                }
            }) { Text("Changer le code", color = Cm.Text) }
            OutlinedButton(onClick = { parental.ask("Désactiver le contrôle parental") { parental.disable() } }) {
                Text("Désactiver", color = Cm.Text)
            }
        }
        Text(
            "Code oublié : efface les données de SoufflyTV (Android : Paramètres → Applications → SoufflyTV → Stockage). " +
                "Les comptes seront à remettre : pense à faire une sauvegarde.",
            color = Cm.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }

    setup?.let { first ->
        PinDialog(
            title = if (first.isEmpty()) "Nouveau code parental" else "Confirme le code",
            subtitle = if (first.isEmpty()) "4 chiffres, à bien retenir" else "Tape-le une seconde fois",
            error = setupError,
            onDismiss = { setup = null },
            onComplete = { code ->
                when {
                    first.isEmpty() -> {
                        setup = code
                        setupError = null
                    }
                    code == first -> {
                        parental.setPin(code)
                        setup = null
                    }
                    else -> {
                        setup = ""
                        setupError = "Les deux codes sont différents : recommence"
                    }
                }
            },
        )
    }
}
