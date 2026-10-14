package com.filispeen.Wellcam.camera

import android.graphics.SurfaceTexture
import android.util.Size
import android.view.TextureView

@Suppress("DEPRECATION")
private const val FACING_BACK = android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK

@Suppress("DEPRECATION")
private const val FACING_FRONT = android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT

@Suppress("DEPRECATION")
private const val FOCUS_VIDEO = android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO

@Suppress("DEPRECATION")
class Camera1Backend : CameraBackend {
    override val api = "Camera1"
    override var info: String = ""
        private set
    override var maxZoom: Float = 1f
        private set

    private var camera: android.hardware.Camera? = null
    private var view: TextureView? = null
    private var size: Size? = null
    private var orientation = 0
    private var zoomRatios: List<Int> = emptyList()
    private var zoomRatio = 1f

    private fun pickCameraId(): Int {
        val count = android.hardware.Camera.getNumberOfCameras()
        if (count <= 0) return -1
        val cameraInfo = android.hardware.Camera.CameraInfo()
        for (i in 0 until count) {
            android.hardware.Camera.getCameraInfo(i, cameraInfo)
            if (cameraInfo.facing == FACING_BACK) return i
        }
        return 0
    }

    private val capsCache = mutableMapOf<Int, Pair<List<Size>, List<Int>>>()

    private fun capsOf(params: android.hardware.Camera.Parameters): Pair<List<Size>, List<Int>> {
        val sizes = params.supportedPreviewSizes.orEmpty().map { Size(it.width, it.height) }
        val fps = params.supportedPreviewFpsRange.orEmpty().map { it[1] / 1000 }.distinct().sorted()
        return sizes to fps
    }

    private fun resolveId(requested: String?): Int {
        val count = android.hardware.Camera.getNumberOfCameras()
        val parsed = requested?.toIntOrNull()
        return if (parsed != null && parsed in 0 until count) parsed else pickCameraId()
    }

    private fun caps(id: Int): Pair<List<Size>, List<Int>> {
        capsCache[id]?.let { return it }
        if (id < 0) return emptyList<Size>() to emptyList<Int>()
        val temp = try {
            android.hardware.Camera.open(id)
        } catch (e: Exception) {
            return emptyList<Size>() to emptyList<Int>()
        }
        return try {
            capsOf(temp.parameters).also { capsCache[id] = it }
        } catch (e: Exception) {
            emptyList<Size>() to emptyList<Int>()
        } finally {
            runCatching { temp.release() }
        }
    }

    override fun sizes(cameraId: String?): List<Size> = caps(resolveId(cameraId)).first

    override fun fpsOptions(cameraId: String?, size: Size?): List<Int> = caps(resolveId(cameraId)).second

    override fun cameras(): List<CameraOption> {
        val count = android.hardware.Camera.getNumberOfCameras()
        val cameraInfo = android.hardware.Camera.CameraInfo()
        val entries = (0 until count).map { i ->
            android.hardware.Camera.getCameraInfo(i, cameraInfo)
            val kind = when (cameraInfo.facing) {
                FACING_BACK -> "Back"
                FACING_FRONT -> "Front"
                else -> "Camera"
            }
            i.toString() to kind
        }
        return buildCameraOptions(entries)
    }

    override fun start(
        view: TextureView?,
        texture: SurfaceTexture?,
        config: CameraConfig,
        report: (String) -> Unit
    ) {
        stop()
        if (view == null || texture == null) return
        val count = android.hardware.Camera.getNumberOfCameras()
        val requested = config.cameraId?.toIntOrNull()
        val id = if (requested != null && requested in 0 until count) requested else pickCameraId()
        if (id < 0) {
            report("Camera not found")
            return
        }
        val opened = try {
            android.hardware.Camera.open(id)
        } catch (e: Exception) {
            report("Camera1 open failed: ${e.message}")
            return
        }
        try {
            val cameraInfo = android.hardware.Camera.CameraInfo()
            android.hardware.Camera.getCameraInfo(id, cameraInfo)
            val params = opened.parameters
            capsCache[id] = capsOf(params)
            val chosen = chooseSize(
                params.supportedPreviewSizes.map { Size(it.width, it.height) },
                config
            )
            params.setPreviewSize(chosen.width, chosen.height)
            params.supportedPreviewFpsRange.orEmpty()
                .filter { it[1] == config.fps * 1000 }
                .maxByOrNull { it[0] }
                ?.let { params.setPreviewFpsRange(it[0], it[1]) }
            if (params.supportedFocusModes?.contains(FOCUS_VIDEO) == true) {
                params.focusMode = FOCUS_VIDEO
            }
            val ratios = if (params.isZoomSupported) params.zoomRatios.orEmpty() else emptyList()
            params.zoom = 0
            opened.parameters = params
            val degrees = view.displayDegrees()
            val result = if (cameraInfo.facing == FACING_FRONT) {
                (360 - (cameraInfo.orientation + degrees) % 360) % 360
            } else {
                (cameraInfo.orientation - degrees + 360) % 360
            }
            opened.setDisplayOrientation(result)
            opened.setPreviewTexture(texture)
            opened.startPreview()
            camera = opened
            this.view = view
            size = chosen
            orientation = result
            zoomRatios = ratios
            zoomRatio = 1f
            maxZoom = if (ratios.isEmpty()) 1f else (ratios.last() / 100f).coerceAtLeast(1f)
        } catch (e: Exception) {
            runCatching { opened.release() }
            report("Camera1 preview failed: ${e.message}")
            return
        }
        refreshTransform()
    }

    override fun setZoom(ratio: Float) {
        zoomRatio = ratio.coerceIn(1f, maxZoom)
        val cam = camera ?: return
        if (zoomRatios.isEmpty()) return
        val target = (zoomRatio * 100).toInt()
        var index = zoomRatios.indexOfFirst { it >= target }
        if (index < 0) index = zoomRatios.lastIndex
        runCatching {
            val params = cam.parameters
            params.zoom = index
            cam.parameters = params
        }
    }

    override fun onFrame(texture: SurfaceTexture) {
    }

    override fun refreshTransform() {
        val v = view ?: return
        val s = size ?: return
        val rotated = orientation % 180 != 0
        val w = if (rotated) s.height else s.width
        val h = if (rotated) s.width else s.height
        v.applyPreviewTransform(w, h, 0)
        info = "$api disp=$orientation buf=${s.width}x${s.height} " +
            "view=${v.width}x${v.height} emu=${isEmulator()}"
    }

    override fun stop() {
        val old = camera
        camera = null
        view = null
        size = null
        if (old != null) {
            runCatching { old.stopPreview() }
            runCatching { old.release() }
        }
    }
}
