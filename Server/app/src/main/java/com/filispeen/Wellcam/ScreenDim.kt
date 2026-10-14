package com.filispeen.Wellcam

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val IdleDimMs = 15_000L
private const val DimBrightness = 0.02f

class DimController {
    var tick by mutableIntStateOf(0)
        private set

    @Volatile
    var dimmed = false

    fun touch() {
        tick++
    }
}

fun Modifier.trackActivity(controller: DimController): Modifier = pointerInput(controller) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (controller.dimmed) event.changes.forEach { it.consume() }
            controller.touch()
        }
    }
}

private fun Context.findWindow(): Window? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        current = current.baseContext
    }
    return null
}

private fun Window.applyBrightness(value: Float) {
    val params = attributes
    if (params.screenBrightness == value) return
    params.screenBrightness = value
    attributes = params
}

@Composable
fun ScreenDimEffect(active: Boolean, controller: DimController) {
    val context = LocalContext.current
    val window = remember(context) { context.findWindow() }
    DisposableEffect(window, active) {
        if (window != null && active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller.dimmed = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.applyBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
    }
    LaunchedEffect(window, active) {
        if (window == null || !active) return@LaunchedEffect
        snapshotFlow { controller.tick }.collectLatest {
            controller.dimmed = false
            window.applyBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            delay(IdleDimMs)
            window.applyBrightness(DimBrightness)
            controller.dimmed = true
        }
    }
}
