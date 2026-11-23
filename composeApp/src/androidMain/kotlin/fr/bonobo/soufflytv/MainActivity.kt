package fr.bonobo.soufflytv

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fr.bonobo.soufflytv.platform.AndroidApp
import fr.bonobo.soufflytv.platform.ReminderPermission

class MainActivity : ComponentActivity() {

    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        AndroidApp.context = applicationContext
        ReminderPermission.activity = java.lang.ref.WeakReference(this)
        state = AppState()

        if (!BuildConfig.IS_TV && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            state.enterPictureInPicture = { enterPip() }
        }
        addOnPictureInPictureModeChangedListener { info ->
            state.inPictureInPicture = info.isInPictureInPictureMode
        }

        setContent {
            BackHandler(enabled = state.canGoBack) { state.back() }
            App(state)
        }
    }

    private fun enterPip() {
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }

    /** Touche Accueil pendant la lecture : on continue en petite fenêtre. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (state.screen is Screen.Player && state.settings.value.autoPip && state.enterPictureInPicture != null) {
            enterPip()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        state.close()
    }
}
