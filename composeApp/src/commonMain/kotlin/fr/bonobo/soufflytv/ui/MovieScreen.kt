package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.data.formatDuration
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.MovieInfo
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.platform.isTvBuild
import fr.bonobo.soufflytv.platform.rememberUrlOpener
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.MonoLabel
import kotlinx.coroutines.CancellationException

/**
 * Fiche d'un film : affiche, année · genre · durée · note, résumé, réalisation, distribution,
 * puis « Lecture » (ou « Reprendre » + « Depuis le début » si le film a déjà été commencé).
 */
@Composable
fun MovieScreen(state: AppState, source: Source, channels: List<Channel>, index: Int) {
    val movie = channels[index.coerceIn(0, channels.lastIndex)]
    val provider = remember(source) { state.provider(source) }
    // Ce que la liste donne déjà s'affiche tout de suite, la fiche complète arrive ensuite
    var info by remember(movie.id) { mutableStateOf(MovieInfo(movie.name, movie.plot, movie.logo)) }
    var loading by remember(movie.id) { mutableStateOf(true) }
    val favorite = state.library.isFavorite(source.id, movie.id)
    val resumeKey = "${source.id}|${movie.id}"
    val saved = state.library.position(resumeKey)?.takeIf { it > 30_000 }
    val progress = state.library.progress(resumeKey)
    val watched = state.library.isWatched(resumeKey)

    val openUrl = rememberUrlOpener()
    var trailerError by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    var playFocused by remember { mutableStateOf(false) }
    KeepFocus(playFocus, movie.id, isFocused = { playFocused })

    LaunchedEffect(movie.id) {
        try {
            info = provider.movieInfo(movie)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // la fiche de base reste affichée
        } finally {
            loading = false
        }
    }

    fun play(fromStart: Boolean) {
        if (fromStart) state.library.setPosition(resumeKey, 0)
        state.play(source, channels, index)
    }

    val canPlayTrailer = info.trailer != null
    Box(Modifier.fillMaxSize().background(Cm.Background)) {
        DetailBackdrop(info.backdrop ?: info.cover)

        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = "Détail du film",
                subtitle = "FILM",
                onBack = if (isTvBuild) null else ({ state.back() }),
            )

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 600.dp
                val withInfo = maxWidth >= 860.dp
                val buttonsWidth = if (wide) Modifier.width(320.dp) else Modifier.fillMaxWidth()

                // Titre, infos, résumé, progression puis boutons empilés (Lire / Continuer, Du début, Ma liste, Bande-annonce)
                val main: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier) {
                        Text(
                            info.name,
                            color = Cm.Text,
                            fontSize = if (wide) 28.sp else 21.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = if (wide) 32.sp else 25.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        MetaChips(info.meta)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            info.plot?.takeIf { it.isNotBlank() }
                                ?: if (loading) "Chargement du résumé…" else "Pas de résumé pour ce film.",
                            color = if (info.plot.isNullOrBlank()) Cm.TextMuted else Cm.Text,
                            fontSize = if (wide) 15.sp else 14.sp,
                            lineHeight = if (wide) 22.sp else 20.sp,
                            maxLines = if (wide) 6 else 10,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(16.dp))
                        if (saved != null && progress != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Progression : ${(progress * 100).toInt()} %", color = Cm.TextMuted, fontSize = 13.sp)
                                Spacer(Modifier.width(10.dp))
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.width(160.dp).height(4.dp),
                                    color = Cm.Cyan,
                                    trackColor = Cm.SurfaceHigher,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("arrêté à ${formatDuration(saved)}", color = Cm.TextMuted, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(12.dp))
                        } else if (watched) {
                            Text("✓ Déjà vu", color = Cm.Success, fontSize = 13.sp)
                            Spacer(Modifier.height(12.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            DetailPrimaryButton(
                                if (saved != null) "Continuer la lecture (${formatDuration(saved)})" else "Lire",
                                AppIcons.Play,
                                buttonsWidth.focusRequester(playFocus).onFocusChanged { playFocused = it.isFocused },
                            ) { play(fromStart = false) }
                            if (saved != null) {
                                DetailButton("↺  Reprendre du début", buttonsWidth) { play(fromStart = true) }
                            }
                            DetailButton(
                                if (favorite) "Dans ma liste" else "Ajouter à ma liste",
                                buttonsWidth,
                                icon = if (favorite) AppIcons.Star else AppIcons.StarBorder,
                                tint = if (favorite) Cm.Cyan else Cm.Text,
                            ) { state.library.toggleFavorite(source.id, movie) }
                            if (canPlayTrailer) {
                                DetailButton("Bande-annonce", buttonsWidth, icon = AppIcons.Play) {
                                    trailerError = !openUrl(info.trailer!!)
                                }
                            }
                        }
                        if (trailerError) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Impossible d'ouvrir la bande-annonce : aucune application YouTube ni navigateur sur cet appareil",
                                color = Cm.Error,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }

                if (wide) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 32.dp, vertical = 16.dp),
                    ) {
                        DetailPoster(info.cover, 200.dp, AppIcons.Movie)
                        Spacer(Modifier.width(28.dp))
                        main(Modifier.weight(1f))
                        if (withInfo && (info.director != null || info.cast != null)) {
                            Spacer(Modifier.width(24.dp))
                            InfoColumn(info.director, info.cast, modifier = Modifier.width(240.dp))
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        Row {
                            DetailPoster(info.cover, 120.dp, AppIcons.Movie)
                        }
                        Spacer(Modifier.height(16.dp))
                        main(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(16.dp))
                        InfoColumn(info.director, info.cast, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}


