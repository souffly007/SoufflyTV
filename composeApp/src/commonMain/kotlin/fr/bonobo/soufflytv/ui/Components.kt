package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.MonoLabel

/**
 * Élément cliquable lisible à la souris, au doigt et à la télécommande :
 * le focus (D-pad / Tab) est souligné par une bordure cyan.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvClickable(
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(10.dp),
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        focused -> Cm.SurfaceHigher
        selected -> Cm.CyanGlow
        hovered -> Cm.SurfaceHigh
        else -> Color.Transparent
    }
    return this
        .clip(shape)
        .background(background)
        .then(if (focused) Modifier.border(2.dp, Cm.Cyan, shape) else Modifier)
        .hoverable(interaction)
        .combinedClickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Retour", tint = Cm.Text) }
            } else {
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    title,
                    color = Cm.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(subtitle, style = MonoLabel, color = Cm.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            actions()
        }
        CyanLine()
    }
}

/** Fin filet cyan qui s'estompe, signature visuelle de l'app. */
@Composable
fun CyanLine(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(1.dp).background(
            Brush.horizontalGradient(listOf(Cm.Cyan.copy(alpha = 0.7f), Cm.Outline, Color.Transparent))
        )
    )
}

@Composable
fun TypeBadge(label: String) {
    Box(
        Modifier
            .border(1.dp, Cm.Cyan.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .background(Cm.CyanGlow, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, style = MonoLabel, color = Cm.Cyan)
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .border(1.dp, if (selected) Cm.Cyan else Cm.Outline, shape)
            .tvClickable(selected = selected, shape = shape, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (selected) Cm.Cyan else Cm.TextMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ChannelLogo(url: String?, name: String, size: Dp = 40.dp) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Cm.SurfaceHigher),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) Text(initials(name), style = MonoLabel, color = Cm.Cyan)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
            )
        }
    }
}

private fun initials(name: String): String =
    name.split(' ', '-', '_', '|', ':', '.')
        .filter { it.isNotBlank() && it[0].isLetterOrDigit() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "TV" }

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        placeholder = { Text(placeholder, color = Cm.TextMuted) },
        leadingIcon = { Icon(AppIcons.Search, contentDescription = null, tint = Cm.TextMuted) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(AppIcons.Close, contentDescription = "Effacer", tint = Cm.TextMuted)
                }
            }
        } else null,
    )
}

@Composable
fun LoadingBox(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Cm.Cyan, strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(message, color = Cm.TextMuted, fontSize = 14.sp)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ERREUR", style = MonoLabel, color = Cm.Error)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Cm.Text, fontSize = 15.sp, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) {
                Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Réessayer")
            }
        }
    }
}

@Composable
fun EmptyHint(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Cm.TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

/** Ligne de réglage avec interrupteur, cliquable en entier (télécommande comprise). */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().tvClickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Cm.Text, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Cm.TextMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MonoLabel, color = Cm.TextMuted, modifier = modifier)
}

/** true quand on navigue à la télécommande ou au clavier (pas au doigt). */
@Composable
fun isKeyNavigation(): Boolean = LocalInputModeManager.current.inputMode == InputMode.Keyboard

/**
 * Télécommande : donne le focus à [requester] et insiste jusqu'à ce qu'il soit vraiment posé.
 * À l'arrivée sur un écran, la liste se compose en plusieurs passes : un seul essai ne suffit pas,
 * et sans focus le premier appui de touche l'envoie tout en haut à gauche (flèche Retour).
 *
 * [prepare] peut faire défiler la liste pour rendre l'élément visible avant de le cibler.
 */
@Composable
fun KeepFocus(
    requester: FocusRequester,
    vararg keys: Any?,
    isFocused: () -> Boolean,
    prepare: suspend () -> Unit = {},
) {
    val keyNavigation = isKeyNavigation()
    LaunchedEffect(keyNavigation, *keys) {
        if (!keyNavigation) return@LaunchedEffect
        withFrameNanos { } // attendre une première mise en page
        prepare()
        repeat(25) {
            if (isFocused()) return@LaunchedEffect
            runCatching { requester.requestFocus() }
            delay(60)
        }
    }
}
