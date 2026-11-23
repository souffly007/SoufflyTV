package fr.bonobo.soufflytv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import fr.bonobo.soufflytv.data.estimateFrameRate
import java.util.concurrent.atomic.AtomicBoolean
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import fr.bonobo.soufflytv.data.DnsMode
import fr.bonobo.soufflytv.platform.SecureDns
import java.util.concurrent.TimeUnit
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import fr.bonobo.soufflytv.data.DEFAULT_USER_AGENT
import fr.bonobo.soufflytv.model.StreamRequest
import fr.bonobo.soufflytv.platform.findActivity
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayer(
    request: StreamRequest,
    controller: PlayerController,
    options: PlayerOptions,
    modifier: Modifier,
    onStatus: (PlayerStatus) -> Unit,
) {
    val context = LocalContext.current
    val currentOnStatus by rememberUpdatedState(onStatus)
    val player = remember(request) { buildPlayer(context, request, options) }

    DisposableEffect(player) {
        // Nouvelle chaîne : les pistes de la précédente ne valent plus
        controller.audioTracks = emptyList()
        controller.textTracks = emptyList()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> currentOnStatus(PlayerStatus.Loading)
                    Player.STATE_READY -> currentOnStatus(PlayerStatus.Playing)
                    Player.STATE_ENDED -> currentOnStatus(PlayerStatus.Ended)
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                controller.isPlaying = isPlaying
            }

            override fun onTracksChanged(tracks: Tracks) {
                controller.audioTracks = trackOptions(tracks, C.TRACK_TYPE_AUDIO)
                controller.textTracks = trackOptions(tracks, C.TRACK_TYPE_TEXT)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Direct trop en retard : on se recale sur le bord du live
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                currentOnStatus(PlayerStatus.Error(describe(error)))
            }
        }
        player.addListener(listener)

        // AFR : fréquence annoncée par le flux, sinon mesurée sur les premières images, puis mode de la TV
        val activity = context.findActivity()
        val frameTimes = ArrayList<Long>()
        val decided = AtomicBoolean(!options.autoFrameRate || activity == null)
        val mainHandler = Handler(Looper.getMainLooper())
        fun decide(fps: Float) {
            if (decided.getAndSet(true)) return
            mainHandler.post { activity?.let { runCatching { FrameRateSwitcher.apply(it, fps) } } }
        }
        val frameListener = VideoFrameMetadataListener { presentationTimeUs, _, format, _ ->
            if (decided.get()) return@VideoFrameMetadataListener
            if (format.frameRate > 0f) {
                decide(format.frameRate)
                return@VideoFrameMetadataListener
            }
            frameTimes += presentationTimeUs
            if (frameTimes.size >= 48) {
                val fps = estimateFrameRate(frameTimes)
                when {
                    fps != null -> decide(fps)
                    frameTimes.size > 240 -> decided.set(true) // fréquence introuvable : on n'y touche pas
                }
            }
        }
        if (!decided.get()) player.setVideoFrameMetadataListener(frameListener)

        controller.attach(object : PlayerImpl {
            override fun togglePause() {
                if (player.isPlaying) player.pause() else player.play()
            }

            override fun seekTo(ms: Long) = player.seekTo(ms)

            override fun selectAudio(id: String) = selectTrack(player, C.TRACK_TYPE_AUDIO, id)

            override fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)

            override fun selectText(id: String?) {
                if (id == null) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                } else {
                    selectTrack(player, C.TRACK_TYPE_TEXT, id)
                }
            }
        })

        // Application en arrière-plan (hors image dans l'image) : pause, puis reprise au retour
        val lifecycle = (context.findActivity() as? LifecycleOwner)?.lifecycle
        var resumeOnStart = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resumeOnStart = player.playWhenReady
                    player.pause()
                }
                Lifecycle.Event.ON_START -> if (resumeOnStart) {
                    resumeOnStart = false
                    player.play()
                }
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)

        onDispose {
            lifecycle?.removeObserver(observer)
            controller.detach()
            player.clearVideoFrameMetadataListener(frameListener)
            mainHandler.removeCallbacksAndMessages(null)
            player.removeListener(listener)
            player.release()
        }
    }

    // Position et durée pour la barre de lecture (films, épisodes)
    LaunchedEffect(player) {
        while (true) {
            controller.positionMs = player.currentPosition.coerceAtLeast(0)
            val duration = player.duration
            controller.durationMs = if (!options.live && duration != C.TIME_UNSET && duration > 0) duration else 0
            delay(500)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                keepScreenOn = true
                // Le focus reste à Compose (télécommande)
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        update = { view ->
            view.player = player
            view.resizeMode = when (controller.aspect) {
                AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectMode.WIDE -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        onRelease = { view -> view.player = null },
    )
}

@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, request: StreamRequest, options: PlayerOptions): ExoPlayer {
    val userAgent = request.headers["User-Agent"] ?: DEFAULT_USER_AGENT
    val http: DataSource.Factory = if (SecureDns.mode != DnsMode.SYSTEM) {
        // DNS sécurisé : la vidéo passe par OkHttp, avec le DNS chiffré choisi dans les réglages
        OkHttpDataSource.Factory(
            SecureDns.client.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        )
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(request.headers - "User-Agent")
    } else {
        DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(request.headers - "User-Agent")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
    }

    // minBuffer, maxBuffer, tampon avant lecture, tampon après coupure (ms)
    val (minMs, maxMs, startMs, rebufferMs) = when (options.buffer) {
        BufferMode.LOW -> listOf(5_000, 20_000, 1_000, 2_000)
        BufferMode.NORMAL -> listOf(15_000, 50_000, 2_500, 5_000)
        BufferMode.HIGH -> listOf(30_000, 120_000, 5_000, 10_000)
    }
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(minMs, maxMs, startMs, rebufferMs)
        .build()

    // Décodeurs : ceux de l'appareil d'abord (matériels, économes), puis FFmpeg en secours.
    // Beaucoup de tablettes n'ont pas de décodeur Dolby (AC3 / E-AC3) ni DTS : sans FFmpeg,
    // les films et séries s'y lisent sans le son, alors que le direct (souvent en AAC) en a.
    val renderers = DefaultRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)

    return ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
        .setLoadControl(loadControl)
        .build()
        .apply {
            val item = MediaItem.Builder().setUri(request.url)
            // Les liens HLS sans extension .m3u8 dans le chemin (…?extension=m3u8) doivent être signalés
            if (request.url.contains("m3u8", ignoreCase = true)) item.setMimeType(MimeTypes.APPLICATION_M3U8)
            setMediaItem(item.build())
            // Sous-titres désactivés par défaut, comme sur un téléviseur
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(Locale.getDefault().language)
                .setPreferredTextLanguage(null)
                .build()
            playWhenReady = true
            prepare()
        }
}

@OptIn(UnstableApi::class)
private fun trackOptions(tracks: Tracks, type: Int): List<TrackOption> {
    val result = ArrayList<TrackOption>()
    tracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != type) return@forEachIndexed
        for (trackIndex in 0 until group.length) {
            if (!group.isTrackSupported(trackIndex)) continue
            val format = group.getTrackFormat(trackIndex)
            val described = type == C.TRACK_TYPE_AUDIO && (
                (format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0 || isAudioDescription(format.label, format.language)
            )
            val label = trackLabel(format, result.size + 1, described)
            result += TrackOption(
                id = "$groupIndex:$trackIndex",
                label = label,
                selected = group.isTrackSelected(trackIndex),
                language = format.language?.takeIf { it.isNotBlank() && it != "und" },
                audioDescription = described,
            )
        }
    }
    return result
}

@OptIn(UnstableApi::class)
private fun trackLabel(format: Format, number: Int, audioDescription: Boolean = false): String {
    val language = format.language
        ?.takeIf { it.isNotBlank() && it != "und" && it != "qad" }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.FRENCH) }
        ?.replaceFirstChar { it.uppercase() }
    val channels = when (format.channelCount) {
        1 -> "mono"
        2 -> "stéréo"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }
    return listOfNotNull(if (audioDescription) "Audiodescription" else null, format.label, language, channels)
        .distinct()
        .joinToString(" · ")
        .ifEmpty { "Piste $number" }
}

@OptIn(UnstableApi::class)
private fun selectTrack(player: ExoPlayer, type: Int, id: String) {
    val (groupIndex, trackIndex) = id.split(':').map { it.toInt() }
    val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(type, false)
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
        .build()
}

private fun describe(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Le serveur refuse le flux (erreur HTTP)"
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Flux injoignable (réseau)"
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Format vidéo non pris en charge"
    else -> "Lecture impossible (${error.errorCodeName.removePrefix("ERROR_CODE_")})"
}

actual fun restoreDisplayMode() = FrameRateSwitcher.restore()
