package fr.bonobo.soufflytv.player

import android.app.Activity
import android.os.Build
import android.view.Display
import fr.bonobo.soufflytv.data.DisplayModeInfo
import fr.bonobo.soufflytv.data.chooseDisplayMode
import java.lang.ref.WeakReference

/**
 * Fréquence d'image automatique (AFR) : demande à la TV le mode d'affichage (même définition)
 * dont la fréquence correspond à la vidéo, puis rend le mode d'origine en quittant le lecteur.
 * La TV peut afficher un écran noir une ou deux secondes pendant le changement (resynchronisation HDMI).
 */
internal object FrameRateSwitcher {
    private var activityRef: WeakReference<Activity>? = null
    /** Mode préféré avant le premier changement (0 = aucune préférence) ; null = rien changé. */
    private var originalModeId: Int? = null

    @Suppress("DEPRECATION")
    private fun displayOf(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay

    /** À appeler sur le fil principal. */
    fun apply(activity: Activity, fps: Float) {
        val display = displayOf(activity) ?: return
        val current = display.mode
        val modes = display.supportedModes.map { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val target = chooseDisplayMode(
            fps,
            DisplayModeInfo(current.modeId, current.physicalWidth, current.physicalHeight, current.refreshRate),
            modes,
        ) ?: return
        val window = activity.window ?: return
        if (originalModeId == null) originalModeId = window.attributes.preferredDisplayModeId
        activityRef = WeakReference(activity)
        window.attributes = window.attributes.also { it.preferredDisplayModeId = target.id }
    }

    /** Rend le mode d'affichage d'origine (sortie du lecteur). */
    fun restore() {
        val original = originalModeId ?: return
        originalModeId = null
        val activity = activityRef?.get() ?: return
        activityRef = null
        val window = activity.window ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = original }
    }
}
