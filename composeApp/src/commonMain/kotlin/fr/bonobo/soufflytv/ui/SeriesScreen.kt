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
import coil3.compose.AsyncImage
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
                val firstEpisodeItem = if (current.seasons.size > 1) 3 else 2 // en-tête, saisons, titre

                KeepFocus(
                    episodeFocus, series.id, seasonIndex,
                    isFocused = { episodeFocused },
                    prepare = { listState.scrollToItem((firstEpisodeItem + targetIndex - 1).coerceAtLeast(0)) },
                )

                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    item { SeriesHeader(current) }
                    if (current.seasons.size > 1) {
                        item {
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
                    item {
                        SectionLabel(
                            "SAISON ${season.number} · ${season.episodes.size} ÉPISODE(S)",
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    itemsIndexed(season.episodes) { i, episode ->
                        EpisodeRow(
                            episode,
                            i,
                            focusModifier = if (i == targetIndex) {
                                Modifier.focusRequester(episodeFocus).onFocusChanged { episodeFocused = it.isFocused }
                            } else Modifier,
                        ) {
                            state.focusMemory[seasonKey] = seasonIndex.toString()
                            state.focusMemory[episodeKey] = episode.id
                            state.play(source, season.episodes, i)
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
private fun EpisodeRow(episode: Channel, index: Int, focusModifier: Modifier, onClick: () -> Unit) {
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
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("ÉPISODE ${episode.number ?: (index + 1)}", style = MonoLabel, color = Cm.Cyan)
            Text(episode.name, color = Cm.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (episode.plot != null) {
                Text(episode.plot, color = Cm.TextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
