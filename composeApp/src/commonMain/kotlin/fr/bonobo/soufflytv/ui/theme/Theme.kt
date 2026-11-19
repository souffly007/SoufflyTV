package fr.bonobo.soufflytv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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

@Composable
fun SoufflyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CyanogenScheme, content = content)
}
