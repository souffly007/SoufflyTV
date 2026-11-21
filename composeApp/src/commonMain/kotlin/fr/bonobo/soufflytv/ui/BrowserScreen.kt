package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.Screen
import androidx.compose.material3.TextButton
import fr.bonobo.soufflytv.data.detail
import fr.bonobo.soufflytv.data.kindLabel
import fr.bonobo.soufflytv.data.movieMetaLine
import fr.bonobo.soufflytv.data.ratingValue
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.ItemType
import fr.bonobo.soufflytv.model.MovieInfo
import fr.bonobo.soufflytv.model.Programme
import fr.bonobo.soufflytv.model.Section
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.platform.formatClock
import fr.bonobo.soufflytv.platform.isTvBuild
import fr.bonobo.soufflytv.platform.nowMillis
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.MonoLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect

@Composable
fun BrowserScreen(state: AppState, source: Source) {
    val model = remember(source) { state.browser(source) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = isTvBuild || maxWidth >= 720.dp
        SideEffect { model.wide = wide }
        LaunchedEffect(model) { model.start() }

        val manage = { state.navigate(Screen.ManageCategories(source, model.section)) }

        if (wide) {
            // Grand écran : barre latérale (Accueil, TV, Films, Séries, Filtres, Réglages, compte)
            // et, à droite, l'accueil de contenus ou la rubrique choisie
            val remote = remoteNavigation()
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    // À la télécommande, la barre repliée (icônes) se déplie par-dessus le contenu
                    Spacer(Modifier.width(if (remote) RAIL_COLLAPSED else RAIL_EXPANDED).fillMaxHeight())
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val error = model.error
                        when {
                            model.showHome -> HomePage(state, source, model, Modifier.fillMaxSize())
                            model.categoriesLoading && !model.loadedOnce -> LoadingBox("Connexion à la source…")
                            error != null && !model.loadedOnce -> ErrorBox(error, onRetry = model::retry)
                            else -> Row(Modifier.fillMaxSize()) {
                                CategoryPane(model, Modifier.width(240.dp).fillMaxHeight())
                                Box(Modifier.width(1.dp).fillMaxHeight().background(Cm.Outline))
                                ChannelPane(state, source, model, Modifier.weight(1f).fillMaxHeight(), showBack = false, wide = true)
                            }
                        }
                    }
                }
                SideRail(
                    source = source,
                    model = model,
                    remote = remote,
                    onAccounts = { state.home() },
                    onManage = manage,
                    onSettings = { state.navigate(Screen.Settings) },
                )
            }
            return@BoxWithConstraints
        }

        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = source.name,
                subtitle = "${source.kindLabel} · ${source.detail}",
                onBack = { state.back() },
                actions = {
                    if (model.loadedOnce) {
                        TextButtonIcon(
                            label = if (model.hiddenCount > 0) "Catégories (${model.hiddenCount} masquées)" else "Catégories",
                            showLabel = false,
                            onClick = manage,
                        )
                    }
                },
            )
            if (model.sections.size > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    model.sections.forEach { s ->
                        Pill(s.label, selected = s == model.section) { model.selectSection(s) }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val error = model.error
                when {
                    model.categoriesLoading && !model.loadedOnce -> LoadingBox("Connexion à la source…")
                    error != null && !model.loadedOnce -> ErrorBox(error, onRetry = model::retry)
                    model.showChannels -> ChannelPane(state, source, model, Modifier.fillMaxSize(), showBack = true, wide = false)
                    else -> CategoryPane(model, Modifier.fillMaxSize())
                }
            }
        }
    }
}

private val RAIL_COLLAPSED = 76.dp
private val RAIL_EXPANDED = 216.dp

/**
 * Barre latérale, esprit Souffly : logo, heure, Accueil · TV en direct · Films · Séries,
 * puis Filtres, Réglages et le compte en cours (changer de compte).
 * À la télécommande : repliée en icônes, elle se déplie quand on y entre (◀ depuis le contenu).
 */
@Composable
private fun SideRail(
    source: Source,
    model: BrowserModel,
    remote: Boolean,
    onAccounts: () -> Unit,
    onManage: () -> Unit,
    onSettings: () -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }
    val expanded = !remote || hasFocus
    var clock by remember { mutableStateOf(formatClock(nowMillis())) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = formatClock(nowMillis())
            delay(20_000)
        }
    }

    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier
                .width(if (expanded) RAIL_EXPANDED else RAIL_COLLAPSED)
                .fillMaxHeight()
                .background(Cm.Surface)
                .onFocusChanged { hasFocus = it.hasFocus }
                .onPreviewKeyEvent { event ->
                    // ▶ : retour dans le contenu, sur la catégorie ou la carte d'où l'on vient
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight &&
                        model.enterContent?.invoke() == true
                }
                .padding(horizontal = 10.dp, vertical = 14.dp),
        ) {
            // Logo et heure
            Row(Modifier.padding(start = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.Bottom) {
                Text("S", color = Cm.Cyan, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (expanded) {
                    Text("oufflyTV", color = Cm.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(clock, style = MonoLabel, color = Cm.TextMuted)
                }
            }
            RailItem(AppIcons.Home, "Accueil", expanded, selected = model.showHome) { model.goHome() }
            model.sections.forEach { s ->
                val (icon, label) = when (s) {
                    Section.LIVE -> AppIcons.Tv to "TV en direct"
                    Section.MOVIES -> AppIcons.Movie to "Films"
                    Section.SERIES -> AppIcons.Library to "Séries"
                }
                RailItem(icon, label, expanded, selected = !model.showHome && s == model.section) { model.selectSection(s) }
            }
            Spacer(Modifier.weight(1f))
            RailItem(
                AppIcons.Filter,
                if (model.hiddenCount > 0) "Filtres (${model.hiddenCount})" else "Filtres",
                expanded,
                selected = false,
                onClick = onManage,
            )
            RailItem(AppIcons.Settings, "Réglages", expanded, selected = false, onClick = onSettings)
            Spacer(Modifier.height(6.dp))
            // Compte en cours : changer de compte
            Row(
                Modifier.fillMaxWidth().tvClickable(onClick = onAccounts).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(34.dp).background(Cm.CyanGlow, RoundedCornerShape(50)).border(1.dp, Cm.Cyan, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(source.name.take(1).uppercase(), color = Cm.Cyan, fontWeight = FontWeight.Bold)
                }
                if (expanded) {
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(source.name, color = Cm.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Changer de compte", color = Cm.TextMuted, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
        // liseré cyan, façon CyanogenMod
        Box(Modifier.width(1.dp).fillMaxHeight().background(Cm.Cyan.copy(alpha = 0.5f)))
    }
}

@Composable
private fun RailItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).tvClickable(selected = selected, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) Cm.Cyan else Cm.TextMuted, modifier = Modifier.size(24.dp))
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(
                label,
                color = if (selected) Cm.Cyan else Cm.Text,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CategoryPane(model: BrowserModel, modifier: Modifier) {
    val selectedFocus = remember { FocusRequester() }
    var selectedFocused by remember { mutableStateOf(false) }
    val listState = model.categoryListState
    val selectedId = model.selected?.id

    // Télécommande : à l'ouverture et au retour du lecteur, le focus va sur la catégorie choisie
    KeepFocus(
        selectedFocus, selectedId, model.loadedOnce, model.contentFocusToken,
        isFocused = { selectedFocused || selectedId == null || model.returnFocusId != null },
        prepare = {
            val item = model.categories.indexOfFirst { it.id == selectedId } + 1 // ligne 0 : en-tête
            if (item > 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == item }) {
                listState.scrollToItem((item - 3).coerceAtLeast(0))
            }
        },
    )

    DisposableEffect(model) {
        model.enterContent = { runCatching { selectedFocus.requestFocus() }.isSuccess }
        onDispose { model.enterContent = null }
    }

    LazyColumn(
        modifier.onPreviewKeyEvent { event ->
            event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight &&
                model.enterPosters?.invoke() == true
        },
        state = listState,
        contentPadding = PaddingValues(8.dp),
    ) {
        item {
            Text(
                "CATÉGORIES · ${model.providerCategoryCount.coerceAtLeast(0)}",
                style = MonoLabel,
                color = Cm.TextMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        items(model.categories) { category ->
            val selected = category.id == model.selected?.id
            val icon = when (category.id) {
                FAVORITES -> AppIcons.Star
                RECENTS -> AppIcons.History
                else -> null
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp)
                    .then(
                        if (selected) {
                            Modifier.focusRequester(selectedFocus).onFocusChanged { selectedFocused = it.isFocused }
                        } else Modifier
                    )
                    .tvClickable(selected = selected) { model.select(category) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(width = 3.dp, height = 18.dp)
                        .background(if (selected) Cm.Cyan else Color.Transparent, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(10.dp))
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = Cm.Cyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    category.name,
                    color = if (selected) Cm.Cyan else Cm.Text,
                    fontSize = 14.sp,
                    fontWeight = if (selected || icon != null) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ChannelPane(state: AppState, source: Source, model: BrowserModel, modifier: Modifier, showBack: Boolean, wide: Boolean) {
    val category = model.selected
    val filtered by remember(model) { derivedStateOf { model.visibleChannels() } }
    val error = model.error
    val posters = model.section != Section.LIVE
    val unit = when (model.section) {
        Section.LIVE -> "chaîne(s)"
        Section.MOVIES -> "film(s)"
        Section.SERIES -> "élément(s)"
    }
    val showEpg = model.section == Section.LIVE && model.provider.hasLocalEpg && state.settings.value.epgInLists
    // Films et séries à la télécommande : bandeau du haut qui présente l'affiche sélectionnée
    val heroMode = wide && posters && remoteNavigation() && filtered.isNotEmpty() && !model.channelsLoading
    val hero = if (heroMode) {
        model.heroItem?.takeIf { h -> filtered.any { it.id == h.id } } ?: filtered.first()
    } else null

    // Rafraîchit l'émission en cours affichée dans la liste
    var clock by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(showEpg) {
        while (showEpg) {
            delay(60_000)
            clock = nowMillis()
        }
    }

    BoxWithConstraints(modifier) {
    val heroHeight = (maxHeight * 0.47f).coerceIn(190.dp, 360.dp)
    Column(Modifier.fillMaxSize()) {
        if (hero != null) PosterHero(model, hero, Modifier.fillMaxWidth().height(heroHeight))
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = if (hero != null) 2.dp else 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(onClick = { model.showChannels = false }) {
                    Icon(AppIcons.Back, contentDescription = "Catégories", tint = Cm.Text)
                }
            } else {
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    category?.name ?: "Aucune catégorie",
                    color = Cm.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (category != null && !model.channelsLoading) {
                    val position = hero?.let { h -> filtered.indexOfFirst { it.id == h.id } + 1 } ?: 0
                    Text(
                        if (position > 0) "$position / ${filtered.size}" else "${filtered.size} $unit",
                        style = MonoLabel,
                        color = Cm.TextMuted,
                    )
                }
            }
            // Grand écran : la recherche tient sur la ligne du titre
            if (wide && category != null) {
                SearchField(
                    value = model.query,
                    onValueChange = { model.query = it },
                    placeholder = "Rechercher",
                    modifier = Modifier.width(340.dp),
                )
            }
        }

        if (!wide && category != null) {
            SearchField(
                value = model.query,
                onValueChange = { model.query = it },
                placeholder = "Rechercher",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(6.dp))
        }

        when {
            category == null -> EmptyHint("Choisis une catégorie")
            model.channelsLoading -> LoadingBox("Chargement de la liste…")
            error != null -> ErrorBox(error, onRetry = model::retry)
            filtered.isEmpty() -> EmptyHint(
                when {
                    model.query.isNotBlank() -> "Aucun résultat"
                    category.id == FAVORITES -> if (posters) "Aucun favori.\nAppui long sur une affiche pour l'ajouter."
                    else "Aucun favori.\nTouche l'étoile d'une chaîne pour l'ajouter."
                    category.id == RECENTS -> "Rien regardé récemment"
                    else -> "Cette catégorie est vide"
                }
            )
            posters -> {
                // ▶ depuis les catégories : on entre sur l'affiche du bandeau (ou la première)
                val entryRequester = remember { FocusRequester() }
                val entryId = (hero ?: model.heroItem?.takeIf { h -> filtered.any { it.id == h.id } } ?: filtered.first()).id
                val scope = rememberCoroutineScope()
                DisposableEffect(model, entryId, filtered) {
                    model.enterPosters = {
                        val target = filtered.indexOfFirst { it.id == entryId }.coerceAtLeast(0)
                        scope.launch {
                            val grid = model.posterGridState
                            if (grid.layoutInfo.visibleItemsInfo.none { it.index == target }) grid.scrollToItem(target)
                            withFrameNanos { }
                            runCatching { entryRequester.requestFocus() }
                        }
                        true
                    }
                    onDispose { model.enterPosters = null }
                }

                // Retour d'une fiche ou du lecteur : le focus revient sur l'affiche ouverte
                val posterFocus = remember { FocusRequester() }
                var posterFocused by remember { mutableStateOf(false) }
                val returnId = model.returnFocusId
                KeepFocus(
                    posterFocus, returnId, filtered.size,
                    isFocused = { posterFocused || model.returnFocusId == null },
                    prepare = {
                        val target = filtered.indexOfFirst { it.id == returnId }
                        val grid = model.posterGridState
                        if (target >= 0 && grid.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                            grid.scrollToItem(target)
                        }
                    },
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (heroMode) 112.dp else 128.dp),
                    state = model.posterGridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    itemsIndexed(filtered, key = { _, item -> item.id }) { index, item ->
                        PosterCard(
                            item,
                            favorite = model.isFavorite(item),
                            focusModifier = Modifier
                                .then(if (item.id == entryId) Modifier.focusRequester(entryRequester) else Modifier)
                                .then(
                                    if (item.id == returnId) {
                                        Modifier.focusRequester(posterFocus).onFocusChanged { posterFocused = it.isFocused }
                                    } else Modifier
                                ),
                            onFocused = { model.focusPoster(item, index) },
                            onClick = {
                                model.returnFocusId = item.id
                                model.focusPoster(item, index)
                                state.openItem(source, filtered, index)
                            },
                            onLongClick = { model.toggleFavorite(item) },
                        )
                    }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), state = model.channelListState, contentPadding = PaddingValues(8.dp)) {
                itemsIndexed(filtered) { index, channel ->
                    val now = if (showEpg) remember(channel.id, model.epgVersion, clock) { model.provider.epgNow(channel) } else null
                    ChannelRow(
                        channel,
                        index,
                        favorite = model.isFavorite(channel),
                        now = now,
                        clock = clock,
                        onClick = { state.openItem(source, filtered, index) },
                        onToggleFavorite = { model.toggleFavorite(channel) },
                    )
                }
            }
        }
    }
    }
}

/**
 * Bandeau façon TiviMate, esprit Souffly : l'affiche sélectionnée est présentée en grand
 * (image à droite fondue dans le fond, titre, année · genre · durée · note, distribution, résumé).
 */
@Composable
private fun PosterHero(model: BrowserModel, item: Channel, modifier: Modifier) {
    val basic = remember(item.id) {
        MovieInfo(item.name, item.plot, item.logo, meta = movieMetaLine(null, null, null, item.rating))
    }
    var info by remember(item.id) { mutableStateOf(model.cachedDetails(item) ?: basic) }
    LaunchedEffect(item.id) {
        if (model.cachedDetails(item) != null) return@LaunchedEffect
        delay(400) // défilement rapide : pas de requête pour chaque affiche survolée
        try {
            info = model.details(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // on garde ce que la liste fournit
        }
    }

    Box(modifier.clipToBounds().background(Cm.Background)) {
        val image = info.backdrop ?: info.cover
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.62f).align(Alignment.CenterEnd),
                contentScale = ContentScale.Crop,
            )
            // Fondu vers la gauche (texte lisible) et vers le bas (grille des affiches)
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(
                        0f to Cm.Background,
                        0.38f to Cm.Background,
                        0.62f to Cm.Background.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                    )
                )
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(0.55f to Color.Transparent, 1f to Cm.Background)
                )
            )
        }
        Column(Modifier.fillMaxHeight().fillMaxWidth(0.64f).padding(start = 18.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(
                info.name,
                color = Cm.Text,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            info.meta?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Cm.Cyan, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            info.cast?.let { HeroCredit("Avec", it) }
            info.director?.let { HeroCredit("Réalisation", it) }
            info.plot?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = Cm.Text.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

@Composable
private fun HeroCredit(label: String, value: String) {
    Spacer(Modifier.height(4.dp))
    Row {
        Text("$label : ", color = Cm.TextMuted, fontSize = 13.sp)
        Text(value, color = Cm.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    index: Int,
    favorite: Boolean,
    now: Programme?,
    clock: Long,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .tvClickable(onClick = onClick, onLongClick = onToggleFavorite)
            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (channel.number ?: (index + 1)).toString(),
            style = MonoLabel,
            color = Cm.TextMuted,
            modifier = Modifier.width(44.dp),
            maxLines = 1,
        )
        ChannelLogo(channel.logo, channel.name, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, color = Cm.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) {
                Text(
                    "${formatClock(now.start)} · ${now.title}",
                    color = Cm.TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                LinearProgressIndicator(
                    progress = { now.progress(clock) },
                    modifier = Modifier.fillMaxWidth(0.6f).height(2.dp),
                    color = Cm.Cyan,
                    trackColor = Cm.Outline,
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (favorite) AppIcons.Star else AppIcons.StarBorder,
                contentDescription = if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
                tint = if (favorite) Cm.Cyan else Cm.TextMuted.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
internal fun PosterCard(
    item: Channel,
    favorite: Boolean,
    focusModifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var loaded by remember(item.logo) { mutableStateOf(false) }
    Column(
        Modifier
            .padding(4.dp)
            .then(focusModifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .tvClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Cm.SurfaceHigher),
            contentAlignment = Alignment.Center,
        ) {
            if (!loaded) {
                Text(
                    item.name,
                    color = Cm.TextMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    modifier = Modifier.padding(8.dp),
                )
            }
            if (item.logo != null) {
                AsyncImage(
                    model = item.logo,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onSuccess = { loaded = true },
                )
            }
            if (favorite) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)
                        .background(Cm.Background.copy(alpha = 0.7f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Star, contentDescription = "Favori", tint = Cm.Cyan, modifier = Modifier.size(16.dp))
                }
            }
            if (item.type == ItemType.SERIES) {
                Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { TypeBadge("SÉRIE") }
            }
            ratingValue(item.rating)?.let { note ->
                Text(
                    note,
                    color = Cm.OnCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Cm.Cyan, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.name,
            color = Cm.Text,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(36.dp),
        )
    }
}

/** Bouton « filtre » de la barre du haut, avec son libellé sur grand écran. */
@Composable
private fun TextButtonIcon(label: String, showLabel: Boolean, onClick: () -> Unit) {
    if (showLabel) {
        TextButton(onClick = onClick) {
            Icon(AppIcons.Filter, contentDescription = null, tint = Cm.Cyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Cm.Cyan, fontSize = 14.sp)
        }
    } else {
        IconButton(onClick = onClick) {
            Icon(AppIcons.Filter, contentDescription = label, tint = Cm.Cyan)
        }
    }
}
