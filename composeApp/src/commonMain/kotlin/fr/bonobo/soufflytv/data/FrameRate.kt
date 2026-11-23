package fr.bonobo.soufflytv.data

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Fréquence d'image automatique (AFR) : la TV passe à la fréquence qui correspond à la vidéo
 * (25 i/s → 50 Hz, 23,976 → 23,976 Hz, 29,97 → 59,94 Hz…), pour une image fluide, sans saccades.
 */

/** Un mode d'affichage de l'écran : définition et fréquence. */
data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * Mode à choisir pour une vidéo à [fps] images/s : même définition que le mode actuel, fréquence égale
 * à fps × 1, 2, 3 ou 4 (au plus près). null s'il n'y en a pas — ou si le mode actuel convient déjà.
 */
fun chooseDisplayMode(fps: Float, current: DisplayModeInfo, modes: List<DisplayModeInfo>): DisplayModeInfo? {
    if (fps < 10f || fps > 121f) return null
    fun fit(mode: DisplayModeInfo): Pair<Int, Float>? {
        val k = (mode.refreshRate / fps).roundToInt()
        if (k !in 1..4) return null
        val error = abs(mode.refreshRate - fps * k) / k
        // 0,03 : sépare 59,94 et 60 Hz (29,97 × 2 = 59,94 ; 30 × 2 = 60)
        return if (error < 0.03f) k to error else null
    }
    if (fit(current) != null) return null
    return modes
        .filter { it.width == current.width && it.height == current.height }
        .mapNotNull { mode -> fit(mode)?.let { (k, error) -> Triple(mode, k, error) } }
        // 25 / 30 i/s : 50 / 60 Hz plutôt que 25 / 30 Hz (scintillement) ; le cinéma (24 i/s) garde 24 Hz
        .minWithOrNull(compareBy({ if (it.first.refreshRate < 40f && fps !in 23f..24.5f) 1 else 0 }, { it.second }, { it.third }))
        ?.first
}

/**
 * Fréquence d'une vidéo d'après l'écart entre les images (μs) : médiane des écarts, puis arrondi
 * aux fréquences habituelles (23,976 / 24 / 25 / 29,97 / 30 / 50 / 59,94 / 60). null si incertain.
 */
fun estimateFrameRate(frameTimesUs: List<Long>): Float? {
    if (frameTimesUs.size < 12) return null
    val deltas = frameTimesUs.sorted().zipWithNext { a, b -> b - a }.filter { it in 5_000..100_000 }
    if (deltas.size < 10) return null
    val median = deltas.sorted()[deltas.size / 2]
    val fps = 1_000_000f / median
    val known = listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)
    val nearest = known.minByOrNull { abs(it - fps) } ?: return null
    return if (abs(nearest - fps) < 0.6f) nearest else null
}
