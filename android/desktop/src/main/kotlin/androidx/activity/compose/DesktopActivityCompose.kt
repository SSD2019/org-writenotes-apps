@file:Suppress("ktlint")

package androidx.activity.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.util.concurrent.CopyOnWriteArrayList

object DesktopBackDispatcher {
    private val handlers = CopyOnWriteArrayList<() -> Unit>()

    fun register(handler: () -> Unit) {
        handlers.add(handler)
    }

    fun unregister(handler: () -> Unit) {
        handlers.remove(handler)
    }

    fun onBackPressed(): Boolean {
        val last = handlers.lastOrNull() ?: return false
        last.invoke()
        return true
    }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val currentOnBack = rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        val cb: () -> Unit = { currentOnBack.value.invoke() }
        if (enabled) {
            DesktopBackDispatcher.register(cb)
        }
        onDispose {
            DesktopBackDispatcher.unregister(cb)
        }
    }
}

class ManagedActivityResultLauncher<I, O>(
    private val onLaunch: (I) -> Unit
) {
    fun launch(input: I) {
        onLaunch(input)
    }
}

@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
    onResult: (O) -> Unit
): ManagedActivityResultLauncher<I, O> {
    val currentOnResult = rememberUpdatedState(onResult)
    return remember(contract) {
        ManagedActivityResultLauncher { input ->
            contract.executeDesktop(input) { out ->
                currentOnResult.value(out)
            }
        }
    }
}

