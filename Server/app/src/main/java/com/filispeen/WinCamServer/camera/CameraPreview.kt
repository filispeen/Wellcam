package com.filispeen.WinCamServer.camera

import android.content.Context
import android.content.ContextWrapper
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}

@Composable
fun CameraPreview(
    backend: CameraBackend,
    config: CameraConfig,
    zoom: Float,
    onMaxZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit
) {
    val keepAlive = config.encoderSurface != null
    val context = LocalContext.current
    val owner = remember(context) { context.findLifecycleOwner() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val latestOnError by rememberUpdatedState(onError)
    val latestOnMaxZoom by rememberUpdatedState(onMaxZoom)
    val latestZoom by rememberUpdatedState(zoom)
    val latestConfig by rememberUpdatedState(config)
    val latestKeepAlive by rememberUpdatedState(keepAlive)
    val view = remember { TextureView(context) }
    var surfaceReady by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(false) }

    DisposableEffect(owner) {
        val lifecycle = owner?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        if (lifecycle == null) {
            resumed = true
        } else {
            lifecycle.addObserver(observer)
        }
        onDispose { lifecycle?.removeObserver(observer) }
    }

    DisposableEffect(surfaceReady, resumed, config, keepAlive) {
        val texture = view.surfaceTexture
        val withPreview = surfaceReady && resumed && texture != null
        val headless = keepAlive && config.encoderSurface != null
        if (withPreview || headless) {
            backend.start(
                if (withPreview) view else null,
                if (withPreview) texture else null,
                config
            ) { message ->
                mainHandler.post { latestOnError(message) }
            }
            latestOnMaxZoom(backend.maxZoom)
            backend.setZoom(latestZoom)
        } else {
            backend.stop()
        }
        onDispose { if (!keepAlive) backend.stop() }
    }

    LaunchedEffect(zoom) {
        backend.setZoom(zoom)
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = {
            view.apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        surfaceReady = true
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        backend.refreshTransform()
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        backend.stop()
                        surfaceReady = false
                        val current = latestConfig
                        if (latestKeepAlive && current.encoderSurface != null) {
                            backend.start(null, null, current) { message ->
                                mainHandler.post { latestOnError(message) }
                            }
                        }
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        backend.onFrame(surface)
                    }
                }
            }
        }
    )
}
