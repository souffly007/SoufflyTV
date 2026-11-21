package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.data.formatDuration
import fr.bonobo.soufflytv.data.movieMetaLine
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.ItemType
import fr.bonobo.soufflytv.model.MovieInfo
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.MonoLabel
import kotlinx.coroutines.CancellationException

/**
 * Accueil de contenus (grand écran), esprit Souffly :
 * « À la une » en grand, puis Continuer à regarder, Chaînes récentes, Nouveaux films,
 * Nouvelles séries, Films les mieux notés et Ma liste (favoris).
 */
@Composable
fun HomePage(state: AppState, source: Source, model: BrowserModel, modifier: Modifier) {
    LaunchedEffect(model) { model.loadHome() }

    val library = state.library
    val playable = setOf(ItemType.MOVIE, ItemType.EPISODE)
    val continueItems = library.recents
        .filter { it.sourceId == source.id && it.channel.type in playable }
        .map { it.channel }
        .filter { (library.position("${source.id}|${it.id}") ?: 0L) > 30_000 }
        .take(20)
    val liveRecents = library.recents.filter { it.sourceId == source.id && it.channel.type == ItemType.LIVE }
        .map { it.channel }.take(20)
    val myList = library.favorites
        .filter { it.sourceId == source.id && it.channel.type in setOf(ItemType.MOVIE, ItemType.SERIES) }
        .map { it.channel }

    val featured = continueItems.firstOrNull { it.type == ItemType.MOVIE }
        ?: model.homeNewMovies.firstOrNull()
        ?: model.homeNewSeries.firstOrNull()
        ?: myList.firstOrNull()

    // Focus : à l'arrivée, sur le bouton principal d'« À la une » ;
    // au retour d'une fiche ou du lecteur, sur la carte que l'on avait ouverte
    val heroFocus = remember { FocusRequester() }
    var heroFocused by remember { mutableStateOf(false) }
    val cardFocus = remember { FocusRequester() }
    var cardFocused by remember { mutableStateOf(false) }
    val returnKey = model.homeReturnKey
    if (returnKey == null) {
        KeepFocus(heroFocus, featured?.id, model.contentFocusToken, isFocused = { heroFocused || featured == null })
    } else {
        KeepFocus(cardFocus, returnKey, isFocused = { cardFocused })
    }
    DisposableEffect(model) {
        model.enterContent = {
            runCatching { if (model.homeReturnKey != null) cardFocus.requestFocus() else heroFocus.requestFocus() }.isSuccess
        }
        onDispose { model.enterContent = null }
    }

    fun open(row: String, list: List<Channel>, index: Int) {
        model.homeReturnKey = "$row|${list[index].id}"
        if (list[index].type == ItemType.LIVE) state.play(source, list, index) else state.openItem(source, list, index)
    }
    fun cardModifier(row: String, item: Channel): Modifier =
        if ("$row|${item.id}" == returnKey) {
            Modifier.focusRequester(cardFocus).onFocusChanged { cardFocused = it.isFocused }
        } else Modifier

    Column(modifier.verticalScroll(rememberScrollState())) {
        if (featured != null) {
            FeaturedBanner(
                state = state,
                source = source,
                model = model,
                item = featured,
                focusModifier = Modifier.focusRequester(heroFocus).onFocusChanged { heroFocused = it.isFocused },
                onOpen = {
                    model.homeReturnKey = null
                    state.openItem(source, listOf(featured), 0)
                },
                onPlay = { fromStart ->
                    model.homeReturnKey = null
                    if (fromStart) library.setPosition("${source.id}|${featured.id}", 0)
                    state.play(source, listOf(featured), 0)
                },
            )
        } else if (model.homeLoading) {
            Box(Modifier.fillMaxWidth().height(260.dp)) { LoadingBox("Chargement de l'accueil…") }
        }

        if (continueItems.isNotEmpty()) {
            HomeRow("Continuer à regarder") {
                continueItems.forEachIndexed { i, item ->
                    ContinueCard(
                        item,
                        progress = library.progress("${source.id}|${item.id}"),
                        position = library.position("${source.id}|${item.id}"),
                        modifier = cardModifier("continue", item),
                    ) {
                        // reprise directe, là où l'on s'était arrêté
                        model.homeReturnKey = "continue|${item.id}"
                        state.play(source, continueItems, i)
                    }
                }
            }
        }
        if (liveRecents.isNotEmpty()) {
            HomeRow("Chaînes récentes") {
                liveRecents.forEachIndexed { i, item ->
                    LiveCard(item, cardModifier("live", item)) { open("live", liveRecents, i) }
                }
            }
        }
        PosterRow("Nouveaux films", "movies", model.homeNewMovies, state, model, ::cardModifier, ::open)
        PosterRow("Nouvelles séries", "series", model.homeNewSeries, state, model, ::cardModifier, ::open)
        PosterRow("Films les mieux notés", "top", model.homeTopMovies, state, model, ::cardModifier, ::open)
        PosterRow("Ma liste", "mylist", myList, state, model, ::cardModifier, ::open)

        if (featured == null && !model.homeLoading && continueItems.isEmpty() && liveRecents.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                EmptyHint("Choisis TV en direct, Films ou Séries dans la barre de gauche.")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Grand bandeau « À la une » : image en fond, titre, infos, résumé, boutons. */
@Composable
private fun FeaturedBanner(
    state: AppState,
    source: Source,
    model: BrowserModel,
    item: Channel,
    focusModifier: Modifier,
    onOpen: () -> Unit,
    onPlay: (fromStart: Boolean) -> Unit,
) {
    var info by remember(item.id) {
        mutableStateOf(model.cachedDetails(item) ?: MovieInfo(item.name, item.plot, item.logo, meta = movieMetaLine(null, null, null, item.rating)))
    }
    LaunchedEffect(item.id) {
        try {
            info = model.details(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // on garde ce que la liste fournit
        }
    }
    val key = "${source.id}|${item.id}"
    val saved = state.library.position(key)?.takeIf { it > 30_000 }
    val favorite = state.library.isFavorite(source.id, item.id)
    val series = item.type == ItemType.SERIES

    Box(Modifier.fillMaxWidth().height(330.dp).clipToBounds()) {
        val image = info.backdrop ?: info.cover
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.7f).align(Alignment.CenterEnd),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(
                        0f to Cm.Background,
                        0.32f to Cm.Background,
                        0.6f to Cm.Background.copy(alpha = 0.5f),
                        1f to Color.Transparent,
                    )
                )
            )
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Cm.Background)))
        }
        Column(Modifier.fillMaxHeight().fillMaxWidth(0.68f).padding(start = 28.dp, top = 20.dp, end = 8.dp)) {
            Text(
                if (saved != null) "REPRENDRE" else "À LA UNE",
                style = MonoLabel,
                color = Cm.OnCyan,
                modifier = Modifier.background(Cm.Cyan, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(info.name, color = Cm.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 34.sp)
            info.meta?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Cm.Cyan, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            info.plot?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Cm.Text.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { if (series) onOpen() else onPlay(false) },
                    colors = ButtonDefaults.buttonColors(containerColor = Cm.Cyan, contentColor = Cm.OnCyan),
                    modifier = focusModifier,
                ) {
                    Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            series -> "Voir la série"
                            saved != null -> "Continuer (${formatDuration(saved)})"
                            else -> "Lire"
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                if (saved != null && !series) {
                    OutlinedButton(onClick = { onPlay(true) }) { Text("↺ Du début", maxLines = 1, softWrap = false) }
                }
                if (!series) {
                    OutlinedButton(onClick = onOpen) { Text("Plus d'infos", maxLines = 1, softWrap = false) }
                }
                // Ma liste : bouton rond, icône seule (la place manque sur une ligne à côté des autres)
                OutlinedButton(
                    onClick = { state.library.toggleFavorite(source.id, item) },
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        if (favorite) AppIcons.Star else AppIcons.StarBorder,
                        contentDescription = if (favorite) "Retirer de ma liste" else "Ajouter à ma liste",
                        tint = if (favorite) Cm.Cyan else Cm.Text,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** Titre de rangée (« ▌Continuer à regarder ») et cartes qui défilent horizontalement. */
@Composable
private fun HomeRow(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(Modifier.padding(start = 24.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(18.dp).background(Cm.Cyan, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Text(title, color = Cm.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            content()
        }
    }
}

@Composable
private fun PosterRow(
    title: String,
    row: String,
    items: List<Channel>,
    state: AppState,
    model: BrowserModel,
    cardModifier: (String, Channel) -> Modifier,
    open: (String, List<Channel>, Int) -> Unit,
) {
    if (items.isEmpty()) return
    HomeRow(title) {
        items.forEachIndexed { i, item ->
            Box(Modifier.width(132.dp)) {
                PosterCard(
                    item,
                    favorite = model.isFavorite(item),
                    focusModifier = cardModifier(row, item),
                    onClick = { open(row, items, i) },
                    onLongClick = { model.toggleFavorite(item) },
                )
            }
        }
    }
}

/** Carte « Continuer à regarder » : image large, barre de progression, temps restant. */
@Composable
private fun ContinueCard(item: Channel, progress: Float?, position: Long?, modifier: Modifier, onClick: () -> Unit) {
    Column(Modifier.width(250.dp).padding(4.dp).then(modifier).tvClickable(onClick = onClick).padding(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Cm.SurfaceHigher)) {
            Icon(AppIcons.Play, contentDescription = null, tint = Cm.Cyan.copy(alpha = 0.5f), modifier = Modifier.size(36.dp).align(Alignment.Center))
            if (item.logo != null) {
                AsyncImage(item.logo, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Cm.Cyan))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.name, color = Cm.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                if (item.type == ItemType.EPISODE) "ÉPISODE" else "FILM",
                progress?.let { "${(it * 100).toInt()} %" },
                position?.let { "arrêté à ${formatDuration(it)}" },
            ).joinToString(" · "),
            style = MonoLabel,
            color = Cm.TextMuted,
            maxLines = 1,
        )
    }
}

/** Carte de chaîne récente : logo et nom. */
@Composable
private fun LiveCard(item: Channel, modifier: Modifier, onClick: () -> Unit) {
    Column(
        Modifier.width(150.dp).padding(4.dp).then(modifier).tvClickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChannelLogo(item.logo, item.name, size = 64.dp)
        Spacer(Modifier.height(8.dp))
        Text(item.name, color = Cm.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
