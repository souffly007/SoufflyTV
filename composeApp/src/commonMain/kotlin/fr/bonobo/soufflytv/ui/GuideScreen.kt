package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.data.ChannelGroup
import fr.bonobo.soufflytv.data.ChannelVariant
import fr.bonobo.soufflytv.data.HOUR_MS
import fr.bonobo.soufflytv.data.variantFor
import fr.bonobo.soufflytv.model.Programme
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.platform.formatClock
import fr.bonobo.soufflytv.platform.formatDay
import fr.bonobo.soufflytv.platform.nowMillis
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.MonoLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * Guide TV en grille (page à part) : une ligne par chaîne — ses versions SD / HD / FHD / UHD regroupées —,
 * les émissions sur une frise horaire, le détail de l'émission choisie en haut.
 * OK (ou toucher deux fois) sur une émission : la chaîne se lance, après le choix de la qualité
 * s'il en existe plusieurs.
 */

private const val HALF_HOUR = 30 * 60_000L
private const val MINUTE = 60_000L

private fun floorHalfHour(ms: Long) = (ms / HALF_HOUR) * HALF_HOUR

/** Horloge du guide, rafraîchie toutes les 30 s (ligne « maintenant », émissions en cours). */
@Composable
private fun rememberGuideClock(): Long {
    var clock by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            clock = nowMillis()
        }
    }
    return clock
}

/** Mesures de la grille : largeur d'une minute, colonne des chaînes, hauteur des lignes. */
private class GuideMetrics(
    val minuteWidth: Dp,
    val channelWidth: Dp,
    val rowHeight: Dp,
    val pxPerMinute: Float,
    /** Durée visible (ms) dans la largeur de la grille. */
    val spanMs: Long,
    val minStart: Long,
    val maxStart: Long,
) {
    fun x(time: Long, viewStart: Long): Float = (time - viewStart) / MINUTE.toFloat() * pxPerMinute
}

@Composable
fun GuidePage(state: AppState, source: Source, model: BrowserModel, modifier: Modifier, compact: Boolean) {
    val clock = rememberGuideClock()
    val minStart = floorHalfHour(clock) - 2 * HOUR_MS
    val maxStart = floorHalfHour(clock) + 30 * HOUR_MS
    var chooser by remember { mutableStateOf<Pair<ChannelGroup, Int>?>(null) }

    LaunchedEffect(Unit) {
        model.prepareGuide()
        if (model.guideViewStart == 0L || model.guideViewStart < minStart) model.guideViewStart = floorHalfHour(nowMillis())
    }

    // Télécommande : à l'arrivée (et au retour du lecteur) le focus va sur l'émission choisie
    val target = remember { FocusRequester() }
    var targetFocused by remember { mutableStateOf(false) }
    KeepFocus(target, model.contentFocusToken, model.guideGroups.isNotEmpty(), isFocused = { targetFocused })
    DisposableEffect(model) {
        model.enterContent = { runCatching { target.requestFocus() }.isSuccess }
        onDispose { model.enterContent = null }
    }

    /** Lance la chaîne : choix de la qualité d'abord s'il y a plusieurs versions. */
    fun play(group: ChannelGroup) {
        val index = model.guideGroups.indexOf(group).coerceAtLeast(0)
        if (group.variants.size > 1) {
            chooser = group to index
        } else {
            startGuidePlayback(state, source, model, group.variants.first(), index)
        }
    }

    Column(modifier) {
        GuideHeader(model, minStart, maxStart, compact)

        // Groupes de chaînes
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            items(model.guideCategories, key = { it.id }) { category ->
                Pill(category.name, selected = model.guideCategory?.id == category.id) { model.selectGuideCategory(category) }
            }
        }

        val selection = model.guideSelection
        val selectedGroup = selection?.let { s -> model.guideGroups.firstOrNull { it.key == s.groupKey } }
        GuideDetail(
            model = model,
            group = selectedGroup,
            start = selection?.start,
            clock = clock,
            compact = compact,
            onWatch = if (remoteNavigation()) null else selectedGroup?.let { g -> { play(g) } },
        )

        val density = LocalDensity.current
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val minuteWidth = if (compact) 3.dp else 5.dp
            val channelWidth = if (compact) 92.dp else 210.dp
            val pxPerMinute = with(density) { minuteWidth.toPx() }
            val gridWidthPx = (constraints.maxWidth - with(density) { channelWidth.toPx() }).coerceAtLeast(1f)
            val metrics = GuideMetrics(
                minuteWidth = minuteWidth,
                channelWidth = channelWidth,
                rowHeight = if (compact) 58.dp else 64.dp,
                pxPerMinute = pxPerMinute,
                spanMs = (gridWidthPx / pxPerMinute * MINUTE).toLong(),
                minStart = minStart,
                maxStart = maxStart,
            )
            // Au doigt ou à la souris : on fait glisser la frise horaire
            val drag = rememberDraggableState { delta ->
                val shift = (delta / pxPerMinute * MINUTE).toLong()
                model.guideViewStart = (model.guideViewStart - shift).coerceIn(minStart, maxStart)
            }
            Column(Modifier.fillMaxSize().draggable(drag, Orientation.Horizontal)) {
                Row(Modifier.fillMaxWidth().height(26.dp)) {
                    Spacer(Modifier.width(channelWidth))
                    TimeRuler(model, metrics, Modifier.weight(1f).fillMaxHeight())
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Cm.Cyan.copy(alpha = 0.35f)))
                val error = model.guideError
                when {
                    model.guideGroups.isEmpty() && model.guideLoading -> LoadingBox("Chargement du guide…")
                    error != null && model.guideGroups.isEmpty() -> EmptyHint(error)
                    model.guideGroups.isEmpty() -> EmptyHint(
                        if (model.guideCategory?.id == FAVORITES) "Aucune chaîne en favori : choisis un autre groupe en haut."
                        else "Aucune chaîne dans ce groupe."
                    )
                    else -> LazyColumn(state = model.guideListState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(model.guideGroups, key = { _, g -> g.key }) { index, group ->
                            GuideRow(
                                model = model,
                                group = group,
                                rowIndex = index,
                                metrics = metrics,
                                clock = clock,
                                target = target,
                                onTargetFocus = { targetFocused = it },
                                onPlay = { play(group) },
                            )
                        }
                    }
                }
            }
            NowLine(model, metrics, clock, with(density) { channelWidth.toPx() }.roundToInt(), gridWidthPx)
        }
    }

    chooser?.let { (group, index) ->
        QualityChooser(
            group = group,
            preferred = state.settings.value.preferredQuality,
            onDismiss = { chooser = null },
            onChoose = { variant ->
                chooser = null
                state.settings.update { it.copy(preferredQuality = variant.quality) }
                startGuidePlayback(state, source, model, variant, index)
            },
        )
    }
}

/**
 * Lecture depuis le guide : la liste du lecteur (zapping CH+ / CH−) reprend les chaînes du guide,
 * chacune dans la qualité choisie quand elle existe.
 */
private fun startGuidePlayback(state: AppState, source: Source, model: BrowserModel, variant: ChannelVariant, index: Int) {
    val groups = model.guideGroups
    val list = groups.mapIndexed { i, g -> if (i == index) variant.channel else g.variantFor(variant.quality).channel }
    state.play(source, list, index.coerceIn(0, (list.size - 1).coerceAtLeast(0)))
}

// ---------- En-tête : titre, jour, ‹ 1 h · Maintenant · 1 h › ----------

@Composable
private fun GuideHeader(model: BrowserModel, minStart: Long, maxStart: Long, compact: Boolean) {
    fun shift(ms: Long) {
        model.guideViewStart = (model.guideViewStart + ms).coerceIn(minStart, maxStart)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Guide, contentDescription = null, tint = Cm.Cyan, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Guide TV", color = Cm.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val day = model.guideViewStart.takeIf { it > 0 } ?: nowMillis()
            Text(formatDay(day), color = Cm.TextMuted, fontSize = 13.sp)
        }
        val small = Modifier.height(36.dp)
        OutlinedButton(onClick = { shift(-HOUR_MS) }, modifier = small, contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text(if (compact) "‹" else "‹ 1 h", color = Cm.Text)
        }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = { model.guideViewStart = floorHalfHour(nowMillis()) },
            modifier = small,
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) { Text("Maintenant", color = Cm.Cyan) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { shift(HOUR_MS) }, modifier = small, contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text(if (compact) "›" else "1 h ›", color = Cm.Text)
        }
    }
}

// ---------- Détail de l'émission choisie ----------

@Composable
private fun GuideDetail(
    model: BrowserModel,
    group: ChannelGroup?,
    start: Long?,
    clock: Long,
    compact: Boolean,
    onWatch: (() -> Unit)?,
) {
    val height = if (compact) 104.dp else 128.dp
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(Cm.Surface.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .border(1.dp, Cm.Outline, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (group == null) {
            Text(
                "Choisis une émission dans la grille : son résumé s'affiche ici. " +
                    if (remoteNavigation()) "OK pour regarder la chaîne." else "Touche-la une seconde fois pour regarder la chaîne.",
                color = Cm.TextMuted,
                fontSize = 14.sp,
            )
        } else {
            GuideDetailContent(model, group, start, clock, compact, onWatch)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.GuideDetailContent(
    model: BrowserModel,
    group: ChannelGroup,
    start: Long?,
    clock: Long,
    compact: Boolean,
    onWatch: (() -> Unit)?,
) {
    run {
        val programmes by produceState(initialValue = emptyList<Programme>(), group.key, model.epgVersion) {
            value = loadProgrammes(model, group)
        }
        val programme = start?.let { s -> programmes.firstOrNull { it.start == s } }
            ?: programmes.firstOrNull { it.isOnAir(clock) }
        ChannelLogo(group.main.logo, group.name, size = if (compact) 44.dp else 60.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                group.name + if (group.variants.size > 1) "  ·  " + group.variants.joinToString(" / ") { it.quality } else "",
                style = MonoLabel,
                color = Cm.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (programme == null) {
                Text("Pas d'information de programme pour cette chaîne", color = Cm.TextMuted, fontSize = 14.sp)
            } else {
                Text(
                    programme.title,
                    color = Cm.Text,
                    fontSize = if (compact) 16.sp else 19.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(programmeTiming(programme, clock), color = Cm.TextMuted, fontSize = 13.sp, maxLines = 1)
                programme.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = Cm.Text.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (onWatch != null) {
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = onWatch,
                colors = ButtonDefaults.buttonColors(containerColor = Cm.Cyan, contentColor = Cm.OnCyan),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                if (!compact) {
                    Spacer(Modifier.width(6.dp))
                    Text("Regarder", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** « 20:55 – 22:40 · 1 h 45 · en cours, reste 35 min » / « dans 2 h 10 » / « terminé ». */
internal fun programmeTiming(p: Programme, now: Long): String {
    fun duration(ms: Long): String {
        val m = (ms / MINUTE).coerceAtLeast(0)
        return if (m >= 60) "${m / 60} h ${(m % 60).toString().padStart(2, '0')}" else "$m min"
    }
    val status = when {
        p.isOnAir(now) -> "en cours, reste ${duration(p.stop - now)}"
        p.start > now -> "dans ${duration(p.start - now)}"
        else -> "terminé"
    }
    return "${formatClock(p.start)} – ${formatClock(p.stop)} · ${duration(p.stop - p.start)} · $status"
}

/** Émissions d'une ligne : première version qui a un guide (instantané avec un guide complet). */
private suspend fun loadProgrammes(model: BrowserModel, group: ChannelGroup): List<Programme> {
    for (variant in group.variants.sortedByDescending { it.rank }.take(3)) {
        val list = try {
            model.provider.epg(variant.channel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
        if (list.isNotEmpty()) return list.sortedBy { it.start }
    }
    return emptyList()
}

// ---------- Frise horaire et ligne « maintenant » ----------

@Composable
private fun TimeRuler(model: BrowserModel, metrics: GuideMetrics, modifier: Modifier) {
    val marks = remember(metrics.minStart, metrics.maxStart, metrics.spanMs) {
        val end = metrics.maxStart + metrics.spanMs + HALF_HOUR
        generateSequence(metrics.minStart) { it + HALF_HOUR }.takeWhile { it <= end }.toList()
    }
    Layout(
        modifier = modifier.clipToBounds(),
        content = {
            marks.forEach { mark ->
                val label = formatClock(mark)
                Text(
                    if (label == "00:00") formatDay(mark) else label,
                    style = MonoLabel,
                    color = if (label == "00:00") Cm.Cyan else Cm.TextMuted,
                    maxLines = 1,
                )
            }
        },
    ) { measurables, constraints ->
        val viewStart = model.guideViewStart
        val placeables = measurables.map { it.measure(Constraints()) }
        val height = constraints.maxHeight
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { i, p ->
                val x = metrics.x(marks[i], viewStart).roundToInt()
                if (x > -p.width && x < constraints.maxWidth) p.place(x + 6, (height - p.height) / 2)
            }
        }
    }
}

@Composable
private fun NowLine(model: BrowserModel, metrics: GuideMetrics, clock: Long, offsetPx: Int, gridWidthPx: Float) {
    val x = metrics.x(clock, model.guideViewStart)
    if (x < 0f || x > gridWidthPx) return
    Box(
        Modifier
            .offset { IntOffset(offsetPx + x.roundToInt(), 0) }
            .width(2.dp)
            .fillMaxHeight()
            .background(Cm.Cyan.copy(alpha = 0.9f)),
    )
}

// ---------- Une ligne : la chaîne, puis ses émissions ----------

@Composable
private fun GuideRow(
    model: BrowserModel,
    group: ChannelGroup,
    rowIndex: Int,
    metrics: GuideMetrics,
    clock: Long,
    target: FocusRequester,
    onTargetFocus: (Boolean) -> Unit,
    onPlay: () -> Unit,
) {
    val programmes by produceState(initialValue = emptyList<Programme>(), group.key, model.epgVersion) {
        value = loadProgrammes(model, group)
    }
    val visible = remember(programmes, metrics.minStart) {
        programmes.filter { it.stop > metrics.minStart && it.start < metrics.maxStart + metrics.spanMs }
    }
    val requesters = remember(visible) { visible.map { FocusRequester() } }
    val selection = model.guideSelection
    val keyNavigation = remoteNavigation()

    // Cible du focus (arrivée sur la page, retour du lecteur) : l'émission choisie, sinon celle en cours de la 1re ligne
    val targetIndex: Int? = when {
        selection != null && selection.groupKey == group.key && selection.start != null ->
            visible.indexOfFirst { it.start == selection.start }.takeIf { it >= 0 }
                ?: visible.indexOfFirst { it.isOnAir(clock) }.takeIf { it >= 0 }
        selection == null && rowIndex == 0 && visible.isNotEmpty() -> visible.indexOfFirst { it.isOnAir(clock) }.takeIf { it >= 0 } ?: 0
        else -> null
    }
    val channelIsTarget = (selection != null && selection.groupKey == group.key && selection.start == null) ||
        (targetIndex == null && selection == null && rowIndex == 0)

    /** Fait défiler la frise pour que l'émission soit bien visible. */
    fun reveal(p: Programme) {
        val viewStart = model.guideViewStart
        val newStart = when {
            p.start > viewStart + metrics.spanMs - HALF_HOUR -> floorHalfHour(p.start) - HALF_HOUR
            p.stop < viewStart + HALF_HOUR -> floorHalfHour(p.start)
            else -> return
        }
        model.guideViewStart = newStart.coerceIn(metrics.minStart, metrics.maxStart)
    }

    Row(Modifier.fillMaxWidth().height(metrics.rowHeight)) {
        // Colonne de la chaîne : logo, nom, qualités disponibles
        Row(
            Modifier
                .width(metrics.channelWidth)
                .fillMaxHeight()
                .padding(end = 2.dp, bottom = 2.dp)
                .then(if (channelIsTarget) Modifier.focusRequester(target) else Modifier)
                .onFocusChanged {
                    if (channelIsTarget) onTargetFocus(it.isFocused)
                    if (it.isFocused) model.guideSelection = GuideSelection(group.key, null)
                }
                .tvClickable(
                    selected = selection != null && selection.groupKey == group.key && selection.start == null,
                    shape = RoundedCornerShape(6.dp),
                    onClick = onPlay,
                )
                .background(Cm.Surface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(group.main.logo, group.name, size = if (metrics.channelWidth < 120.dp) 34.dp else 40.dp)
            if (metrics.channelWidth >= 120.dp) {
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, color = Cm.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (group.variants.size > 1) {
                        Text(
                            group.variants.joinToString(" · ") { it.quality.substringBefore(' ') }.let { if (it.length > 28) "${group.variants.size} versions" else it },
                            style = MonoLabel,
                            color = Cm.Cyan,
                            maxLines = 1,
                        )
                    }
                }
            } else {
                Spacer(Modifier.width(6.dp))
                Text(group.name, color = Cm.Text, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
            }
        }

        // Émissions placées sur la frise (seule la mise en page suit le défilement)
        Layout(
            modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds(),
            content = {
                if (visible.isEmpty()) {
                    Box(
                        Modifier
                            .padding(end = 2.dp, bottom = 2.dp)
                            .background(Cm.Surface.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .tvClickable(shape = RoundedCornerShape(6.dp), onClick = onPlay)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("Pas de programme", color = Cm.TextMuted, fontSize = 13.sp, maxLines = 1)
                    }
                }
                visible.forEachIndexed { i, p ->
                    val isTarget = targetIndex == i
                    ProgrammeCell(
                        programme = p,
                        clock = clock,
                        selected = selection != null && selection.groupKey == group.key && selection.start == p.start,
                        textShift = {
                            ((model.guideViewStart - p.start) / MINUTE.toFloat() * metrics.pxPerMinute)
                                .roundToInt().coerceAtLeast(0)
                        },
                        modifier = Modifier
                            .focusRequester(requesters[i])
                            .then(if (isTarget) Modifier.focusRequester(target) else Modifier)
                            .onFocusChanged {
                                if (isTarget) onTargetFocus(it.isFocused)
                                if (it.isFocused) {
                                    model.guideSelection = GuideSelection(group.key, p.start)
                                    reveal(p)
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    // ◀ ▶ : émission précédente / suivante de la même chaîne (même hors de l'écran)
                                    Key.DirectionRight -> {
                                        if (i < visible.lastIndex) {
                                            reveal(visible[i + 1])
                                            runCatching { requesters[i + 1].requestFocus() }
                                        }
                                        true
                                    }
                                    Key.DirectionLeft -> {
                                        if (i > 0) {
                                            reveal(visible[i - 1])
                                            runCatching { requesters[i - 1].requestFocus() }
                                            true
                                        } else {
                                            false // vers la colonne de la chaîne
                                        }
                                    }
                                    else -> false
                                }
                            },
                        onClick = {
                            val already = selection != null && selection.groupKey == group.key && selection.start == p.start
                            // Télécommande : OK lance ; au doigt / à la souris : 1er appui = choisir, 2e = regarder
                            if (keyNavigation || already) onPlay() else model.guideSelection = GuideSelection(group.key, p.start)
                        },
                    )
                }
            },
        ) { measurables, constraints ->
            val viewStart = model.guideViewStart
            val height = constraints.maxHeight
            if (visible.isEmpty()) {
                val cell = measurables.first().measure(Constraints.fixed(constraints.maxWidth, height))
                return@Layout layout(constraints.maxWidth, height) { cell.place(0, 0) }
            }
            val placeables = measurables.mapIndexed { i, m ->
                val p = visible[i]
                val width = ((p.stop - p.start) / MINUTE.toFloat() * metrics.pxPerMinute)
                    .roundToInt().coerceIn(4, 200_000)
                m.measure(Constraints.fixed(width, height))
            }
            layout(constraints.maxWidth, height) {
                placeables.forEachIndexed { i, placeable ->
                    placeable.place(metrics.x(visible[i].start, viewStart).roundToInt(), 0)
                }
            }
        }
    }
}

@Composable
private fun ProgrammeCell(
    programme: Programme,
    clock: Long,
    selected: Boolean,
    textShift: () -> Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val onAir = programme.isOnAir(clock)
    val past = programme.stop <= clock
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .padding(end = 2.dp, bottom = 2.dp)
            .background(
                when {
                    onAir -> Cm.SurfaceHigher
                    past -> Cm.Surface.copy(alpha = 0.45f)
                    else -> Cm.Surface
                },
                shape,
            )
            .then(if (selected) Modifier.border(2.dp, Cm.Cyan, shape) else Modifier)
            .tvClickable(shape = shape, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Le titre reste lisible au bord gauche quand l'émission a commencé avant le début de la frise
        Column(Modifier.offset { IntOffset(textShift(), 0) }.padding(horizontal = 8.dp)) {
            Text(
                programme.title,
                color = if (past) Cm.TextMuted else Cm.Text,
                fontSize = 13.sp,
                fontWeight = if (onAir) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatClock(programme.start)} – ${formatClock(programme.stop)}",
                style = MonoLabel,
                color = if (onAir) Cm.Cyan else Cm.TextMuted,
                maxLines = 1,
            )
        }
        if (onAir) {
            // petite barre de progression en bas de l'émission en cours
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(programme.progress(clock)).height(3.dp).background(Cm.Cyan))
        }
    }
}

// ---------- Choix de la qualité ----------

@Composable
private fun QualityChooser(
    group: ChannelGroup,
    preferred: String,
    onDismiss: () -> Unit,
    onChoose: (ChannelVariant) -> Unit,
) {
    val first = remember(group) { FocusRequester() }
    val start = group.variantFor(preferred.ifEmpty { null })
    LaunchedEffect(group) {
        repeat(10) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(360.dp)
                .background(Cm.Surface, RoundedCornerShape(16.dp))
                .border(1.dp, Cm.Cyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(group.main.logo, group.name, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(group.name, color = Cm.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Choisis la qualité", color = Cm.TextMuted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            group.variants.forEach { variant ->
                val isStart = variant == start
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .then(if (isStart) Modifier.focusRequester(first) else Modifier)
                        .tvClickable(selected = isStart, onClick = { onChoose(variant) })
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        variant.quality,
                        color = if (isStart) Cm.Cyan else Cm.Text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(120.dp),
                        maxLines = 1,
                    )
                    Text(variant.channel.name, color = Cm.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (preferred.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("En surbrillance : ta dernière qualité choisie", color = Cm.TextMuted, fontSize = 11.sp)
            }
        }
    }
}
