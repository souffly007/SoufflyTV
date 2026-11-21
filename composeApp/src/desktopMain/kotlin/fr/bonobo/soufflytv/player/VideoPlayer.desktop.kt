package fr.bonobo.soufflytv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import fr.bonobo.soufflytv.model.StreamRequest
import kotlinx.coroutines.delay
import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy
import uk.co.caprica.vlcj.player.component.InputEvents
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JComponent
import javax.swing.SwingUtilities

private const val VLC_MISSING =
    "VLC est introuvable. Installe VLC 3 (paquet du système, pas la version Flatpak) " +
        "pour la lecture sur ordinateur."

private val isWindows = System.getProperty("os.name").lowercase().contains("win")
private val isMac = System.getProperty("os.name").lowercase().contains("mac")

/**
 * libVLC livrée avec l'application (installeur Windows) : <ressources de l'app>/vlc/libvlc.dll.
 * Sinon on utilise celle du système (Linux, Flatpak, VLC installé).
 */
private val bundledVlcDir: File? by lazy {
    val lib = when {
        isWindows -> "libvlc.dll"
        isMac -> "libvlc.dylib"
        else -> "libvlc.so"
    }
    val candidates = listOfNotNull(
        // Flatpak : dossier indiqué par le manifeste (/app/lib)
        System.getenv("SOUFFLYTV_VLC_DIR")?.let { File(it) },
        // installeur Windows : ressources de l'application
        System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") },
    )
    candidates.firstOrNull { File(it, lib).exists() }
}

/** Raison de l'échec du chargement de libVLC, affichée dans le message d'erreur. */
@Volatile
private var vlcProblem: String? = null

/**
 * Une seule instance libVLC pour toute la durée de l'application :
 * le chargement des modules ne se refait pas à chaque changement de chaîne.
 */
private val vlcFactory: MediaPlayerFactory? by lazy {
    bundledVlcDir?.let { dir ->
        try {
            val path = dir.absolutePath
            NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), path)
            NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), path)
            // Windows : libvlccore d'abord, puis libvlc, depuis le dossier livré avec l'application
            // (libvlc.dll ne trouve pas toujours sa dépendance toute seule)
            NativeLibrary.getInstance(RuntimeUtil.getLibVlcCoreLibraryName())
            NativeLibrary.getInstance(RuntimeUtil.getLibVlcLibraryName())
            // pas de recherche du VLC du système : il ne doit pas se mélanger avec celui livré
            return@lazy MediaPlayerFactory(NativeDiscovery(*arrayOf<NativeDiscoveryStrategy>()), "--no-video-title-show")
        } catch (e: Throwable) {
            vlcProblem = "libVLC livrée dans $dir : ${e.message ?: e::class.simpleName}"
        }
    }
    // Sinon : VLC installé sur le système
    try {
        val discovery = NativeDiscovery()
        if (discovery.discover()) {
            MediaPlayerFactory(discovery, "--no-video-title-show")
        } else {
            if (vlcProblem == null) {
                val resources = System.getProperty("compose.application.resources.dir")
                vlcProblem = if (resources != null) "pas de libVLC dans $resources${File.separator}vlc" else null
            }
            null
        }
    } catch (e: Throwable) {
        if (vlcProblem == null) vlcProblem = e.message ?: e::class.simpleName
        null
    }
}

private class Vlc(
    val panel: JComponent,
    val surface: java.awt.Component,
    val player: EmbeddedMediaPlayer,
    val release: () -> Unit,
)

private fun createVlc(): Vlc? {
    val factory = vlcFactory ?: return null
    return try {
        val vlc = if (isMac) {
            val c = CallbackMediaPlayerComponent(factory, null, InputEvents.NONE, true, null)
            c.videoSurfaceComponent().isFocusable = false
            Vlc(c, c.videoSurfaceComponent(), c.mediaPlayer()) { c.release() }
        } else {
            // Rendu natif (le plus économe) sous Linux (X11 / XWayland) et Windows
            val c = EmbeddedMediaPlayerComponent(factory, null, null, InputEvents.NONE, null)
            c.videoSurfaceComponent().isFocusable = false
            Vlc(c, c.videoSurfaceComponent(), c.mediaPlayer()) { c.release() } // libère le lecteur, pas l'instance partagée
        }
        vlc.panel.isFocusable = false // le clavier reste à Compose
        vlc
    } catch (e: Throwable) {
        null
    }
}

@Composable
actual fun VideoPlayer(
    request: StreamRequest,
    controller: PlayerController,
    options: PlayerOptions,
    modifier: Modifier,
    onStatus: (PlayerStatus) -> Unit,
) {
    val currentOnStatus by rememberUpdatedState(onStatus)
    val vlc = remember { createVlc() }

    if (vlc == null) {
        LaunchedEffect(Unit) {
            val detail = vlcProblem?.let { "\n($it)" } ?: ""
            currentOnStatus(PlayerStatus.Error(VLC_MISSING + detail))
        }
        Box(modifier.background(Color.Black))
        return
    }

    DisposableEffect(vlc) {
        // Nouvelle chaîne : les pistes de la précédente ne valent plus
        controller.audioTracks = emptyList()
        controller.textTracks = emptyList()
        val listener = object : MediaPlayerEventAdapter() {
            override fun buffering(mediaPlayer: MediaPlayer?, newCache: Float) {
                post(if (newCache < 100f) PlayerStatus.Loading else PlayerStatus.Playing)
            }

            override fun playing(mediaPlayer: MediaPlayer?) = post(PlayerStatus.Playing)

            override fun finished(mediaPlayer: MediaPlayer?) = post(PlayerStatus.Ended)

            override fun error(mediaPlayer: MediaPlayer?) =
                post(PlayerStatus.Error("Lecture impossible : flux injoignable ou format non pris en charge"))

            // Les événements libVLC arrivent sur un thread natif : retour sur le thread de l'interface
            private fun post(status: PlayerStatus) = SwingUtilities.invokeLater { currentOnStatus(status) }
        }
        vlc.player.events().addMediaPlayerEventListener(listener)

        // Souris sur la vidéo : afficher les commandes (plein écran), double-clic = plein écran
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                controller.onSurfaceActivity?.invoke()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) controller.onSurfaceDoubleClick?.invoke() else controller.onSurfaceActivity?.invoke()
            }
        }
        listOf(vlc.surface, vlc.panel).distinct().forEach {
            it.addMouseListener(mouse)
            it.addMouseMotionListener(mouse)
        }

        controller.attach(object : PlayerImpl {
            override fun togglePause() = vlc.player.controls().setPause(vlc.player.status().isPlaying)

            override fun seekTo(ms: Long) = vlc.player.controls().setTime(ms)

            override fun selectAudio(id: String) {
                id.toIntOrNull()?.let { vlc.player.audio().setTrack(it) }
            }

            override fun selectText(id: String?) {
                vlc.player.subpictures().setTrack(id?.toIntOrNull() ?: -1)
            }

            override fun setSpeed(speed: Float) {
                vlc.player.controls().setRate(speed)
            }
        })

        onDispose {
            listOf(vlc.surface, vlc.panel).distinct().forEach {
                it.removeMouseListener(mouse)
                it.removeMouseMotionListener(mouse)
            }
            controller.detach()
            vlc.player.events().removeMediaPlayerEventListener(listener)
            vlc.player.controls().stop()
            vlc.release()
        }
    }

    LaunchedEffect(vlc, request) {
        // La surface vidéo doit être affichée avant de lancer la lecture
        var waited = 0
        while (!vlc.panel.isDisplayable && waited < 5_000) {
            delay(50)
            waited += 50
        }
        val caching = when (options.buffer) {
            BufferMode.LOW -> 800
            BufferMode.NORMAL -> 1_500
            BufferMode.HIGH -> 5_000
        }
        val mediaOptions = buildList {
            request.headers["User-Agent"]?.let { add(":http-user-agent=$it") }
            request.headers["Referer"]?.let { add(":http-referrer=$it") }
            add(":network-caching=$caching")
        }
        vlc.player.media().play(request.url, *mediaOptions.toTypedArray())
    }

    // Position, durée, pistes et format d'image
    LaunchedEffect(vlc) {
        var appliedAspect: String? = null
        while (true) {
            val player = vlc.player
            controller.isPlaying = player.status().isPlaying
            controller.positionMs = player.status().time().coerceAtLeast(0)
            val length = player.status().length()
            controller.durationMs = if (!options.live && length > 0) length else 0

            if (player.status().isPlaying) {
                val audioCurrent = player.audio().track()
                val audio = player.audio().trackDescriptions()
                    .filter { it.id() >= 0 }
                    .map {
                        val description = it.description() ?: "Piste ${it.id()}"
                        val described = isAudioDescription(description, null)
                        TrackOption(
                            id = it.id().toString(),
                            label = if (described && !description.contains("audiodescription", ignoreCase = true)) {
                                "Audiodescription · $description"
                            } else description,
                            selected = it.id() == audioCurrent,
                            language = LANGUAGE_IN_BRACKETS.find(description)?.groupValues?.get(1)?.lowercase(),
                            audioDescription = described,
                        )
                    }
                if (audio != controller.audioTracks) controller.audioTracks = audio

                val textCurrent = player.subpictures().track()
                val text = player.subpictures().trackDescriptions()
                    .filter { it.id() >= 0 }
                    .map { TrackOption(it.id().toString(), it.description() ?: "Sous-titres ${it.id()}", it.id() == textCurrent) }
                if (text != controller.textTracks) controller.textTracks = text

                // Format : calculé sur la taille réelle de la vidéo à l'écran (16:9, 16:10, 21:9…),
                // et recalculé quand elle change (plein écran, fenêtre redimensionnée)
                val aspect = controller.aspect
                val ratio = screenRatio(vlc.panel.width, vlc.panel.height)
                val applied = "$aspect|$ratio"
                if (applied != appliedAspect) {
                    when (aspect) {
                        AspectMode.FIT -> {
                            player.video().setAspectRatio(null)
                            player.video().setCropGeometry(null)
                        }
                        AspectMode.WIDE -> {
                            player.video().setCropGeometry(null)
                            player.video().setAspectRatio(ratio)
                        }
                        AspectMode.ZOOM -> {
                            player.video().setAspectRatio(null)
                            player.video().setCropGeometry(ratio)
                        }
                    }
                    appliedAspect = applied
                }
            }
            delay(500)
        }
    }

    SwingPanel(
        background = Color.Black,
        factory = { vlc.panel },
        modifier = modifier,
    )
}

/** VLC décrit une piste par « Piste 1 - [Français] » : la langue est entre crochets. */
private val LANGUAGE_IN_BRACKETS = Regex("""\[([^\]]+)]\s*$""")

/** Rapport largeur:hauteur simplifié, par exemple 1920×1080 → « 16:9 », 2560×1080 → « 64:27 ». */
private fun screenRatio(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "16:9"
    tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    val d = gcd(width, height)
    return "${width / d}:${height / d}"
}
