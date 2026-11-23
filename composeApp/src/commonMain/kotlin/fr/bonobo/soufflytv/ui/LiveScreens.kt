package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.bonobo.soufflytv.AppState
import fr.bonobo.soufflytv.model.Channel
import fr.bonobo.soufflytv.model.Programme
import fr.bonobo.soufflytv.model.Source
import fr.bonobo.soufflytv.model.StreamRequest
import fr.bonobo.soufflytv.platform.formatClock
import fr.bonobo.soufflytv.platform.nowMillis
import fr.bonobo.soufflytv.player.PlayerController
import fr.bonobo.soufflytv.player.PlayerOptions
import fr.bonobo.soufflytv.player.PlayerStatus
import fr.bonobo.soufflytv.player.VideoPlayer
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.MonoLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/*
 * TV en direct, au-delà de la simple liste :
 *  - « En ce moment » : les émissions en cours, en grandes cartes rangées par groupe ;
 *  - l'aperçu en direct : la chaîne survolée se lance en petit au-dessus de la liste.
 */

/** Heure courante, rafraîchie chaque minute (barres de progression des émissions). */
@Composable
private fun rememberClock(): Long {
    var clock by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            clock = nowMillis()
        }
    }
    return clock
}

/**
 * « En ce moment » : pour chaque groupe (dans l'ordre du fournisseur, catégories masquées exclues),
 * les chaînes dont le guide TV connaît l'émission en cours.
 */
@Composable
internal fun NowOnPage(state: AppState, source: Source, model: BrowserModel, channels: List<Channel>, modifier: Modifier) {
    val clock = rememberClock()
    val categories = model.categories
    val groups = remember(channels, model.epgVersion, clock, categories) {
        val order = categories.withIndex().associate { (i, c) -> c.id to i }
        val names = categories.associate { it.id to it.name }
        channels.mapNotNull { ch -> model.provider.epgNow(ch)?.let { ch to it } }
            .groupBy { it.first.categoryId }
            .entries
            .sortedBy { order[it.key] ?: Int.MAX_VALUE }
            .map { (cat, list) -> (names[cat] ?: "Autres chaînes") to list.take(NOW_ROW) }
            .take(NOW_GROUPS)
    }

    if (groups.isEmpty()) {
        EmptyHint(
            if (model.epgVersion == 0) "Chargement du guide TV…"
            else "Aucune émission en cours trouvée dans le guide TV pour ces chaînes.",
        )
        return
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        groups.forEach { (name, items) ->
            Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(16.dp).background(Cm.Cyan, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(10.dp))
                Text(name, color = Cm.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text("${items.size}", style = MonoLabel, color = Cm.TextMuted)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val list = items.map { it.first }
                items.forEachIndexed { i, (channel, programme) ->
                    NowCard(channel, programme, clock) { state.play(source, list, i) }
                }
            }
        }
    }
}

private const val NOW_ROW = 24
private const val NOW_GROUPS = 16

/** Carte d'émission en cours : chaîne, titre, progression, horaires. */
@Composable
private fun NowCard(channel: Channel, programme: Programme, clock: Long, onClick: () -> Unit) {
    Column(
        Modifier
            .width(236.dp)
            .padding(4.dp)
            .tvClickable(onClick = onClick)
            .background(Cm.Surface.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel.logo, channel.name, 34.dp)
            Spacer(Modifier.width(10.dp))
            Text(channel.name, color = Cm.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            programme.title,
            color = Cm.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { programme.progress(clock) },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = Cm.Cyan,
            trackColor = Cm.SurfaceHigher,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "${formatClock(programme.start)} – ${formatClock(programme.stop)} · ${remaining(programme, clock)}",
            style = MonoLabel,
            color = Cm.TextMuted,
            maxLines = 1,
        )
    }
}

/** « reste 42 min » */
private fun remaining(programme: Programme, now: Long): String {
    val minutes = ((programme.stop - now) / 60_000).coerceAtLeast(0)
    return if (minutes >= 60) "reste ${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}" else "reste $minutes min"
}

/**
 * Aperçu en direct (grand écran, télécommande) : la chaîne survolée dans la liste se lance en petit,
 * avec l'émission en cours, sa progression et la suivante. OK sur la chaîne : plein écran.
 */
@Composable
internal fun LivePreview(state: AppState, model: BrowserModel, channel: Channel, index: Int, modifier: Modifier) {
    val clock = rememberClock()
    var request by remember { mutableStateOf<StreamRequest?>(null) }
    var status by remember { mutableStateOf<PlayerStatus>(PlayerStatus.Loading) }
    var next by remember(channel.id) { mutableStateOf<Programme?>(null) }
    val now = remember(channel.id, model.epgVersion, clock) { model.provider.epgNow(channel) }

    // Zapping rapide dans la liste : on attend un peu avant de lancer le flux
    LaunchedEffect(channel.id) {
        request = null
        status = PlayerStatus.Loading
        delay(PREVIEW_DELAY_MS)
        request = try {
            model.provider.resolve(channel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            status = PlayerStatus.Error("Aperçu indisponible")
            null
        }
    }
    LaunchedEffect(channel.id, now?.stop) {
        delay(PREVIEW_DELAY_MS)
        next = try {
            val after = now?.stop ?: nowMillis()
            model.provider.epg(channel).firstOrNull { it.start >= after }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    Row(modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .border(1.dp, Cm.Outline, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (status != PlayerStatus.Playing) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ChannelLogo(channel.logo, channel.name, 56.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        (status as? PlayerStatus.Error)?.message ?: "Aperçu…",
                        style = MonoLabel,
                        color = Cm.TextMuted,
                    )
                }
            }
            request?.let { req ->
                key(req) {
                    val controller = remember { PlayerController() }
                    VideoPlayer(
                        request = req,
                        controller = controller,
                        options = PlayerOptions(live = true, buffer = state.settings.value.buffer),
                        modifier = Modifier.fillMaxSize(),
                        onStatus = { status = it },
                    )
                }
            }
            Text(
                "EN DIRECT",
                style = MonoLabel,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color(0xFFE53935), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${channel.number ?: (index + 1)}", style = MonoLabel, color = Cm.Cyan)
                Spacer(Modifier.width(10.dp))
                Text(channel.name, color = Cm.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (now != null) {
                Spacer(Modifier.height(10.dp))
                Text("MAINTENANT · ${formatClock(now.start)} – ${formatClock(now.stop)}", style = MonoLabel, color = Cm.Cyan)
                Text(now.title, color = Cm.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { now.progress(clock) },
                        modifier = Modifier.weight(1f).height(3.dp),
                        color = Cm.Cyan,
                        trackColor = Cm.SurfaceHigher,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(remaining(now, clock), style = MonoLabel, color = Cm.TextMuted)
                }
                now.description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Cm.TextMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Spacer(Modifier.height(10.dp))
                Text("Pas de guide TV pour cette chaîne", color = Cm.TextMuted, fontSize = 13.sp)
            }
            next?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "ENSUITE · ${formatClock(it.start)}  ${it.title}",
                    style = MonoLabel,
                    color = Cm.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            Text("OK : plein écran", style = MonoLabel, color = Cm.TextMuted)
        }
    }
}

private const val PREVIEW_DELAY_MS = 700L
