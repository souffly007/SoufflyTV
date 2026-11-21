package fr.bonobo.soufflytv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import fr.bonobo.soufflytv.model.Section
import fr.bonobo.soufflytv.model.Category
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.Screen
import fr.bonobo.soufflytv.data.formatDuration
import fr.bonobo.soufflytv.data.toUserMessage
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.ItemType
import fr.bonobo.soufflytv.model.Programme
import fr.bonobo.soufflytv.model.StreamRequest
import fr.bonobo.soufflytv.platform.FullscreenEffect
import fr.bonobo.soufflytv.platform.isTvBuild
import fr.bonobo.soufflytv.platform.channelKeyDelta
import fr.bonobo.soufflytv.platform.formatClock
import fr.bonobo.soufflytv.platform.nowMillis
import fr.bonobo.soufflytv.platform.playerOverlay
import fr.bonobo.soufflytv.player.PlayerController
import fr.bonobo.soufflytv.player.PlayerOptions
import fr.bonobo.soufflytv.player.PlayerStatus
import fr.bonobo.soufflytv.player.TrackOption
import fr.bonobo.soufflytv.player.VideoPlayer
import fr.bonobo.soufflytv.player.speedLabel
import fr.bonobo.soufflytv.player.PLAYBACK_SPEEDS
import fr.bonobo.soufflytv.player.pickAudioTrack
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.LocalFocusColor
import fr.bonobo.soufflytv.ui.theme.MonoLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val SEEK_STEP_MS = 10_000L
private const val MAX_AUTO_RETRIES = 3

@Composable
fun PlayerScreen(state: AppState, screen: Screen.Player) {
    val source = screen.source
    val provider = remember(source) { state.provider(source) }
    val settings = state.settings.value

    // La liste en cours peut changer depuis la liste des chaînes (autre groupe)
    var playlist by remember(screen) { mutableStateOf(screen.channels) }
    var index by remember(screen) { mutableIntStateOf(screen.index) }
    val channel = playlist[index.coerceIn(0, playlist.lastIndex)]
    val live = channel.type == ItemType.LIVE
    val resumeKey = "${source.id}|${channel.id}"

    var aspect by remember { mutableStateOf(settings.aspect) }
    val controller = remember(channel) { PlayerController().also { it.aspect = aspect } }

    var retry by remember { mutableIntStateOf(0) }
    var autoRetries by remember(channel) { mutableIntStateOf(0) }
    var request by remember { mutableStateOf<StreamRequest?>(null) }
    var status by remember { mutableStateOf<PlayerStatus>(PlayerStatus.Loading) }
    var programmes by remember(channel) { mutableStateOf<List<Programme>>(emptyList()) }
    var clock by remember { mutableLongStateOf(nowMillis()) }
    var toast by remember { mutableStateOf<String?>(null) }
    var resumed by remember(channel) { mutableStateOf(false) }
    var recorded by remember(channel) { mutableStateOf(false) }

    var overlayVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    val pip = state.inPictureInPicture
    val rootFocus = remember { FocusRequester() }
    val overlayFocus = remember { FocusRequester() }
    var focusOverlay by remember { mutableIntStateOf(0) }
    var bannerVisible by remember { mutableStateOf(false) }
    var bannerKey by remember { mutableIntStateOf(0) }
    var digits by remember { mutableStateOf("") }

    // Liste des chaînes par-dessus la vidéo (direct uniquement)
    val browser = remember(source) { state.browser(source) }
    var playlistGroup by remember(screen) {
        mutableStateOf(browser.selected?.takeIf { browser.section == Section.LIVE })
    }
    var panelOpen by remember { mutableStateOf(false) }
    var panelGroups by remember { mutableStateOf(false) }
    var panelToken by remember { mutableIntStateOf(0) }
    val keyNavigation = isKeyNavigation()

    fun zap(delta: Int) {
        val count = playlist.size
        if (count > 1) {
            index = (index + delta + count) % count
            interaction++
            bannerVisible = true
            bannerKey++
        }
    }

    /** Numéro tapé à la télécommande : numéro de la chaîne, sinon sa position dans la liste. */
    fun jumpTo(number: Int) {
        val byNumber = playlist.indexOfFirst { it.number == number }
        val target = if (byNumber >= 0) byNumber else (number - 1).takeIf { it in playlist.indices }
        if (target == null) {
            toast = "Chaîne $number introuvable"
        } else if (target != index) {
            index = target
            bannerVisible = true
            bannerKey++
        }
    }

    fun openPanel(groups: Boolean) {
        panelOpen = true
        panelGroups = groups
        overlayVisible = false
        bannerVisible = false
        panelToken++
        interaction++
    }

    fun closePanel() {
        panelOpen = false
        panelGroups = false
        runCatching { rootFocus.requestFocus() }
    }

    fun playFromPanel(list: List<Channel>, i: Int, group: Category?) {
        playlist = list
        index = i
        playlistGroup = group
        closePanel()
        bannerVisible = true
        bannerKey++
        // au retour, l'écran des catégories affichera ce groupe
        if (group != null && browser.section == Section.LIVE && browser.selected?.id != group.id) browser.select(group)
    }

    // Retour : on referme d'abord les groupes, puis la liste, puis les commandes
    val currentKeyNavigation by rememberUpdatedState(keyNavigation)
    DisposableEffect(Unit) {
        state.playerBackHandler = {
            when {
                panelOpen && panelGroups -> {
                    panelGroups = false
                    panelToken++
                    true
                }
                panelOpen -> {
                    closePanel()
                    true
                }
                playerOverlay && overlayVisible && currentKeyNavigation -> {
                    overlayVisible = false
                    runCatching { rootFocus.requestFocus() }
                    true
                }
                else -> false
            }
        }
        onDispose { state.playerBackHandler = null }
    }
    // la liste se referme seule après 20 s sans action
    LaunchedEffect(panelOpen, interaction) {
        if (panelOpen) {
            delay(20_000)
            closePanel()
        }
    }

    fun showOverlay(fromKey: Boolean) {
        overlayVisible = true
        interaction++
        if (fromKey) focusOverlay++
    }

    // --- Résolution du lien de lecture ---
    LaunchedEffect(channel, retry) {
        request = null
        status = PlayerStatus.Loading
        try {
            request = provider.resolve(channel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            status = PlayerStatus.Error(e.toUserMessage())
        }
    }

    // --- Reconnexion automatique du direct, historique ---
    val failed = status is PlayerStatus.Error || (live && status == PlayerStatus.Ended)
    val reconnecting = live && failed && autoRetries < MAX_AUTO_RETRIES
    LaunchedEffect(status) {
        when {
            status == PlayerStatus.Playing -> {
                autoRetries = 0
                if (!recorded) {
                    recorded = true
                    state.library.addRecent(source.id, channel)
                }
            }
            reconnecting -> {
                delay(2_000L * (autoRetries + 1))
                autoRetries++
                retry++
            }
        }
    }

    // --- Guide TV ---
    LaunchedEffect(channel) {
        if (!live) return@LaunchedEffect
        programmes = try {
            provider.epg(channel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            clock = nowMillis()
            delay(20_000)
        }
    }
    val current = programmes.firstOrNull { it.isOnAir(clock) }
    val next = programmes.firstOrNull { it.start >= (current?.stop ?: clock) }

    // --- Reprise des films / épisodes ---
    LaunchedEffect(controller, controller.durationMs) {
        if (live || resumed || controller.durationMs <= 0) return@LaunchedEffect
        resumed = true
        val saved = state.library.position(resumeKey)
        if (saved != null && saved > 30_000 && saved < controller.durationMs - 60_000) {
            controller.seekTo(saved)
            toast = "Reprise à ${formatDuration(saved)}"
        }
    }
    LaunchedEffect(controller) {
        if (live) return@LaunchedEffect
        while (true) {
            delay(10_000)
            if (controller.durationMs > 0) state.library.saveProgress(resumeKey, controller.positionMs, controller.durationMs)
        }
    }
    DisposableEffect(controller) {
        onDispose {
            if (!live && controller.durationMs > 0) state.library.saveProgress(resumeKey, controller.positionMs, controller.durationMs)
        }
    }

    // --- Épisode suivant automatique ---
    val hasNext = index < playlist.lastIndex
    LaunchedEffect(status, channel) {
        if (status == PlayerStatus.Ended && channel.type == ItemType.EPISODE && hasNext && settings.autoNextEpisode) {
            delay(5_000)
            index++
        }
    }

    // --- Messages et masquage automatique des commandes ---
    LaunchedEffect(bannerKey) {
        if (bannerVisible) {
            delay(3_500)
            bannerVisible = false
        }
    }
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            toast = "Chaîne : ${digits}_"
            delay(1_500)
            digits.toIntOrNull()?.let { jumpTo(it) }
            digits = ""
        }
    }
    // Audiodescription : dès que les pistes d'une chaîne sont connues, on prend celle qui correspond au réglage.
    // Une seule fois par chaîne et par réglage : un choix manuel dans le menu Audio est ensuite respecté.
    var audioChoiceDone by remember(request, retry, settings.audioDescription) { mutableStateOf(false) }
    LaunchedEffect(controller.audioTracks, audioChoiceDone) {
        val tracks = controller.audioTracks
        if (audioChoiceDone || tracks.none { it.selected }) return@LaunchedEffect
        pickAudioTrack(tracks, settings.audioDescription)?.let { controller.selectAudio(it.id) }
        audioChoiceDone = true
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(3_000)
            toast = null
        }
    }
    // (les commandes restent affichées tant qu'un film est en pause)
    LaunchedEffect(overlayVisible, interaction, status, controller.isPlaying) {
        if (playerOverlay && overlayVisible && status == PlayerStatus.Playing && (live || controller.isPlaying)) {
            delay(5_000)
            overlayVisible = false
            runCatching { rootFocus.requestFocus() }
        }
    }
    LaunchedEffect(focusOverlay) {
        if (focusOverlay > 0) {
            delay(50)
            runCatching { overlayFocus.requestFocus() }
        }
    }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    FullscreenEffect(landscape = settings.autoLandscape && !isTvBuild)

    val overlayActive = playerOverlay && overlayVisible && !pip

    val keyModifier = Modifier
        .focusRequester(rootFocus)
        .focusable()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            interaction++
            if (live) {
                // CH+ / CH- de la télécommande, + / - du clavier : toujours actifs
                val code = event.utf16CodePoint
                val delta = event.channelKeyDelta().takeIf { it != 0 } ?: when (code) {
                    '+'.code -> 1
                    '-'.code -> -1
                    else -> 0
                }
                if (delta != 0) {
                    zap(delta)
                    return@onPreviewKeyEvent true
                }
                // Chiffres : aller directement à une chaîne
                if (code in '0'.code..'9'.code) {
                    if (digits.length < 4) digits += code.toChar()
                    return@onPreviewKeyEvent true
                }
            }
            // Liste des chaînes ou commandes affichées : les flèches naviguent dans les éléments
            if (panelOpen || overlayActive) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionUp, Key.PageUp -> if (live) { zap(-1); true } else { showOverlay(true); true }
                Key.DirectionDown, Key.PageDown -> if (live) { zap(1); true } else { showOverlay(true); true }
                Key.DirectionLeft -> if (!live) { controller.seekBy(-SEEK_STEP_MS); toast = seekLabel(-SEEK_STEP_MS); true } else { openPanel(groups = true); true }
                Key.DirectionRight -> if (!live) { controller.seekBy(SEEK_STEP_MS); toast = seekLabel(SEEK_STEP_MS); true } else { showOverlay(true); true }
                Key.Spacebar -> { controller.togglePause(); true }
                Key.Menu -> { showOverlay(true); true }
                Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                    when {
                        live -> openPanel(groups = false)
                        playerOverlay -> showOverlay(true)
                        else -> controller.togglePause()
                    }
                    true
                }
                else -> false
            }
        }

    // ---------- Éléments de l'écran ----------

    val video: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            val req = request
            val s = status
            when {
                reconnecting -> LoadingBox("Reconnexion… (${autoRetries + 1}/$MAX_AUTO_RETRIES)")
                s is PlayerStatus.Error -> ErrorBox(s.message, onRetry = { autoRetries = 0; retry++ })
                s == PlayerStatus.Ended && live -> ErrorBox("Le flux s'est arrêté", onRetry = { autoRetries = 0; retry++ })
                s == PlayerStatus.Ended -> EndCard(
                    nextLabel = if (channel.type == ItemType.EPISODE && hasNext) playlist[index + 1].name else null,
                    autoNext = settings.autoNextEpisode,
                    onReplay = { state.library.setPosition(resumeKey, 0); retry++ },
                    onNext = if (hasNext) ({ index++ }) else null,
                )
                req != null -> key(req, retry) {
                    VideoPlayer(
                        request = req,
                        controller = controller,
                        options = PlayerOptions(live = live, buffer = settings.buffer),
                        modifier = Modifier.fillMaxSize(),
                        onStatus = { status = it },
                    )
                }
            }
            if (status == PlayerStatus.Loading && !reconnecting) {
                CircularProgressIndicator(color = Cm.Cyan, strokeWidth = 3.dp)
            }
        }
    }

    val topBar: @Composable (Modifier) -> Unit = { modifier ->
        PlayerTopBar(
            modifier = modifier,
            channel = channel,
            position = "${index + 1}/${playlist.size}",
            status = status,
            toast = toast,
            favorite = state.library.isFavorite(source.id, channel.id),
            controller = controller,
            aspectLabel = aspect.label,
            backFocus = overlayFocus,
            onBack = { state.back() },
            onFavorite = { state.library.toggleFavorite(source.id, channel) },
            onAspect = {
                aspect = aspect.next()
                controller.aspect = aspect
                toast = "Format : ${aspect.label}"
            },
            onPip = if (isTvBuild) null else state.enterPictureInPicture,
            onFullscreen = state.toggleFullscreen,
            onChannels = if (live) ({ openPanel(groups = false) }) else null,
            onAudioDescription = {
                val enable = controller.audioTracks.firstOrNull { it.selected }?.audioDescription != true
                pickAudioTrack(controller.audioTracks, enable)?.let { controller.selectAudio(it.id) }
                state.settings.update { it.copy(audioDescription = enable) }
                toast = if (enable) "Audiodescription activée" else "Audiodescription désactivée"
            },
            onInteract = { interaction++ },
        )
    }

    val bottomBar: @Composable (Modifier) -> Unit = { modifier ->
        if (live) {
            LiveBar(
                modifier = modifier,
                current = current,
                next = next,
                clock = clock,
                canZap = playlist.size > 1,
                onPrevious = { zap(-1) },
                onNext = { zap(1) },
            )
        } else {
            VodBar(
                modifier = modifier,
                controller = controller,
                episodes = channel.type == ItemType.EPISODE && playlist.size > 1,
                hasPrevious = index > 0,
                hasNext = hasNext,
                onPrevious = { index-- },
                onNext = { index++ },
                onSeekBy = { delta ->
                    controller.seekBy(delta)
                    toast = seekLabel(delta)
                    interaction++
                },
                onInteract = { interaction++ },
            )
        }
    }

    val panel: @Composable (Modifier) -> Unit = { modifier ->
        LiveChannelPanel(
            browser = browser,
            playlist = playlist,
            playlistGroup = playlistGroup,
            playingId = channel.id,
            showGroups = panelGroups,
            focusToken = panelToken,
            isFavorite = { state.library.isFavorite(source.id, it.id) },
            onShowGroups = { open ->
                panelGroups = open
                panelToken++
                interaction++
            },
            onPlay = { list, i, group -> playFromPanel(list, i, group) },
            modifier = modifier,
        )
    }

    // ---------- Mise en page ----------

    if (playerOverlay) {
        // Android : vidéo plein écran, commandes par-dessus, masquées après 5 s
        Box(Modifier.fillMaxSize().background(Color.Black).then(keyModifier)) {
            video(Modifier.fillMaxSize())
            // Carte d'erreur ou de fin affichée : ses boutons doivent rester touchables
            val showingCard = (status is PlayerStatus.Error && !reconnecting) || status == PlayerStatus.Ended
            if (!pip && !showingCard) {
                Box(
                    Modifier.fillMaxSize().pointerInput(live) {
                        detectTapGestures(
                            onTap = {
                                if (panelOpen) closePanel() else overlayVisible = !overlayVisible
                                interaction++
                            },
                            onDoubleTap = if (live) null else { offset ->
                                val back = offset.x < size.width / 2
                                controller.seekBy(if (back) -SEEK_STEP_MS else SEEK_STEP_MS)
                                toast = if (back) "− 10 s" else "+ 10 s"
                                interaction++
                            },
                        )
                    }
                )
            }
            if (!pip) {
                AnimatedVisibility(
                    visible = bannerVisible && !overlayActive && !panelOpen,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomStart),
                ) {
                    ZapBanner(channel, index, current)
                }
                if (digits.isNotEmpty()) {
                    Box(
                        Modifier.align(Alignment.TopEnd)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                            .padding(24.dp)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text("${digits}_", color = Cm.Cyan, fontSize = 40.sp, fontWeight = FontWeight.Bold, style = MonoLabel)
                    }
                }
                AnimatedVisibility(
                    visible = panelOpen,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    panel(
                        Modifier.fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                    )
                }
                AnimatedVisibility(visible = overlayActive, enter = fadeIn(), exit = fadeOut()) {
                    Box(Modifier.fillMaxSize()) {
                        topBar(
                            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        )
                        bottomBar(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        )
                    }
                }
            }
        }
    } else {
        // Ordinateur : la vidéo libVLC est une fenêtre native, rien de Compose ne se dessine par-dessus.
        // Fenêtre normale : barres autour de la vidéo. Plein écran : la vidéo seule.
        val cinema = state.isFullscreen
        var cinemaControls by remember { mutableStateOf(true) }
        LaunchedEffect(interaction) { cinemaControls = true }
        LaunchedEffect(cinema, cinemaControls, interaction, status, controller.isPlaying, panelOpen) {
            if (cinema && cinemaControls && !panelOpen && status == PlayerStatus.Playing && (live || controller.isPlaying)) {
                delay(3_000)
                cinemaControls = false
            }
        }
        DisposableEffect(controller) {
            controller.onSurfaceActivity = { interaction++ }
            controller.onSurfaceDoubleClick = { state.toggleFullscreen?.invoke() }
            onDispose {
                controller.onSurfaceActivity = null
                controller.onSurfaceDoubleClick = null
            }
        }
        // Plein écran automatique (réglage) ; on en sort en quittant le lecteur
        DisposableEffect(Unit) {
            if (settings.autoFullscreen) state.setFullscreen?.invoke(true)
            onDispose { if (state.isFullscreen) state.setFullscreen?.invoke(false) }
        }

        // Une seule mise en page : la vidéo garde sa place (sinon libVLC serait recréée et le flux relancé).
        // Plein écran : les barres se replient, la vidéo prend tout l'écran ; elles reviennent
        // quand la souris bouge ou qu'une touche est pressée (pas de fenêtre par-dessus la vidéo :
        // certains bureaux, KDE notamment, quittent alors le plein écran).
        val showBars = !cinema || cinemaControls || panelOpen
        Row(Modifier.fillMaxSize().background(Color.Black).then(keyModifier)) {
            if (panelOpen) panel(Modifier.fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (showBars) {
                    topBar(Modifier.fillMaxWidth().background(Cm.Surface))
                    CyanLine()
                }
                video(Modifier.weight(1f).fillMaxWidth())
                if (showBars) bottomBar(Modifier.fillMaxWidth().background(Cm.Surface))
            }
        }
    }
}

@Composable
private fun PlayerTopBar(
    modifier: Modifier,
    channel: Channel,
    position: String,
    status: PlayerStatus,
    toast: String?,
    favorite: Boolean,
    controller: PlayerController,
    aspectLabel: String,
    backFocus: FocusRequester,
    onBack: () -> Unit,
    onFavorite: () -> Unit,
    onAspect: () -> Unit,
    onPip: (() -> Unit)?,
    onFullscreen: (() -> Unit)?,
    onChannels: (() -> Unit)?,
    onAudioDescription: () -> Unit,
    onInteract: () -> Unit,
) {
    Row(
        modifier.height(64.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.focusRequester(backFocus)) {
            Icon(AppIcons.Back, contentDescription = "Retour", tint = Cm.Text)
        }
        ChannelLogo(channel.logo, channel.name, 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                channel.name,
                color = Cm.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                toast ?: "${statusLabel(status)}  ·  $position",
                style = MonoLabel,
                color = when {
                    toast != null -> Cm.Cyan
                    status is PlayerStatus.Error || status == PlayerStatus.Ended -> Cm.Error
                    status == PlayerStatus.Playing -> Cm.Cyan
                    else -> Cm.TextMuted
                },
                maxLines = 1,
            )
        }
        if (onChannels != null) {
            IconButton(onClick = onChannels) {
                Icon(AppIcons.ChannelList, contentDescription = "Liste des chaînes", tint = Cm.Cyan)
            }
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (favorite) AppIcons.Star else AppIcons.StarBorder,
                contentDescription = if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
                tint = if (favorite) Cm.Cyan else Cm.Text,
            )
        }
        // Bouton AD : seulement si la chaîne propose une piste d'audiodescription et une piste normale
        val tracks = controller.audioTracks
        if (tracks.any { it.audioDescription } && tracks.any { !it.audioDescription }) {
            val active = tracks.firstOrNull { it.selected }?.audioDescription == true
            IconButton(onClick = onAudioDescription) {
                Text(
                    "AD",
                    color = if (active) Cm.Cyan else Cm.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textDecoration = if (active) null else TextDecoration.LineThrough,
                    modifier = Modifier.semantics {
                        contentDescription = if (active) "Désactiver l'audiodescription" else "Activer l'audiodescription"
                    },
                )
            }
        }
        if (controller.audioTracks.size > 1) {
            TrackMenu(
                icon = { Icon(AppIcons.Audio, contentDescription = "Piste audio", tint = Cm.Text) },
                options = controller.audioTracks,
                allowOff = false,
                onSelect = { id -> id?.let(controller::selectAudio) },
                onOpen = onInteract,
            )
        }
        if (controller.textTracks.isNotEmpty()) {
            TrackMenu(
                icon = { Icon(AppIcons.Subtitles, contentDescription = "Sous-titres", tint = Cm.Text) },
                options = controller.textTracks,
                allowOff = true,
                onSelect = controller::selectText,
                onOpen = onInteract,
            )
        }
        // Vitesse de lecture : films et séries seulement
        if (channel.type != ItemType.LIVE) {
            TrackMenu(
                icon = {
                    Text(
                        if (controller.speed == 1f) "1×" else speedLabel(controller.speed),
                        color = if (controller.speed == 1f) Cm.Text else Cm.Cyan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                },
                options = PLAYBACK_SPEEDS.map { TrackOption(it.toString(), speedLabel(it), selected = controller.speed == it) },
                allowOff = false,
                onSelect = { id -> id?.toFloatOrNull()?.let(controller::setSpeed) },
                onOpen = onInteract,
            )
        }
        IconButton(onClick = onAspect) {
            Icon(AppIcons.Aspect, contentDescription = "Format d'image : $aspectLabel", tint = Cm.Text)
        }
        if (onPip != null) {
            IconButton(onClick = onPip) { Icon(AppIcons.Pip, contentDescription = "Image dans l'image", tint = Cm.Text) }
        }
        if (onFullscreen != null) {
            IconButton(onClick = onFullscreen) { Icon(AppIcons.Fullscreen, contentDescription = "Plein écran", tint = Cm.Text) }
        }
    }
}

@Composable
private fun TrackMenu(
    icon: @Composable () -> Unit,
    options: List<TrackOption>,
    allowOff: Boolean,
    onSelect: (String?) -> Unit,
    onOpen: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = {
            open = true
            onOpen()
        }) { icon() }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowOff) {
                val noneSelected = options.none { it.selected }
                DropdownMenuItem(
                    text = { Text(if (noneSelected) "✓  Désactivés" else "     Désactivés") },
                    onClick = {
                        onSelect(null)
                        open = false
                    },
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (option.selected) "✓  " else "     ") + option.label,
                            color = if (option.selected) Cm.Cyan else Cm.Text,
                        )
                    },
                    onClick = {
                        onSelect(option.id)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun LiveBar(
    modifier: Modifier,
    current: Programme?,
    next: Programme?,
    clock: Long,
    canZap: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (canZap) {
            ChannelButton("CH −", "Chaîne précédente", onPrevious)
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (current != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MAINTENANT", style = MonoLabel, color = Cm.Cyan)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${formatClock(current.start)} – ${formatClock(current.stop)}",
                        style = MonoLabel,
                        color = Cm.TextMuted,
                    )
                }
                Text(current.title, color = Cm.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Résumé de l'émission, quand le guide TV en donne un
                current.description?.takeIf { it.isNotBlank() }?.let { summary ->
                    Text(summary, color = Cm.TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { current.progress(clock) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Cm.Cyan,
                    trackColor = Cm.Outline,
                )
                if (next != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "ENSUITE ${formatClock(next.start)} · ${next.title}",
                        style = MonoLabel,
                        color = Cm.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text("EN DIRECT", style = MonoLabel, color = Cm.Cyan)
                Text("Pas de guide TV pour cette chaîne", color = Cm.TextMuted, fontSize = 13.sp)
            }
        }
        if (canZap) {
            ChannelButton("CH +", "Chaîne suivante", onNext)
        }
    }
}

@Composable
private fun VodBar(
    modifier: Modifier,
    controller: PlayerController,
    episodes: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onInteract: () -> Unit,
) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        SeekBar(
            position = controller.positionMs,
            duration = controller.durationMs,
            onSeek = { controller.seekTo(it) },
            onInteract = onInteract,
        )
        // Centré quand tout tient, défilable sur un écran étroit
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (episodes) {
                IconButton(onClick = onPrevious, enabled = hasPrevious) {
                    Icon(AppIcons.Previous, contentDescription = "Épisode précédent", tint = if (hasPrevious) Cm.Text else Cm.TextMuted)
                }
            }
            SeekButton("−10 min", "Reculer de 10 minutes") { onSeekBy(-600_000) }
            SeekButton("−1 min", "Reculer d'une minute") { onSeekBy(-60_000) }
            SeekButton("−10 s", "Reculer de 10 secondes") { onSeekBy(-SEEK_STEP_MS) }
            IconButton(
                onClick = { controller.togglePause(); onInteract() },
                modifier = Modifier.padding(horizontal = 6.dp).size(56.dp).background(Cm.CyanGlow, RoundedCornerShape(28.dp)),
            ) {
                Icon(
                    if (controller.isPlaying) AppIcons.Pause else AppIcons.Play,
                    contentDescription = if (controller.isPlaying) "Pause" else "Lecture",
                    tint = Cm.Cyan,
                    modifier = Modifier.size(32.dp),
                )
            }
            SeekButton("+10 s", "Avancer de 10 secondes") { onSeekBy(SEEK_STEP_MS) }
            SeekButton("+1 min", "Avancer d'une minute") { onSeekBy(60_000) }
            SeekButton("+10 min", "Avancer de 10 minutes") { onSeekBy(600_000) }
            if (episodes) {
                IconButton(onClick = onNext, enabled = hasNext) {
                    Icon(AppIcons.Next, contentDescription = "Épisode suivant", tint = if (hasNext) Cm.Text else Cm.TextMuted)
                }
            }
        }
        }
    }
}

/** Bouton de saut dans le temps, lisible et sélectionnable à la télécommande. */
@Composable
private fun SeekButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .semantics { contentDescription = description }
            .tvClickable(shape = RoundedCornerShape(10.dp), onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MonoLabel, fontSize = 13.sp, color = Cm.Text, maxLines = 1)
    }
}

/**
 * Barre de progression utilisable partout :
 *  - doigt / souris : toucher pour aller à un instant, glisser pour chercher ;
 *  - télécommande : la sélectionner (flèche bas), gauche/droite pour se déplacer
 *    (1 % du film par appui, au moins 10 s), OK ou pause d'une seconde pour valider.
 */
@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit, onInteract: () -> Unit) {
    var preview by remember { mutableStateOf<Long?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var widthPx by remember { mutableIntStateOf(1) }
    val thumbPx = with(LocalDensity.current) { (if (focused) 20.dp else 14.dp).roundToPx() }
    val shown = preview ?: position
    val fraction = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val step = maxOf(SEEK_STEP_MS, duration / 100)
    val currentOnSeek by rememberUpdatedState(onSeek)

    // Télécommande : la position choisie est appliquée après une courte pause
    LaunchedEffect(preview) {
        val target = preview ?: return@LaunchedEffect
        delay(900)
        currentOnSeek(target)
        preview = null
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatDuration(shown),
            style = MonoLabel,
            color = if (preview != null) Cm.Cyan else Cm.Text,
            modifier = Modifier.width(64.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(40.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || duration <= 0) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            preview = (shown - step).coerceAtLeast(0)
                            onInteract()
                            true
                        }
                        Key.DirectionRight -> {
                            preview = (shown + step).coerceAtMost(duration)
                            onInteract()
                            true
                        }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            preview?.let { currentOnSeek(it) }
                            preview = null
                            true
                        }
                        else -> false
                    }
                }
                .focusable(interactionSource = interaction)
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        if (duration > 0) {
                            currentOnSeek((offset.x / size.width * duration).toLong().coerceIn(0, duration))
                            onInteract()
                        }
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { onInteract() },
                        onDragEnd = {
                            preview?.let { currentOnSeek(it) }
                            preview = null
                        },
                        onDragCancel = { preview = null },
                        onHorizontalDrag = { change, _ ->
                            if (duration > 0) {
                                preview = (change.position.x / size.width * duration).toLong().coerceIn(0, duration)
                                onInteract()
                            }
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val trackHeight = if (focused) 8.dp else 4.dp
            Box(Modifier.fillMaxWidth().height(trackHeight).background(Cm.Outline, RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth(fraction).height(trackHeight).background(Cm.Cyan, RoundedCornerShape(4.dp)))
            Box(
                Modifier
                    .offset { IntOffset((fraction * widthPx).toInt() - thumbPx / 2, 0) }
                    .size(if (focused) 20.dp else 14.dp)
                    .background(Cm.Cyan, CircleShape)
                    .then(if (focused) Modifier.border(3.dp, LocalFocusColor.current, CircleShape) else Modifier)
            )
        }
        Text(
            if (duration > 0) formatDuration(duration) else "--:--",
            style = MonoLabel,
            color = Cm.TextMuted,
            modifier = Modifier.width(64.dp).padding(start = 10.dp),
        )
    }
}

private fun seekLabel(delta: Long): String {
    val sign = if (delta < 0) "−" else "+"
    val abs = kotlin.math.abs(delta)
    return if (abs >= 60_000) "$sign ${abs / 60_000} min" else "$sign ${abs / 1000} s"
}

@Composable
private fun EndCard(nextLabel: String?, autoNext: Boolean, onReplay: () -> Unit, onNext: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("LECTURE TERMINÉE", style = MonoLabel, color = Cm.Cyan)
        if (nextLabel != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (autoNext) "Épisode suivant dans 5 s : $nextLabel" else "Épisode suivant : $nextLabel",
                color = Cm.Text,
                fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(20.dp))
        Row {
            OutlinedButton(onClick = onReplay) { Text("Revoir") }
            if (onNext != null) {
                Spacer(Modifier.width(12.dp))
                Button(onClick = onNext) { Text("Suivant") }
            }
        }
    }
}

private fun statusLabel(status: PlayerStatus): String = when (status) {
    PlayerStatus.Loading -> "CHARGEMENT"
    PlayerStatus.Playing -> "● LECTURE"
    PlayerStatus.Ended -> "TERMINÉ"
    is PlayerStatus.Error -> "ERREUR"
}

@Composable
private fun ChannelButton(label: String, description: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, style = MonoLabel, fontSize = 14.sp, color = Cm.Cyan)
    }
}

/** Bandeau affiché quelques secondes après un changement de chaîne, commandes masquées. */
@Composable
private fun ZapBanner(channel: Channel, index: Int, current: Programme?) {
    Row(
        Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Start))
            .padding(24.dp)
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (channel.number ?: (index + 1)).toString(),
            color = Cm.Cyan,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            style = MonoLabel,
        )
        Spacer(Modifier.width(14.dp))
        ChannelLogo(channel.logo, channel.name, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(channel.name, color = Cm.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (current != null) {
                Text(
                    "${formatClock(current.start)} · ${current.title}",
                    color = Cm.TextMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
