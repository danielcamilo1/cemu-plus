package info.cemu.cemu.common.android.display

import android.app.Presentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// shows content full screen on the second screen while this is in the composition, for previews of how the screen
// will look in game. The content recomposes with the screen that shows it, so it follows the settings live.
// A touchable one can be used with touch on the second screen, the controller stays with the main screen
@Composable
fun SecondScreenPresentation(
    enabled: Boolean = true,
    touchable: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val secondScreen = rememberSecondScreen()
    val activity = remember(context) { context.findActivity() }
    val compositionContext = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)

    DisposableEffect(activity, secondScreen, enabled, touchable) {
        val display = activity?.let { DisplayUtils.getExternalDisplay(it) }
        if (!enabled || activity == null || display == null) {
            return@DisposableEffect onDispose { }
        }
        val presentation = object : Presentation(activity, display) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                // focus stays with the main screen, and touches too unless it can be used
                window?.addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                )
                setContentView(ComposeView(this.context).apply {
                    setViewTreeLifecycleOwner(activity)
                    setViewTreeViewModelStoreOwner(activity)
                    setViewTreeSavedStateRegistryOwner(activity)
                    setParentCompositionContext(compositionContext)
                    setContent { currentContent() }
                })
            }
        }
        // the screen can go away between finding it and showing on it
        val isShown = runCatching { presentation.show() }.isSuccess
        onDispose {
            if (isShown) {
                presentation.dismiss()
            }
        }
    }
}
