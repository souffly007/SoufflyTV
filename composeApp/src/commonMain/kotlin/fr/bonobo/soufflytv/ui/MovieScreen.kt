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

    Box(Modifier.fillMaxSize().background(Cm.Background)) {
        // Grande image en fond, très atténuée
        val backdrop = info.backdrop ?: info.cover
        if (backdrop != null) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(0.18f),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Cm.Background.copy(alpha = 0.4f), Cm.Background)),
                ),
            )
        }

        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = movie.name,
                subtitle = "FILM",
                onBack = if (isTvBuild) null else ({ state.back() }),
                actions = {
                    IconButton(onClick = { state.library.toggleFavorite(source.id, movie) }) {
                        Icon(
                            if (favorite) AppIcons.Star else AppIcons.StarBorder,
                            contentDescription = if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
                            tint = if (favorite) Cm.Cyan else Cm.TextMuted,
                        )
                    }
                },
            )

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 600.dp
                val posterWidth = if (wide) 180.dp else 110.dp
                Row(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = if (wide) 32.dp else 16.dp, vertical = 12.dp),
                ) {
                    Poster(info.cover, Modifier.width(posterWidth).height(posterWidth * 1.5f))
                    Spacer(Modifier.width(if (wide) 28.dp else 16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(info.name, color = Cm.Text, fontSize = if (wide) 26.sp else 20.sp, fontWeight = FontWeight.Bold)
                        if (info.meta != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(info.meta!!, style = MonoLabel, color = Cm.Cyan)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            info.plot?.takeIf { it.isNotBlank() }
                                ?: if (loading) "Chargement du résumé…" else "Pas de résumé pour ce film.",
                            color = if (info.plot.isNullOrBlank()) Cm.TextMuted else Cm.Text,
                            fontSize = if (wide) 16.sp else 14.sp,
                            lineHeight = if (wide) 23.sp else 20.sp,
                            maxLines = if (wide) 8 else 12,
                            overflow = TextOverflow.Ellipsis,
                        )
                        info.director?.let { Credit("Réalisation", it) }
                        info.cast?.let { Credit("Avec", it) }

                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { play(fromStart = false) },
                                colors = ButtonDefaults.buttonColors(containerColor = Cm.Cyan, contentColor = Cm.OnCyan),
                                modifier = Modifier
                                    .focusRequester(playFocus)
                                    .onFocusChanged { playFocused = it.isFocused },
                            ) {
                                Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (saved != null) "Reprendre à ${formatDuration(saved)}" else "Lecture",
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            if (saved != null) {
                                OutlinedButton(onClick = { play(fromStart = true) }) { Text("Depuis le début") }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Poster(url: String?, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Cm.SurfaceHigher),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.Movie, contentDescription = null, tint = Cm.TextMuted, modifier = Modifier.size(40.dp))
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun Credit(label: String, value: String) {
    Spacer(Modifier.height(8.dp))
    Row {
        Text("$label : ", color = Cm.TextMuted, fontSize = 13.sp)
        Text(value, color = Cm.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
