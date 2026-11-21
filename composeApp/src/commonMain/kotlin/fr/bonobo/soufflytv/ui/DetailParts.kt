package fr.bonobo.soufflytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fr.bonobo.soufflytv.ui.theme.Cm
import fr.bonobo.soufflytv.ui.theme.MonoLabel

/*
 * Éléments communs des fiches film et série (style des maquettes, couleurs Souffly) :
 * grande image de fond fondue dans le noir, affiche, pastilles d'infos, boutons empilés,
 * colonne « Informations ».
 */

/** Grande image du film / de la série en fond, fondue vers la gauche et vers le bas. */
@Composable
internal fun BoxScope.DetailBackdrop(image: String?) {
    if (image == null) return
    AsyncImage(
        model = image,
        contentDescription = null,
        modifier = Modifier.fillMaxHeight().fillMaxWidth(0.75f).align(Alignment.TopEnd),
        contentScale = ContentScale.Crop,
    )
    Box(
        Modifier.matchParentSize().background(
            Brush.horizontalGradient(
                0f to Cm.Background,
                0.3f to Cm.Background.copy(alpha = 0.92f),
                0.65f to Cm.Background.copy(alpha = 0.6f),
                1f to Cm.Background.copy(alpha = 0.35f),
            )
        )
    )
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Cm.Background)))
}

/** Affiche avec coins arrondis et liseré ; icône de remplacement tant que l'image n'est pas là. */
@Composable
internal fun DetailPoster(url: String?, width: Dp, placeholder: ImageVector) {
    Box(
        Modifier
            .width(width)
            .height(width * 1.5f)
            .clip(RoundedCornerShape(12.dp))
            .background(Cm.SurfaceHigher)
            .border(1.dp, Cm.Outline, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, contentDescription = null, tint = Cm.TextMuted, modifier = Modifier.size(40.dp))
        if (url != null) {
            AsyncImage(url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** « 2025 · Drame · 1 h 23 · ★ 6,9 » en pastilles ; la note en cyan plein. */
@Composable
internal fun MetaChips(meta: String?) {
    val parts = meta?.split(" · ")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.forEach { part ->
            val rating = part.startsWith("★")
            Text(
                part,
                color = if (rating) Cm.OnCyan else Cm.Text,
                fontSize = 13.sp,
                fontWeight = if (rating) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(if (rating) Cm.Cyan else Cm.SurfaceHigher.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** Bouton principal (cyan) d'une colonne de boutons empilés. */
@Composable
internal fun DetailPrimaryButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Cm.Cyan, contentColor = Cm.OnCyan),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.height(46.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Bouton secondaire (contour) d'une colonne de boutons empilés. */
@Composable
internal fun DetailButton(
    text: String,
    modifier: Modifier,
    icon: ImageVector? = null,
    tint: Color = Cm.Text,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(10.dp), modifier = modifier.height(42.dp)) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Cm.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Colonne « Informations » : réalisation, distribution… (rien si la source ne donne rien). */
@Composable
internal fun InfoColumn(director: String?, cast: String?, extra: List<Pair<String, String>> = emptyList(), modifier: Modifier) {
    val entries = listOfNotNull(
        director?.let { "Réalisation" to it },
        cast?.let { "Avec" to it },
    ) + extra
    if (entries.isEmpty()) return
    Column(
        modifier
            .background(Cm.Surface.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
            .border(1.dp, Cm.Outline, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("INFORMATIONS", style = MonoLabel, color = Cm.Cyan)
        entries.forEach { (label, value) ->
            Column {
                Text(label, color = Cm.TextMuted, fontSize = 12.sp)
                Text(value, color = Cm.Text, fontSize = 14.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
