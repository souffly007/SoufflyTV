package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxHeight
import fr.bonobo.soufflytv.data.formatDuration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import coil3.compose.AsyncImage
import fr.bonobo.soufflytv.platform.rememberUrlOpener
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.data.toUserMessage
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.SeriesInfo
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.MonoLabel
import kotlinx.coroutines.CancellationException

@Composable
fun SeriesScreen(state: AppState, source: Source, series: Channel) {
    val provider = remember(source) { state.provider(source) }
    var info by remember(series.id) { mutableStateOf<SeriesInfo?>(null) }
    var error by remember(series.id) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    // Saison et épisode mémorisés : au retour du lecteur on retrouve sa place
    val seasonKey = "season:${source.id}|${series.id}"
    val episodeKey = "episode:${source.id}|${series.id}"
    var seasonIndex by remember(series.id) { mutableIntStateOf(state.focusMemory[seasonKey]?.toIntOrNull() ?: 0) }
    val listState = rememberLazyListState()
    val episodeFocus = remember { FocusRequester() }
    var episodeFocused by remember { mutableStateOf(false) }
    val favorite = state.library.isFavorite(source.id, series.id)

    LaunchedEffect(series.id, retry) {
        error = null
        try {
            info = provider.seriesInfo(series)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.toUserMessage()
        }
    }

    Box(Modifier.fillMaxSize().background(Cm.Background)) {
        info?.let { DetailBackdrop(it.backdrop ?: it.cover) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Grand écran : infos et boutons à gauche, saisons et épisodes à droite (style des maquettes)
    val wide = maxWidth >= 840.dp
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = series.name,
            subtitle = "SÉRIE",
            onBack = { state.back() },
            actions = {
                IconButton(onClick = { state.library.toggleFavorite(source.id, series) }) {
                    Icon(
                        if (favorite) AppIcons.Star else AppIcons.StarBorder,
                        contentDescription = if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
                        tint = if (favorite) Cm.Cyan else Cm.TextMuted,
                    )
                }
            },
        )

        val current = info
        val err = error
        when {
            err != null -> ErrorBox(err, onRetry = { retry++ })
            current == null -> LoadingBox("Chargement des épisodes…")
            else -> {
                val season = current.seasons[seasonIndex.coerceIn(0, current.seasons.lastIndex)]
                val rememberedId = state.focusMemory[episodeKey]
                val targetIndex = season.episodes.indexOfFirst { it.id == rememberedId }.coerceAtLeast(0)
                // index de la première ligne d'épisode dans la liste
                val firstEpisodeItem = when {
                    wide -> 0
                    current.seasons.size > 1 -> 3 // en-tête, saisons, titre
                    else -> 2
                }

                // Où en est-on dans la série : épisode commencé, ou suivant du dernier vu
                val resume = resumeTarget(current, source.id, state)
                fun playEpisode(target: ResumeTarget, fromStart: Boolean) {
                    val targetSeason = current.seasons[target.seasonIndex]
                    seasonIndex = target.seasonIndex
                    state.focusMemory[seasonKey] = target.seasonIndex.toString()
                    state.focusMemory[episodeKey] = target.episode.id
                    if (fromStart) state.library.setPosition("${source.id}|${target.episode.id}", 0)
                    state.play(source, withCover(targetSeason.episodes, current.cover), target.episodeIndex)
                }

                // Première arrivée sur la série : le focus va sur « Reprendre » ;
                // au retour du lecteur : sur l'épisode que l'on regardait
                val resumeFocus = remember { FocusRequester() }
                var resumeFocused by remember { mutableStateOf(false) }
                if (rememberedId == null) {
                    KeepFocus(resumeFocus, series.id, isFocused = { resumeFocused }, prepare = { if (!wide) listState.scrollToItem(0) })
                } else {
                    KeepFocus(
                        episodeFocus, series.id, seasonIndex,
                        isFocused = { episodeFocused },
                        prepare = { listState.scrollToItem((firstEpisodeItem + targetIndex - 1).coerceAtLeast(0)) },
                    )
                }

                val resumeButtons: @Composable (Boolean) -> Unit = { stacked ->
                    ResumeButtons(
                        current,
                        resume,
                        stacked = stacked,
                        favorite = favorite,
                        onToggleFavorite = { state.library.toggleFavorite(source.id, series) },
                        focusModifier = Modifier.focusRequester(resumeFocus).onFocusChanged { resumeFocused = it.isFocused },
                        onPlay = { target, fromStart -> playEpisode(target, fromStart) },
                    )
                }
                val seasonTabs: @Composable () -> Unit = {
                    if (current.seasons.size > 1) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(current.seasons) { i, s ->
                                Pill("Saison ${s.number}", selected = i == seasonIndex) {
                                    seasonIndex = i
                                    state.focusMemory[seasonKey] = i.toString()
                                }
                            }
                        }
                    }
                }
                val seasonTitle: @Composable () -> Unit = {
                    SectionLabel(
                        "SAISON ${season.number} · ${season.episodes.size} ÉPISODE(S)",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                val episodeRow: @Composable (Int, Channel) -> Unit = { i, episode ->
                    val key = "${source.id}|${episode.id}"
                    EpisodeRow(
                        episode,
                        i,
                        progress = state.library.position(key)?.takeIf { it > 30_000 }?.let { state.library.progress(key) },
                        watched = state.library.isWatched(key),
                        focusModifier = if (i == targetIndex) {
                            Modifier.focusRequester(episodeFocus).onFocusChanged { episodeFocused = it.isFocused }
                        } else Modifier,
                    ) {
                        state.focusMemory[seasonKey] = seasonIndex.toString()
                        state.focusMemory[episodeKey] = episode.id
                        state.play(source, withCover(season.episodes, current.cover), i)
                    }
                }

                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .width(380.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                        ) {
                            DetailPoster(current.cover, 150.dp, AppIcons.Library)
                            Spacer(Modifier.height(14.dp))
                            Text(current.name, color = Cm.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp)
                            Spacer(Modifier.height(8.dp))
                            MetaChips(current.meta)
                            current.plot?.let {
                                Spacer(Modifier.height(10.dp))
                                Text(it, color = Cm.Text.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 19.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(14.dp))
                            resumeButtons(true)
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Cm.Outline))
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            seasonTabs()
                            seasonTitle()
                            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                                itemsIndexed(season.episodes) { i, episode -> episodeRow(i, episode) }
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                        item {
                            SeriesHeader(current)
                            resumeButtons(false)
                        }
                        if (current.seasons.size > 1) item { seasonTabs() }
                        item { seasonTitle() }
                        itemsIndexed(season.episodes) { i, episode -> episodeRow(i, episode) }
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
private fun SeriesHeader(info: SeriesInfo) {
    Row(Modifier.fillMaxWidth().padding(16.dp)) {
        Box(
            Modifier.width(110.dp).height(165.dp).clip(RoundedCornerShape(10.dp)).background(Cm.SurfaceHigher),
        ) {
            if (info.cover != null) {
                AsyncImage(
                    model = info.cover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(info.name, color = Cm.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (info.meta != null) {
                Spacer(Modifier.height(4.dp))
                Text(info.meta, style = MonoLabel, color = Cm.Cyan)
            }
            if (info.plot != null) {
                Spacer(Modifier.height(8.dp))
                Text(info.plot, color = Cm.TextMuted, fontSize = 13.sp, maxLines = 7, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Channel,
    index: Int,
    progress: Float?,
    watched: Boolean,
    focusModifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .then(focusModifier)
            .tvClickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(8.dp)).background(Cm.SurfaceHigher),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Play, contentDescription = null, tint = Cm.Cyan.copy(alpha = 0.6f), modifier = Modifier.size(28.dp))
            if (episode.logo != null) {
                AsyncImage(
                    model = episode.logo,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            // Épisode commencé : barre de progression en bas de la vignette
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Cm.Cyan))
                }
            }
            // Épisode vu jusqu'au bout
            if (watched && progress == null) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(Cm.Success, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ÉPISODE ${episode.number ?: (index + 1)}", style = MonoLabel, color = Cm.Cyan)
                when {
                    progress != null -> Text("  · EN COURS", style = MonoLabel, color = Cm.TextMuted)
                    watched -> Text("  · VU", style = MonoLabel, color = Cm.Success)
                }
            }
            Text(episode.name, color = Cm.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (episode.plot != null) {
                Text(episode.plot, color = Cm.TextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Où reprendre une série : épisode commencé ([positionMs]), ou suivant du dernier vu (positionMs = null). */
private data class ResumeTarget(
    val seasonIndex: Int,
    val episodeIndex: Int,
    val seasonNumber: Int,
    val episode: Channel,
    val positionMs: Long?,
)

/** Dernier épisode regardé : s'il est commencé on le reprend, s'il est fini on passe au suivant. */
private fun resumeTarget(info: SeriesInfo, sourceId: String, state: AppState): ResumeTarget? {
    val all = info.seasons.flatMapIndexed { si, season ->
        season.episodes.mapIndexed { ei, episode -> ResumeTarget(si, ei, season.number, episode, null) }
    }
    val last = all.maxByOrNull { state.library.lastPlayed("$sourceId|${it.episode.id}") ?: -1L } ?: return null
    val key = "$sourceId|${last.episode.id}"
    if (state.library.lastPlayed(key) == null) return null
    val position = state.library.position(key)?.takeIf { it > 30_000 }
    if (position != null) return last.copy(positionMs = position)
    // fini : épisode suivant (null en fin de série)
    return all.getOrNull(all.indexOf(last) + 1)
}

private fun ResumeTarget.label(): String = "S${seasonNumber} · Épisode ${episode.number ?: (episodeIndex + 1)}"

/** Sous l'image de la série, au-dessus des épisodes : « Reprendre », « Épisode suivant » ou « Lire ». */
@Composable
private fun ResumeButtons(
    info: SeriesInfo,
    resume: ResumeTarget?,
    stacked: Boolean,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    focusModifier: Modifier,
    onPlay: (ResumeTarget, Boolean) -> Unit,
) {
    val first = info.seasons.firstOrNull()?.episodes?.firstOrNull()?.let {
        ResumeTarget(0, 0, info.seasons.first().number, it, null)
    }
    val target = resume ?: first ?: return
    val resumePosition = resume?.positionMs
    val openUrl = rememberUrlOpener()
    var trailerError by remember { mutableStateOf(false) }
    val mainLabel = when {
        resumePosition != null -> "Reprendre l'épisode (${formatDuration(resumePosition)})"
        resume != null -> "Épisode suivant"
        else -> "Lire ${target.label()}"
    }
    if (stacked) {
        // Grand écran : boutons empilés, comme sur la fiche film
        val width = Modifier.fillMaxWidth()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (resume != null) {
                Text(
                    if (resumePosition != null) "EN COURS · ${resume.label()}" else "À SUIVRE · ${resume.label()}",
                    style = MonoLabel,
                    color = Cm.Cyan,
                )
                Text(resume.episode.name, color = Cm.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DetailPrimaryButton(mainLabel, AppIcons.Play, width.then(focusModifier)) { onPlay(target, false) }
            if (resume != null && resumePosition != null) {
                DetailButton("↺  Reprendre l'épisode du début", width) { onPlay(resume, true) }
            }
            if (resume != null && first != null) {
                DetailButton("Voir la série depuis le début", width) { onPlay(first, true) }
            }
            DetailButton(
                if (favorite) "Dans ma liste" else "Ajouter à ma liste",
                width,
                icon = if (favorite) AppIcons.Star else AppIcons.StarBorder,
                tint = if (favorite) Cm.Cyan else Cm.Text,
                onClick = onToggleFavorite,
            )
            info.trailer?.let { trailer ->
                DetailButton("Bande-annonce", width, icon = AppIcons.Play) { trailerError = !openUrl(trailer) }
            }
            if (trailerError) {
                Text(
                    "Impossible d'ouvrir la bande-annonce : aucune application YouTube ni navigateur sur cet appareil",
                    color = Cm.Error,
                    fontSize = 13.sp,
                )
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        if (resume != null) {
            Text(
                if (resumePosition != null) "EN COURS · ${resume.label()}" else "À SUIVRE · ${resume.label()}",
                style = MonoLabel,
                color = Cm.Cyan,
            )
            Text(resume.episode.name, color = Cm.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onPlay(target, false) },
                colors = ButtonDefaults.buttonColors(containerColor = Cm.Cyan, contentColor = Cm.OnCyan),
                modifier = focusModifier,
            ) {
                Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        resumePosition != null -> "Reprendre (${formatDuration(resumePosition)})"
                        resume != null -> "Épisode suivant"
                        else -> "Lire ${target.label()}"
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
            if (resume != null && resumePosition != null) {
                OutlinedButton(onClick = { onPlay(resume, true) }) { Text("↺ Reprendre du début") }
            }
        }
        // Bande-annonce sur sa propre ligne : la place manque à côté des autres boutons sur téléphone
        info.trailer?.let { trailer ->
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { trailerError = !openUrl(trailer) }) { Text("▶ Bande-annonce") }
        }
        if (trailerError) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Impossible d'ouvrir la bande-annonce : aucune application YouTube ni navigateur sur cet appareil",
                color = Cm.Error,
                fontSize = 13.sp,
            )
        }
    }
}

/** Épisodes sans vignette : l'affiche de la série (pour « Continuer à regarder » sur l'accueil). */
private fun withCover(episodes: List<Channel>, cover: String?): List<Channel> =
    if (cover == null) episodes else episodes.map { if (it.logo == null) it.copy(logo = cover) else it }
