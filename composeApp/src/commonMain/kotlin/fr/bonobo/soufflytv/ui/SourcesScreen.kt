package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.Screen
import fr.bonobo.soufflytv.data.detail
import fr.bonobo.soufflytv.data.kindLabel
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.MonoLabel

@Composable
fun SourcesScreen(state: AppState) {
    val sources by state.sources.collectAsState()
    var toDelete by remember { mutableStateOf<Source?>(null) }
    // Télécommande : le focus revient sur la dernière source ouverte (sinon la première)
    val sourceFocus = remember { FocusRequester() }
    var sourceFocused by remember { mutableStateOf(false) }
    val focusId = state.focusMemory["sources"]?.takeIf { id -> sources.any { it.id == id } } ?: sources.firstOrNull()?.id
    KeepFocus(sourceFocus, focusId, state.loaded, isFocused = { sourceFocused || focusId == null })
    // « Reprendre » : dernières chaînes dont la source existe encore
    val recents = if (state.settings.value.showResumeRow) {
        state.library.recents.filter { entry -> sources.any { it.id == entry.sourceId } }.take(15)
    } else emptyList()

    Column(Modifier.fillMaxSize()) {
        Header(
            showAdd = sources.isNotEmpty(),
            onAdd = { state.navigate(Screen.AddSource()) },
            onSettings = { state.navigate(Screen.Settings) },
        )
        CyanLine()

        when {
            !state.loaded -> LoadingBox("Chargement…")
            sources.isEmpty() -> EmptyState(onAdd = { state.navigate(Screen.AddSource()) })
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (recents.isNotEmpty()) {
                    item { SectionLabel("REPRENDRE", Modifier.padding(horizontal = 16.dp)) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(recents) { entry ->
                                RecentCard(entry.channel.name, entry.channel.logo) {
                                    val source = state.sourceById(entry.sourceId) ?: return@RecentCard
                                    state.play(source, recents.filter { it.sourceId == entry.sourceId }.map { it.channel },
                                        recents.filter { it.sourceId == entry.sourceId }.indexOf(entry).coerceAtLeast(0))
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
                item { SectionLabel("MES SOURCES · ${sources.size}", Modifier.padding(horizontal = 16.dp)) }
                items(sources, key = { it.id }) { source ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        SourceCard(
                            source,
                            modifier = if (source.id == focusId) {
                                Modifier.focusRequester(sourceFocus).onFocusChanged { sourceFocused = it.isFocused }
                            } else Modifier,
                            onOpen = {
                                state.focusMemory["sources"] = source.id
                                state.open(source)
                            },
                            onEdit = { state.navigate(Screen.AddSource(source)) },
                            onDelete = { toDelete = source },
                        )
                    }
                }
                item {
                    Text(
                        "SoufflyTV v0.5 · GPL v3 · aucune chaîne fournie",
                        style = MonoLabel,
                        color = Cm.TextMuted.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                    )
                }
            }
        }
    }

    toDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = Cm.SurfaceHigh,
            title = { Text("Supprimer « ${source.name} » ?") },
            text = {
                Text(
                    "Les identifiants, favoris et l'historique de cette source seront effacés de l'appareil.",
                    color = Cm.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.deleteSource(source)
                    toDelete = null
                }) { Text("Supprimer", color = Cm.Error) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun Header(showAdd: Boolean, onAdd: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Souffly", color = Cm.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("TV", color = Cm.Cyan, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Text("Lecteur IPTV libre", color = Cm.TextMuted, fontSize = 13.sp)
        }
        if (showAdd) {
            Button(onClick = onAdd) {
                Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ajouter")
            }
        }
        IconButton(onClick = onSettings) {
            Icon(AppIcons.Settings, contentDescription = "Réglages", tint = Cm.TextMuted)
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val shape = RoundedCornerShape(26.dp)
        Box(
            Modifier.size(104.dp).clip(shape).background(Cm.CyanGlow).border(1.dp, Cm.Cyan.copy(alpha = 0.5f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Tv, contentDescription = null, tint = Cm.Cyan, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Aucune source", color = Cm.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Ajoute une playlist M3U (lien ou fichier), un compte Xtream Codes\nou un portail Stalker (adresse MAC).",
            color = Cm.TextMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 440.dp),
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onAdd, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Ajouter une source")
        }
    }
}

@Composable
private fun RecentCard(name: String, logo: String?, onClick: () -> Unit) {
    Column(
        Modifier.width(104.dp).tvClickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChannelLogo(logo, name, 64.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            color = Cm.Text,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SourceCard(
    source: Source,
    modifier: Modifier,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Cm.Surface)
            .border(1.dp, Cm.Outline, shape)
            .tvClickable(shape = shape, onClick = onOpen)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(64.dp).background(Cm.Cyan))
        Spacer(Modifier.width(14.dp))
        TypeBadge(source.kindLabel)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical = 14.dp)) {
            Text(
                source.name,
                color = Cm.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(source.detail, color = Cm.TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onEdit) {
            Icon(AppIcons.Edit, contentDescription = "Modifier", tint = Cm.TextMuted)
        }
        IconButton(onClick = onDelete) {
            Icon(AppIcons.Delete, contentDescription = "Supprimer", tint = Cm.TextMuted)
        }
    }
}
