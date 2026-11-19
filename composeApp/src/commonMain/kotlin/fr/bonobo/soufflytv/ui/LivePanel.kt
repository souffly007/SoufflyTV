package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.bonobo.soufflytv.data.toUserMessage
import fr.bonobo.soufflytv.model.Category
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.Programme
import fr.bonobo.soufflytv.model.Section
import fr.bonobo.soufflytv.platform.formatClock
import fr.bonobo.soufflytv.platform.nowMillis
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.MonoLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Liste des chaînes posée sur la vidéo, façon TiviMate mais aux couleurs Souffly :
 *  - colonne des chaînes du groupe en cours, chaîne regardée en surbrillance ;
 *  - flèche gauche : colonne des groupes (Favoris, Récents, TV HD, CINEMA…) ;
 *  - OK sur une chaîne : elle se lance, la liste se referme ; la vidéo ne s'arrête jamais.
 */
@Composable
fun LiveChannelPanel(
    browser: BrowserModel,
    playlist: List<Channel>,
    playlistGroup: Category?,
    playingId: String,
    showGroups: Boolean,
    focusToken: Int,
    isFavorite: (Channel) -> Boolean,
    onShowGroups: (Boolean) -> Unit,
    onPlay: (list: List<Channel>, index: Int, group: Category?) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<Category>?>(null) }
    var groupsError by remember { mutableStateOf<String?>(null) }
    var shownGroup by remember(playlistGroup) { mutableStateOf(playlistGroup) }
    var shownList by remember(playlist) { mutableStateOf(playlist) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var clock by remember { mutableLongStateOf(nowMillis()) }
    var epgTick by remember { mutableIntStateOf(0) }

    // Guide complet (XMLTV) pour afficher l'émission en cours de chaque chaîne
    LaunchedEffect(Unit) {
        browser.ensureEpg()
        while (true) {
            delay(30_000)
            clock = nowMillis()
        }
    }
    LaunchedEffect(showGroups) {
        if (showGroups && groups == null) {
            groups = try {
                browser.groupsFor(Section.LIVE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                groupsError = e.toUserMessage()
                emptyList()
            }
        }
    }
    // relit browser.epgVersion pour rafraîchir quand le guide arrive
    val epgVersion = browser.epgVersion + epgTick

    Row(
        modifier.background(Cm.Background.copy(alpha = 0.94f)),
    ) {
        if (showGroups) {
            GroupColumn(
                groups = groups,
                error = groupsError,
                currentId = shownGroup?.id,
                focusToken = focusToken,
                onSelect = { group ->
                    scope.launch {
                        loading = true
                        loadError = null
                        try {
                            shownList = browser.channelsFor(Section.LIVE, group)
                            shownGroup = group
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            loadError = e.toUserMessage()
                        } finally {
                            loading = false
                        }
                        onShowGroups(false)
                    }
                },
                onClose = { onShowGroups(false) },
                modifier = Modifier.width(250.dp).fillMaxHeight(),
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(Cm.Outline))
        }
        ChannelColumn(
            title = shownGroup?.name ?: "Liste en cours",
            channels = shownList,
            playingId = playingId,
            loading = loading,
            error = loadError,
            clock = clock,
            epgVersion = epgVersion,
            epgNow = { browser.provider.epgNow(it) },
            isFavorite = isFavorite,
            focusToken = focusToken,
            groupsOpen = showGroups,
            onOpenGroups = { onShowGroups(true) },
            onPlay = { index -> onPlay(shownList, index, shownGroup) },
            modifier = Modifier.width(380.dp).fillMaxHeight(),
        )
        // Liseré cyan : la signature Souffly
        Box(Modifier.width(2.dp).fillMaxHeight().background(Cm.Cyan.copy(alpha = 0.7f)))
    }
}

@Composable
private fun GroupColumn(
    groups: List<Category>?,
    error: String?,
    currentId: String?,
    focusToken: Int,
    onSelect: (Category) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val list = groups.orEmpty()
    val target = list.indexOfFirst { it.id == currentId }.let { if (it >= 0) it else list.indexOfFirst { g -> g.id == ALL_CATEGORIES }.coerceAtLeast(0) }

    KeepFocus(
        focus, list.size, focusToken,
        isFocused = { focused || list.isEmpty() },
        prepare = { if (list.isNotEmpty()) listState.scrollToItem((target - 3).coerceAtLeast(0)) },
    )

    Column(modifier) {
        PanelHeader(label = "GROUPES", title = "${list.size - 3} groupes".takeIf { list.size > 3 } ?: "Groupes")
        when {
            error != null -> EmptyHint(error)
            groups == null -> LoadingBox("Chargement…")
            else -> LazyColumn(
                Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                        onClose()
                        true
                    } else false
                },
                state = listState,
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 6.dp),
            ) {
                itemsIndexed(list) { i, group ->
                    val current = group.id == currentId
                    val icon = when (group.id) {
                        FAVORITES -> AppIcons.Star
                        RECENTS -> AppIcons.History
                        else -> null
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .then(if (i == target) Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused } else Modifier)
                            .tvClickable(selected = current) { onSelect(group) }
                            .padding(horizontal = 10.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(width = 3.dp, height = 16.dp)
                                .background(if (current) Cm.Cyan else Color.Transparent, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(8.dp))
                        if (icon != null) {
                            Icon(icon, contentDescription = null, tint = Cm.Cyan, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            group.name,
                            color = if (current) Cm.Cyan else Cm.Text,
                            fontSize = 14.sp,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelColumn(
    title: String,
    channels: List<Channel>,
    playingId: String,
    loading: Boolean,
    error: String?,
    clock: Long,
    epgVersion: Int,
    epgNow: (Channel) -> Programme?,
    isFavorite: (Channel) -> Boolean,
    focusToken: Int,
    groupsOpen: Boolean,
    onOpenGroups: () -> Unit,
    onPlay: (Int) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val target = channels.indexOfFirst { it.id == playingId }.coerceAtLeast(0)

    KeepFocus(
        focus, channels, focusToken, groupsOpen,
        isFocused = { focused || channels.isEmpty() || groupsOpen },
        prepare = { if (channels.isNotEmpty()) listState.scrollToItem((target - 3).coerceAtLeast(0)) },
    )

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PanelHeader(label = "GROUPE · ${channels.size} CHAÎNES", title = title)
            }
            if (!groupsOpen) {
                TextButton(onClick = onOpenGroups, modifier = Modifier.padding(end = 6.dp)) {
                    Text("◀ Groupes", style = MonoLabel, color = Cm.Cyan, fontSize = 12.sp)
                }
            }
        }
        when {
            loading -> LoadingBox("Chargement…")
            error != null -> EmptyHint(error)
            channels.isEmpty() -> EmptyHint("Aucune chaîne dans ce groupe")
            else -> LazyColumn(
                Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                        onOpenGroups()
                        true
                    } else false
                },
                state = listState,
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 6.dp),
            ) {
                itemsIndexed(channels) { i, channel ->
                    val playing = channel.id == playingId
                    val now = remember(channel.id, epgVersion, clock) { epgNow(channel) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .then(if (i == target) Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused } else Modifier)
                            .tvClickable(selected = playing) { onPlay(i) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (channel.number ?: (i + 1)).toString(),
                            style = MonoLabel,
                            color = if (playing) Cm.Cyan else Cm.TextMuted,
                            modifier = Modifier.width(38.dp),
                            maxLines = 1,
                        )
                        ChannelLogo(channel.logo, channel.name, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                channel.name,
                                color = if (playing) Cm.Cyan else Cm.Text,
                                fontSize = 14.sp,
                                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                                    modifier = Modifier.fillMaxWidth(0.7f).height(2.dp),
                                    color = Cm.Cyan,
                                    trackColor = Cm.Outline,
                                )
                            }
                        }
                        if (playing) {
                            Text("● EN COURS", style = MonoLabel, color = Cm.Cyan, fontSize = 9.sp)
                        } else if (isFavorite(channel)) {
                            Icon(AppIcons.Star, contentDescription = "Favori", tint = Cm.Cyan, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(label: String, title: String) {
    Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)) {
        Text(label, style = MonoLabel, color = Cm.TextMuted)
        Text(
            title,
            color = Cm.Cyan,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
