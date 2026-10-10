package com.filispeen.WinCamServer.camera

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs
import kotlin.math.max

data class CameraConfig(
    val width: Int,
    val height: Int,
    val cameraId: String? = null,
    val fps: Int = 30,
    val encoderSurface: android.view.Surface? = null
)

data class CameraOption(val id: String, val label: String)

interface CameraBackend {
    val api: String
    val info: String
    val maxZoom: Float
    fun start(view: TextureView, texture: SurfaceTexture, config: CameraConfig, report: (String) -> Unit)
    fun refreshTransform()
    fun onFrame(texture: SurfaceTexture)
    fun setZoom(ratio: Float)
    fun cameras(): List<CameraOption>
    fun sizes(cameraId: String?): List<Size>
    fun fpsOptions(cameraId: String?, size: Size?): List<Int>
    fun stop()
}

object CameraBackends {
    fun create(context: Context): CameraBackend =
        if (camera2Id(context) != null) Camera2Backend(context) else Camera1Backend()

    fun sensorOrientation(context: Context, cameraId: String?): Int {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return 0
        return try {
            val id = if (cameraId != null && manager.cameraIdList.contains(cameraId)) {
                cameraId
            } else {
                camera2Id(context)
            }
            id?.let { manager.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_ORIENTATION) } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun camera2Id(context: Context): String? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        return try {
            val ids = manager.cameraIdList.toList()
            fun isLegacy(id: String): Boolean {
                val level = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                return level == null || level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
            }
            val backId = ids.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
            if (backId != null) {
                backId.takeIf { !isLegacy(it) }
            } else {
                ids.firstOrNull { !isLegacy(it) }
            }
        } catch (e: Exception) {
            null
        }
    }
}

internal fun buildCameraOptions(entries: List<Pair<String, String>>): List<CameraOption> {
    fun rank(kind: String): Int = when (kind) {
        "Back" -> 0
        "Front" -> 1
        else -> 2
    }
    return entries
        .sortedBy { rank(it.second) }
        .map { CameraOption(it.first, "${it.second} (ID ${it.first})") }
}

internal fun chooseSize(sizes: List<Size>, config: CameraConfig): Size {
    val targetAspect = config.width.toFloat() / config.height
    val targetArea = config.width * config.height
    val sameAspect = sizes.filter { abs(it.width.toFloat() / it.height - targetAspect) < 0.02f }
    val pool = sameAspect.ifEmpty { sizes }
    return pool.minByOrNull { abs(it.width * it.height - targetArea) } ?: Size(config.width, config.height)
}

internal fun isEmulator(): Boolean =
    Build.HARDWARE == "goldfish" ||
        Build.HARDWARE == "ranchu" ||
        Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.contains("sdk_gphone") ||
        Build.PRODUCT.contains("sdk_gphone") ||
        Build.MODEL.contains("Emulator")

internal fun SurfaceTexture.isContentRotated(): Boolean {
    val m = FloatArray(16)
    getTransformMatrix(m)
    return abs(m[0]) < 0.01f && abs(m[5]) < 0.01f
}

internal fun TextureView.displayDegrees(): Int = when (display?.rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

internal fun TextureView.applyPreviewTransform(bufferWidth: Int, bufferHeight: Int, rotation: Int) {
    val viewW = width.toFloat()
    val viewH = height.toFloat()
    if (viewW <= 0f || viewH <= 0f) return
    val bw = bufferWidth.toFloat()
    val bh = bufferHeight.toFloat()
    val swapped = rotation % 180 != 0
    val rotatedW = if (swapped) bh else bw
    val rotatedH = if (swapped) bw else bh
    val scale = max(viewW / rotatedW, viewH / rotatedH)
    val cx = viewW / 2f
    val cy = viewH / 2f
    val matrix = Matrix()
    matrix.postScale(bw / viewW, bh / viewH, cx, cy)
    matrix.postRotate(rotation.toFloat(), cx, cy)
    matrix.postScale(scale, scale, cx, cy)
    setTransform(matrix)
}
