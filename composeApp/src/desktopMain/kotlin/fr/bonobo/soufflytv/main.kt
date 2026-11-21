package fr.bonobo.soufflytv

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

fun main() {
    // Menus et boîtes de dialogue dans leur propre fenêtre, pour passer au-dessus de la vidéo libVLC
    System.setProperty("compose.layers.type", "WINDOW")

    application {
        val state = remember { AppState() }
        val windowState = rememberWindowState(width = 1200.dp, height = 760.dp)

        DisposableEffect(Unit) {
            state.setFullscreen = { on ->
                windowState.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
            }
            state.toggleFullscreen = {
                state.setFullscreen?.invoke(windowState.placement != WindowPlacement.Fullscreen)
            }
            onDispose { state.close() }
        }
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.placement }.collect { state.isFullscreen = it == WindowPlacement.Fullscreen }
        }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "SoufflyTV",
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else when {
                    event.key == Key.Escape && windowState.placement == WindowPlacement.Fullscreen -> {
                        windowState.placement = WindowPlacement.Floating
                        true
                    }
                    event.key == Key.Escape -> state.back()
                    event.key == Key.F && state.screen is Screen.Player -> {
                        state.toggleFullscreen?.invoke()
                        true
                    }
                    else -> false
                }
            },
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(820, 540) }
            App(state)
        }
    }
}
