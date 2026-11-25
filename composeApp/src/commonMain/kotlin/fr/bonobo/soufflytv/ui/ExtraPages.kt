package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.ItemType
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.platform.formatClock
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.MonoLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/*
 * Pages en plus de l'accueil : Ma liste, Historique et Recherche (smartphone, tablette, TV et PC).
 */

@Composable
fun ExtraPageContent(state: AppState, source: Source, model: BrowserModel, page: ExtraPage, modifier: Modifier, compact: Boolean) {
    when (page) {
        ExtraPage.MY_LIST -> MyListPage(state, source, model, modifier, compact)
        ExtraPage.HISTORY -> HistoryPage(state, source, model, modifier)
        ExtraPage.SEARCH -> SearchPage(state, source, model, modifier, compact)
        ExtraPage.GUIDE -> GuidePage(state, source, model, modifier, compact)
        ExtraPage.REPLAY -> ReplayPage(state, source, model, modifier, compact)
    }
}

/** Titre de page : « ▌Ma liste » + petite ligne d'explication. */
@Composable
private fun PageTitle(icon: ImageVector, title: String, subtitle: String?) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Cm.Cyan, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Cm.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = Cm.TextMuted, fontSize = 13.sp)
        }
    }
}

/**
 * Télécommande : à l'ouverture de la page (et au retour d'une fiche), le focus va sur le premier élément ;
 * ▶ depuis la barre latérale y revient aussi.
 */
@Composable
private fun rememberPageFocus(model: BrowserModel, vararg keys: Any?): Pair<FocusRequester, Modifier> {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    KeepFocus(requester, model.contentFocusToken, *keys, isFocused = { focused })
    DisposableEffect(model) {
        model.enterContent = { runCatching { requester.requestFocus() }.isSuccess }
        onDispose { model.enterContent = null }
    }
    return requester to Modifier.focusRequester(requester).onFocusChanged { focused = it.isFocused }
}

// ---------- Ma liste ----------

@Composable
private fun MyListPage(state: AppState, source: Source, model: BrowserModel, modifier: Modifier, compact: Boolean) {
    val favorites = state.library.favorites.filter { it.sourceId == source.id }.map { it.channel }
    val live = favorites.filter { it.type == ItemType.LIVE }
    val vod = favorites.filter { it.type != ItemType.LIVE }
    val (_, firstFocus) = rememberPageFocus(model, favorites.isEmpty())

    Column(modifier) {
        PageTitle(AppIcons.Star, "Ma liste", "Appui long sur une affiche ou une chaîne pour l'ajouter ou la retirer")
        if (favorites.isEmpty()) {
            EmptyHint("Ta liste est vide.\nAjoute des chaînes, des films ou des séries avec l'étoile ou un appui long.")
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (compact) 104.dp else 140.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        ) {
            if (live.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    HomeRow("Chaînes (${live.size})") {
                        live.forEachIndexed { i, item ->
                            LiveCard(item, if (i == 0) firstFocus else Modifier) { state.play(source, live, i) }
                        }
                    }
                }
            }
            if (vod.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "FILMS ET SÉRIES (${vod.size})",
                        style = MonoLabel,
                        color = Cm.Cyan,
                        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                itemsIndexed(vod, key = { _, c -> "v|${c.id}" }) { i, item ->
                    PosterCard(
                        item,
                        favorite = true,
                        focusModifier = if (live.isEmpty() && i == 0) firstFocus else Modifier,
                        onClick = { state.openItem(source, vod, i) },
                        onLongClick = { model.toggleFavorite(item) },
                    )
                }
            }
        }
    }
}

// ---------- Historique ----------

@Composable
private fun HistoryPage(state: AppState, source: Source, model: BrowserModel, modifier: Modifier) {
    val library = state.library
    // les séries plutôt que chaque épisode vu (la série s'ouvre sur l'épisode où l'on s'est arrêté)
    val entries = library.recents.filter {
        it.sourceId == source.id && (model.sections.size == 1 || it.channel.type != ItemType.EPISODE)
    }
    val (_, firstFocus) = rememberPageFocus(model, entries.isEmpty())

    Column(modifier) {
        PageTitle(AppIcons.History, "Historique", "Ce que tu as regardé récemment, du plus récent au plus ancien")
        if (entries.isEmpty()) {
            EmptyHint("Rien pour l'instant : les chaînes, films et épisodes regardés s'afficheront ici.")
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(entries, key = { _, e -> "${e.channel.type}|${e.channel.id}" }) { i, entry ->
                val item = entry.channel
                val key = "${source.id}|${item.id}"
                HistoryRow(
                    item = item,
                    at = library.lastPlayed(key) ?: entry.at,
                    progress = if (item.type == ItemType.LIVE) null else library.progress(key),
                    watched = library.isWatched(key),
                    modifier = if (i == 0) firstFocus else Modifier,
                ) {
                    when (item.type) {
                        ItemType.LIVE -> {
                            val live = entries.map { it.channel }.filter { it.type == ItemType.LIVE }
                            state.play(source, live, live.indexOf(item).coerceAtLeast(0))
                        }
                        ItemType.SERIES -> state.openItem(source, listOf(item), 0)
                        // film ou épisode : reprise directe, là où l'on s'était arrêté
                        else -> state.play(source, listOf(item), 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: Channel, at: Long, progress: Float?, watched: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(modifier).tvClickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Cm.SurfaceHigher),
            contentAlignment = Alignment.Center,
        ) {
            if (item.type == ItemType.LIVE) {
                ChannelLogo(item.logo, item.name, size = 52.dp)
            } else {
                Icon(AppIcons.Play, contentDescription = null, tint = Cm.Cyan.copy(alpha = 0.5f), modifier = Modifier.size(30.dp))
                if (item.logo != null) {
                    AsyncImage(item.logo, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                if (progress != null && !watched) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f))) {
                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Cm.Cyan))
                    }
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Cm.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            val kind = when (item.type) {
                ItemType.LIVE -> "Chaîne"
                ItemType.MOVIE -> "Film"
                ItemType.SERIES -> "Série"
                ItemType.EPISODE -> "Épisode"
            }
            val status = when {
                item.type == ItemType.LIVE -> null
                watched -> "✓ vu"
                progress != null -> "${(progress * 100).toInt()} % vu"
                else -> null
            }
            Text(
                listOfNotNull(kind, status, formatClock(at)).joinToString(" · "),
                style = MonoLabel,
                color = if (watched) Cm.Success else Cm.TextMuted,
                maxLines = 1,
            )
        }
        Icon(AppIcons.Play, contentDescription = "Lire", tint = Cm.Cyan, modifier = Modifier.size(24.dp))
    }
}

// ---------- Recherche ----------

@Composable
private fun SearchPage(state: AppState, source: Source, model: BrowserModel, modifier: Modifier, compact: Boolean) {
    val (_, fieldFocus) = rememberPageFocus(model)
    var searching by remember { mutableStateOf(false) }
    val query = model.searchText

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            model.searchResults = null
            searching = false
            return@LaunchedEffect
        }
        delay(450) // on attend la fin de la frappe
        searching = true
        try {
            model.searchResults = model.search(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            model.searchResults = SearchResults(emptyList(), emptyList(), emptyList())
        } finally {
            searching = false
        }
    }

    Column(modifier) {
        PageTitle(AppIcons.Search, "Recherche", "Chaînes, films et séries de ce compte")
        SearchField(
            value = query,
            onValueChange = { model.searchText = it },
            placeholder = "Titre d'un film, d'une série, nom d'une chaîne…",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(fieldFocus),
        )
        val results = model.searchResults
        when {
            searching && results == null -> LoadingBox("Recherche…")
            query.trim().length < 2 -> EmptyHint("Tape au moins deux lettres.")
            results == null -> Spacer(Modifier.height(1.dp))
            results.isEmpty -> EmptyHint("Aucun résultat pour « ${query.trim()} ».")
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                val posterWidth = if (compact) 108.dp else 132.dp
                if (results.live.isNotEmpty()) {
                    HomeRow("TV en direct (${results.live.size})") {
                        results.live.forEachIndexed { i, item ->
                            LiveCard(item, Modifier) { state.play(source, results.live, i) }
                        }
                    }
                }
                listOf("Films" to results.movies, "Séries" to results.series).forEach { (title, list) ->
                    if (list.isNotEmpty()) {
                        HomeRow("$title (${list.size})") {
                            list.forEachIndexed { i, item ->
                                Box(Modifier.width(posterWidth)) {
                                    PosterCard(
                                        item,
                                        favorite = model.isFavorite(item),
                                        onClick = { state.openItem(source, list, i) },
                                        onLongClick = { model.toggleFavorite(item) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (searching) {
                    Text("Mise à jour…", style = MonoLabel, color = Cm.TextMuted, modifier = Modifier.padding(20.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
