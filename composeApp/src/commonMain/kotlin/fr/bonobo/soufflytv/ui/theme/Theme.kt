package fr.bonobo.soufflytv.ui.theme

import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Palette façon CyanogenMod : noir profond, cyan électrique, texte clair. */
object Cm {
    val Background = Color(0xFF05080A)
    val Surface = Color(0xFF0B1115)
    val SurfaceHigh = Color(0xFF111A1F)
    val SurfaceHigher = Color(0xFF18242B)
    val Outline = Color(0xFF1E323A)

    val Cyan = Color(0xFF00D4EC)
    val CyanDeep = Color(0xFF00838F)
    val CyanGlow = Color(0x2600D4EC)
    val OnCyan = Color(0xFF00252A)

    val Text = Color(0xFFE2F3F6)
    val TextMuted = Color(0xFF7D959D)
    val Error = Color(0xFFFF5C7A)
    val Success = Color(0xFF3DDC97)
}

/** Style « terminal » pour les petites étiquettes techniques. */
val MonoLabel = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    letterSpacing = 1.sp,
)

private val CyanogenScheme = darkColorScheme(
    primary = Cm.Cyan,
    onPrimary = Cm.OnCyan,
    primaryContainer = Cm.CyanDeep,
    onPrimaryContainer = Cm.Text,
    secondary = Cm.CyanDeep,
    onSecondary = Cm.Text,
    tertiary = Cm.Cyan,
    background = Cm.Background,
    onBackground = Cm.Text,
    surface = Cm.Surface,
    onSurface = Cm.Text,
    surfaceVariant = Cm.SurfaceHigh,
    onSurfaceVariant = Cm.TextMuted,
    surfaceContainerLowest = Cm.Background,
    surfaceContainerLow = Cm.Surface,
    surfaceContainer = Cm.Surface,
    surfaceContainerHigh = Cm.SurfaceHigh,
    surfaceContainerHighest = Cm.SurfaceHigher,
    outline = Cm.Outline,
    outlineVariant = Cm.Outline,
    error = Cm.Error,
    onError = Color.Black,
)

/**
 * Défilement minimal quand un élément prend le focus : on ne bouge que s'il sort de l'écran.
 * (Sur Android TV, Compose place par défaut l'élément au tiers de la hauteur : l'en-tête des séries
 * et le bandeau « À la une » se retrouvaient coupés.)
 */
@OptIn(ExperimentalFoundationApi::class)
private val MinimalBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailingEdge = offset + size
        return when {
            offset >= 0f && trailingEdge <= containerSize -> 0f // déjà visible
            offset < 0f && trailingEdge > containerSize -> 0f // plus grand que la zone : on ne bouge pas
            abs(offset) < abs(trailingEdge - containerSize) -> offset
            else -> trailingEdge - containerSize
        }
    }
}

/** Couleur du « curseur » de la télécommande (réglable). */
val LocalFocusColor = staticCompositionLocalOf { Color(0xFFFFD600) }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SoufflyTheme(focusColor: Color = Color(0xFFFFD600), textScale: Float = 1f, content: @Composable () -> Unit) {
    // Taille du texte (réglage) : tous les textes en sp suivent, les marges et icônes ne bougent pas
    val density = LocalDensity.current
    MaterialTheme(colorScheme = CyanogenScheme) {
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, density.fontScale * textScale),
            LocalFocusColor provides focusColor,
            LocalBringIntoViewSpec provides MinimalBringIntoView,
            // Boutons Material (Lecture, icônes du lecteur…) : fond bien visible quand ils ont le focus
            LocalRippleConfiguration provides RippleConfiguration(
                color = focusColor,
                rippleAlpha = RippleAlpha(
                    draggedAlpha = 0.16f,
                    focusedAlpha = 0.42f,
                    hoveredAlpha = 0.10f,
                    pressedAlpha = 0.30f,
                ),
            ),
            content = content,
        )
    }
}
