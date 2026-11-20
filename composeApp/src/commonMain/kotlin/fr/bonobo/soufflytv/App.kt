package fr.bonobo.soufflytv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fr.bonobo.soufflytv.ui.AddSourceScreen
import fr.bonobo.soufflytv.ui.BrowserScreen
import fr.bonobo.soufflytv.ui.ManageCategoriesScreen
import fr.bonobo.soufflytv.ui.MovieScreen
import fr.bonobo.soufflytv.ui.PlayerScreen
import fr.bonobo.soufflytv.ui.SeriesScreen
import fr.bonobo.soufflytv.ui.SettingsScreen
import fr.bonobo.soufflytv.ui.SourcesScreen
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.SoufflyTheme

@Composable
fun App(state: AppState) {
    SoufflyTheme {
        Surface(Modifier.fillMaxSize(), color = Cm.Background) {
            when (val screen = state.screen) {
                is Screen.Player -> PlayerScreen(state, screen)
                else -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    when (screen) {
                        Screen.Sources -> SourcesScreen(state)
                        is Screen.AddSource -> AddSourceScreen(state, screen.editing)
                        Screen.Settings -> SettingsScreen(state)
                        is Screen.Browse -> BrowserScreen(state, screen.source)
                        is Screen.Series -> SeriesScreen(state, screen.source, screen.series)
                        is Screen.Movie -> MovieScreen(state, screen.source, screen.channels, screen.index)
                        is Screen.ManageCategories -> ManageCategoriesScreen(state, screen.source, screen.section)
                        is Screen.Player -> Unit
                    }
                }
            }
        }
    }
}
