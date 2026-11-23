package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.data.ChannelGroup
import fr.bonobo.soufflytv.data.isReplayable
import fr.bonobo.soufflytv.data.replayOf
import fr.bonobo.soufflytv.model.Programme
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.platform.formatClock
import fr.bonobo.soufflytv.platform.formatDay
import fr.bonobo.soufflytv.platform.nowMillis
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.MonoLabel
import kotlinx.coroutines.CancellationException

/*
 * Replay (si le fournisseur garde des archives) : chaîne → jour → émission.
 * Grand écran : chaînes à gauche, jours et émissions à droite. Smartphone : chaînes, puis émissions.
 * Les versions d'une chaîne (SD / HD / FHD…) qui ont des archives sont regroupées ; au lancement,
 * choix de la qualité s'il y en a plusieurs.
 */

/** Ce qu'on relit pour une chaîne : la version qui garde le plus de jours, la meilleure à égalité. */
private fun ChannelGroup.replaySource() =
    variants.filter { it.channel.archiveDays > 0 }
        .maxWithOrNull(compareBy({ it.channel.archiveDays }, { it.rank }))

@Composable
fun ReplayPage(state: AppState, source: Source, model: BrowserModel, modifier: Modifier, compact: Boolean) {
    LaunchedEffect(Unit) { model.prepareReplay() }
    var chooser by remember { mutableStateOf<Pair<ChannelGroup, Programme>?>(null) }

    // Télécommande : le focus arrive sur la chaîne choisie (ou la première)
    val target = remember { FocusRequester() }
    var targetFocused by remember { mutableStateOf(false) }
    KeepFocus(target, model.contentFocusToken, model.replayGroups.isNotEmpty(), isFocused = { targetFocused })
    DisposableEffect(model) {
        model.enterContent = { runCatching { target.requestFocus() }.isSuccess }
        onDispose { model.enterContent = null }
    }

    fun play(group: ChannelGroup, programme: Programme) {
        val now = nowMillis()
        val archived = group.variants.filter { isReplayable(it.channel, programme, now) }
        when {
            archived.size > 1 -> chooser = group.copy(variants = archived) to programme
            archived.size == 1 -> state.play(source, listOf(archived.first().channel.replayOf(programme)), 0)
            else -> group.replaySource()?.let { state.play(source, listOf(it.channel.replayOf(programme)), 0) }
        }
    }

    val selectedGroup = model.replayGroups.firstOrNull { it.key == model.replayGroupKey }

    Column(modifier) {
        // En-tête
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (compact && selectedGroup != null) {
                IconButton(onClick = {
                    model.replayGroupKey = null
                    model.replayDay = null
                }) { Icon(AppIcons.Back, contentDescription = "Chaînes", tint = Cm.Text) }
            } else {
                Spacer(Modifier.width(8.dp))
                Icon(AppIcons.Replay, contentDescription = null, tint = Cm.Cyan, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (compact && selectedGroup != null) selectedGroup.name else "Replay",
                    color = Cm.Text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Les émissions passées que ton fournisseur garde en archive", color = Cm.TextMuted, fontSize = 13.sp, maxLines = 1)
            }
        }

        if (!compact || selectedGroup == null) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                items(model.replayCategories, key = { it.id }) { category ->
                    Pill(category.name, selected = model.replayCategory?.id == category.id) { model.selectReplayCategory(category) }
                }
            }
        }

        when {
            model.replayLoading && model.replayGroups.isEmpty() -> LoadingBox("Recherche des chaînes avec replay…")
            model.replayGroups.isEmpty() -> EmptyHint("Aucune chaîne avec replay chez ce fournisseur.")
            compact -> {
                if (selectedGroup == null) {
                    ReplayChannelList(model, Modifier.fillMaxSize(), target, onTargetFocus = { targetFocused = it })
                } else {
                    ReplayProgrammes(model, selectedGroup, Modifier.fillMaxSize(), onPlay = { p -> play(selectedGroup, p) })
                }
            }
            else -> Row(Modifier.fillMaxSize()) {
                ReplayChannelList(model, Modifier.width(300.dp).fillMaxHeight(), target, onTargetFocus = { targetFocused = it })
                Box(Modifier.width(1.dp).fillMaxHeight().background(Cm.Outline))
                if (selectedGroup != null) {
                    ReplayProgrammes(model, selectedGroup, Modifier.weight(1f).fillMaxHeight(), onPlay = { p -> play(selectedGroup, p) })
                } else {
                    EmptyHint("Choisis une chaîne à gauche.")
                }
            }
        }
    }

    chooser?.let { (group, programme) ->
        QualityChooser(
            group = group,
            preferred = state.settings.value.preferredQuality,
            subtitle = "Replay : ${programme.title}",
            onDismiss = { chooser = null },
            onChoose = { variant ->
                chooser = null
                state.settings.update { it.copy(preferredQuality = variant.quality) }
                state.play(source, listOf(variant.channel.replayOf(programme)), 0)
            },
        )
    }
}

/** Liste des chaînes qui ont des archives : logo, nom, nombre de jours. */
@Composable
private fun ReplayChannelList(model: BrowserModel, modifier: Modifier, target: FocusRequester, onTargetFocus: (Boolean) -> Unit) {
    val targetKey = model.replayGroupKey ?: model.replayGroups.firstOrNull()?.key
    LazyColumn(
        modifier,
        state = model.replayListState,
        contentPadding = PaddingValues(start = 12.dp, end = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(model.replayGroups, key = { it.key }) { group ->
            val isTarget = group.key == targetKey
            val days = group.replaySource()?.channel?.archiveDays ?: 0
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (isTarget) Modifier.focusRequester(target) else Modifier)
                    .onFocusChanged {
                        if (isTarget) onTargetFocus(it.isFocused)
                        // grand écran : la chaîne sous le curseur affiche ses émissions à droite
                        if (it.isFocused && model.wide && model.replayGroupKey != group.key) {
                            model.replayGroupKey = group.key
                            model.replayDay = null
                        }
                    }
                    .tvClickable(selected = model.replayGroupKey == group.key) {
                        model.replayGroupKey = group.key
                        model.replayDay = null
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelLogo(group.main.logo, group.name, size = 38.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, color = Cm.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "⟲ $days j" + if (group.variants.size > 1) " · " + group.variants.joinToString(" · ") { it.quality.substringBefore(' ') } else "",
                        style = MonoLabel,
                        color = Cm.Cyan,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Jours (Aujourd'hui, Hier, samedi 21…) puis émissions du jour choisi, les plus récentes en haut. */
@Composable
private fun ReplayProgrammes(model: BrowserModel, group: ChannelGroup, modifier: Modifier, onPlay: (Programme) -> Unit) {
    val replaySource = group.replaySource()
    var loading by remember(group.key) { mutableStateOf(true) }
    val programmes by produceState(initialValue = emptyList<Programme>(), group.key) {
        loading = true
        value = try {
            replaySource?.let { model.provider.replayProgrammes(it.channel) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
        loading = false
    }
    val now = nowMillis()
    // jours, du plus récent au plus ancien
    val byDay = remember(programmes) {
        programmes.sortedByDescending { it.start }.groupBy { formatDay(it.start) }
    }
    val day = model.replayDay?.takeIf { it in byDay } ?: byDay.keys.firstOrNull()

    Column(modifier) {
        if (byDay.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                items(byDay.keys.toList(), key = { it }) { label ->
                    Pill(label, selected = label == day) { model.replayDay = label }
                }
            }
        }
        val list = day?.let { byDay[it] }.orEmpty()
        when {
            loading -> LoadingBox("Chargement des émissions…")
            list.isEmpty() -> EmptyHint("Pas d'émission en replay pour cette chaîne pour le moment.")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(list, key = { _, p -> p.start }) { _, p ->
                    ReplayRow(p, now, onClick = { onPlay(p) })
                }
            }
        }
    }
}

@Composable
private fun ReplayRow(programme: Programme, now: Long, onClick: () -> Unit) {
    val onAir = programme.isOnAir(now)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Cm.Surface.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .border(1.dp, if (onAir) Cm.Cyan.copy(alpha = 0.6f) else Cm.Outline, RoundedCornerShape(10.dp))
            .tvClickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(74.dp)) {
            Text(formatClock(programme.start), color = Cm.Cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(formatClock(programme.stop), style = MonoLabel, color = Cm.TextMuted)
        }
        Column(Modifier.weight(1f)) {
            Text(programme.title, color = Cm.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            programme.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Cm.TextMuted, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (onAir) "⟲ Depuis le début" else "⟲ Revoir",
            color = Cm.Cyan,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
