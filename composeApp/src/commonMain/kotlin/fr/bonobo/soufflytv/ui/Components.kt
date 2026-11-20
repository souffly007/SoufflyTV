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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import fr.bonobo.soufflytv.platform.isAndroid
import fr.bonobo.soufflytv.platform.isTvBuild
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.widthIn
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

/** Champ de recherche : loupe à gauche, croix pour effacer (appui long sur OK à la télécommande). */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    RemoteTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        leadingIcon = AppIcons.Search,
        clearable = true,
    )
}

/** Bandeau en haut de l'écran, au-dessus de tout, qui montre le texte en cours de saisie. */
@Composable
private fun TypingPreview(label: String, text: String) {
    val offsetY = with(LocalDensity.current) { 28.dp.roundToPx() }
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, offsetY),
        properties = PopupProperties(focusable = false),
    ) {
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier
                .widthIn(min = 420.dp, max = 760.dp)
                .background(Cm.SurfaceHigh, shape)
                .border(2.dp, Cm.Cyan, shape)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(label, color = Cm.TextMuted, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    color = Cm.Text,
                    fontSize = 24.sp,
                    maxLines = 1,
                    // texte long : on montre la fin, là où l'on tape
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // curseur
                Box(Modifier.padding(start = 2.dp).width(2.dp).height(26.dp).background(Cm.Cyan))
            }
        }
    }
}

/** Télécommande (Android TV, box, clavier sur Android) : les champs ne s'ouvrent que sur OK. */
@Composable
fun remoteTextInput(): Boolean = remoteNavigation()

/** Navigation à la télécommande sur Android (TV, box, ou téléphone piloté au clavier). */
@Composable
fun remoteNavigation(): Boolean = isAndroid && (isTvBuild || isKeyNavigation())

/**
 * Champ de saisie utilisable au doigt, à la souris et à la télécommande.
 *
 * Au doigt et sur PC : un champ classique.
 * À la télécommande : une case qui se traverse avec ▲ ▼ comme un bouton ;
 * OK ouvre la saisie et le clavier, « OK / Terminé » du clavier ou Retour la referment
 * et rendent le focus à la case. Plus besoin de faire Retour pour continuer à descendre.
 */
@Composable
fun RemoteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    hidden: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isError: Boolean = false,
    supporting: String? = null,
    clearable: Boolean = false,
) {
    val remote = remoteTextInput()
    var editing by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val boxFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val shape = RoundedCornerShape(12.dp)

    fun stopEditing(keepFocus: Boolean) {
        returnFocus = keepFocus
        editing = false
    }

    if (remote && !editing) {
        LaunchedEffect(Unit) {
            if (returnFocus) {
                returnFocus = false
                withFrameNanos { }
                runCatching { boxFocus.requestFocus() }
            }
        }
        Column(modifier) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .focusRequester(boxFocus)
                    .border(1.dp, if (isError) Cm.Error else Cm.Outline, shape)
                    .tvClickable(
                        shape = shape,
                        onLongClick = if (clearable && value.isNotEmpty()) ({ onValueChange("") }) else null,
                    ) { editing = true }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, contentDescription = null, tint = Cm.TextMuted)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    if (label != null) Text(label, color = if (isError) Cm.Error else Cm.TextMuted, fontSize = 12.sp)
                    Text(
                        when {
                            value.isEmpty() -> placeholder
                            hidden -> "•".repeat(value.length)
                            else -> value
                        },
                        color = if (value.isEmpty()) Cm.TextMuted else Cm.Text,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(AppIcons.Edit, contentDescription = "Saisir", tint = Cm.TextMuted, modifier = Modifier.size(18.dp))
            }
            if (supporting != null) {
                Text(
                    supporting,
                    color = if (isError) Cm.Error else Cm.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
                )
            }
        }
        return
    }

    if (remote) {
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { fieldFocus.requestFocus() }
            delay(100)
            keyboard?.show()
        }
        // Le clavier de la TV recouvre souvent le champ : ce qu'on tape s'affiche en haut de l'écran
        TypingPreview(
            label = label ?: placeholder.ifEmpty { "Saisie" },
            text = if (hidden) "•".repeat(value.length) else value,
        )
        // Clavier refermé (Retour) : la saisie se termine, le focus reste sur la case
        val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
        var imeShown by remember { mutableStateOf(false) }
        LaunchedEffect(imeBottom) {
            if (imeBottom > 0) {
                imeShown = true
            } else if (imeShown) {
                imeShown = false
                stopEditing(keepFocus = true)
            }
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .focusRequester(fieldFocus)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    hadFocus = true
                } else if (hadFocus) {
                    hadFocus = false
                    // Focus parti ailleurs (▲ ▼) : la case reprend sa place, sans y ramener le focus
                    if (remote && editing) stopEditing(keepFocus = false)
                }
            }
            .onPreviewKeyEvent { event ->
                // Retour pendant la saisie (clavier déjà fermé) : on referme le champ, sans quitter l'écran
                if (remote && event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyUp) stopEditing(keepFocus = true)
                    true
                } else {
                    false
                }
            },
        singleLine = true,
        shape = shape,
        label = if (label != null) {
            { Text(label) }
        } else null,
        placeholder = if (placeholder.isNotEmpty()) {
            { Text(placeholder, color = Cm.TextMuted.copy(alpha = 0.7f)) }
        } else null,
        leadingIcon = if (leadingIcon != null) {
            { Icon(leadingIcon, contentDescription = null, tint = Cm.TextMuted) }
        } else null,
        trailingIcon = if (clearable && value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(AppIcons.Close, contentDescription = "Effacer", tint = Cm.TextMuted)
                }
            }
        } else null,
        isError = isError,
        supportingText = if (supporting != null) {
            { Text(supporting) }
        } else null,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboardOptions.copy(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            if (remote) stopEditing(keepFocus = true) else defaultKeyboardAction(ImeAction.Done)
        }),
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
