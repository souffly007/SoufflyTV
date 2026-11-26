package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay

/*
 * Accueil smartphone, façon maquette : carrousel « À la une » et grande carte « Continuer à regarder ».
 */

/** Carrousel des films à la une : on fait glisser, ou il avance tout seul ; petits points en dessous. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhoneFeaturedCarousel(state: AppState, source: Source, model: BrowserModel, items: List<Channel>) {
    val pager = rememberPagerState { items.size }
    LaunchedEffect(pager, items.size) {
        while (items.size > 1) {
            delay(8_000)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 12.dp),
            pageSpacing = 10.dp,
        ) { page ->
            PhoneFeaturedCard(state, source, model, items[page])
        }
        if (items.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.indices.forEach { i ->
                    val current = i == pager.currentPage
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (current) 18.dp else 7.dp, height = 7.dp)
                            .background(if (current) Cm.Cyan else Cm.TextMuted.copy(alpha = 0.5f), CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhoneFeaturedCard(state: AppState, source: Source, model: BrowserModel, item: Channel) {
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
    val series = item.type == ItemType.SERIES
    val shape = RoundedCornerShape(20.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .height(250.dp)
            .clip(shape)
            .background(Cm.Surface)
            .border(1.dp, Cm.Cyan.copy(alpha = 0.45f), shape),
    ) {
        val image = info.backdrop ?: info.cover
        if (image != null) {
            AsyncImage(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Cm.Background.copy(alpha = 0.95f), 0.55f to Cm.Background.copy(alpha = 0.55f), 1f to Color.Transparent)
            )
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Cm.Background.copy(alpha = 0.9f))))
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Bottom) {
            Text(info.name, color = Cm.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 28.sp)
            Spacer(Modifier.height(8.dp))
            MetaChips(info.meta)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (series) state.openItem(source, listOf(item), 0) else state.play(source, listOf(item), 0)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cm.Cyan, contentColor = Cm.OnCyan),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            series -> "Voir"
                            saved != null -> "Continuer"
                            else -> "Lire"
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (saved != null && !series) {
                            state.library.setPosition(key, 0)
                            state.play(source, listOf(item), 0)
                        } else {
                            state.openItem(source, listOf(item), 0)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        if (saved != null && !series) "↺ Du début" else "Infos",
                        color = Cm.Text,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Grande carte « Continuer à regarder » : image, pourcentage, temps restant, Continuer / Reprendre du début. */
@Composable
internal fun PhoneContinueCard(
    item: Channel,
    progress: Float?,
    position: Long?,
    onSeeAll: () -> Unit,
    /** Image ou titre touchés : fiche de la série (sur le bon épisode) ou du film. */
    onOpen: () -> Unit,
    onContinue: () -> Unit,
    onRestart: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .background(Cm.Surface.copy(alpha = 0.85f), shape)
            .border(1.dp, Cm.Cyan.copy(alpha = 0.35f), shape)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(18.dp).background(Cm.Cyan, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Text("Continuer à regarder", color = Cm.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                "Tout voir ›",
                color = Cm.Cyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.tvClickable(onClick = onSeeAll).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row {
            Box(
                Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Cm.SurfaceHigher)
                    .tvClickable(onClick = onOpen),
            ) {
                ContinueImage(item, Modifier.fillMaxSize())
                progress?.let {
                    Text(
                        "${(it * 100).toInt()} %",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f))) {
                        Box(Modifier.fillMaxWidth(it).fillMaxHeight().background(Cm.Cyan))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).tvClickable(onClick = onOpen)) {
                Text(continueTitle(item), color = Cm.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(continueKind(item), style = MonoLabel, color = Cm.Cyan)
                Spacer(Modifier.height(4.dp))
                remainingLabel(progress, position)?.let { Text(it, style = MonoLabel, color = Cm.TextMuted) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = Cm.Cyan, contentColor = Cm.OnCyan),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(position?.let { "Continuer (${formatDuration(it)})" } ?: "Continuer", fontWeight = FontWeight.Bold, maxLines = 1)
            }
            OutlinedButton(onClick = onRestart, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                Text("↺ Reprendre du début", color = Cm.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** « 44 min restantes », calculé à partir de la position et de la part déjà vue. */
internal fun remainingLabel(progress: Float?, position: Long?): String? {
    if (progress == null || position == null || progress <= 0f) return null
    val total = (position / progress).toLong()
    val minutes = ((total - position) / 60_000).coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} restantes" else "$minutes min restantes"
}
