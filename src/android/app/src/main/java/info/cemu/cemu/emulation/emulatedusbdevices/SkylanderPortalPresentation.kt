package info.cemu.cemu.emulation.emulatedusbdevices

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Shows Compose content on a secondary display (e.g. the bottom screen of a dual-screen handheld)
 * while the game keeps running on the activity's display.
 *
 * The window is not focusable, so touching it never takes key/controller focus away from
 * [info.cemu.cemu.emulation.EmulationActivity] and the activity is not paused. Because of that,
 * the content must not use text fields or dialogs, which would need focus of their own.
 *
 * The activity is used as the lifecycle, view model store and saved state owner, so view models
 * obtained with `viewModel()` inside [content] are the same instances the activity uses.
 */
class SkylanderPortalPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val content: @Composable () -> Unit,
) : Presentation(activity, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(activity)
            decorView.setViewTreeViewModelStoreOwner(activity)
            decorView.setViewTreeSavedStateRegistryOwner(activity)
        }

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(content)
        }

        setContentView(composeView)
    }
}
