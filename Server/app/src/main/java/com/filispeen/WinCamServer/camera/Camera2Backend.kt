package com.filispeen.WinCamServer.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView

private const val DefaultShutterNs = 16_666_667L
private const val DefaultIso = 400

class Camera2Backend(private val context: Context) : CameraBackend {
    override val api = "Camera2"
    override var info: String = ""
        private set
    override var maxZoom: Float = 1f
        private set

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val lock = Any()

    @Volatile
    private var generation = 0
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var encoderSurface: Surface? = null
    private var view: TextureView? = null
    private var size: Size? = null
    private var sensorOrientation = 90
    private var contentRotated = false
    private var afMode: Int? = null
    private var zoomRatio = 1f
    private var useZoomRatio = false
    private var activeArray: Rect? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var fpsRange: android.util.Range<Int>? = null
    private var limits: ExposureLimits? = null
    private var frameCapNs = Long.MAX_VALUE
    private var manualShutterNs: Long? = null
    private var manualIso: Int? = null
    private var heldShutterNs = DefaultShutterNs
    private var heldIso = DefaultIso

    @Volatile
    private var lastShutterNs: Long? = null

    @Volatile
    private var lastIso: Int? = null

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastShutterNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
        }
    }

    private fun readLimits(chars: CameraCharacteristics): ExposureLimits? {
        val time = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return null
        val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
        return ExposureLimits(time.lower, time.upper, iso.lower, iso.upper)
    }

    override fun exposureLimits(cameraId: String?): ExposureLimits? = try {
        resolveId(cameraId)?.let { readLimits(manager.getCameraCharacteristics(it)) }
    } catch (e: Exception) {
        null
    }

    private fun applyExposure(builder: CaptureRequest.Builder) {
        val range = limits
        val shutter = manualShutterNs
        val iso = manualIso
        if (range == null || (shutter == null && iso == null)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            return
        }
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(
            CaptureRequest.SENSOR_EXPOSURE_TIME,
            (shutter ?: heldShutterNs).coerceIn(range.minNs, minOf(range.maxNs, frameCapNs).coerceAtLeast(range.minNs))
        )
        builder.set(
            CaptureRequest.SENSOR_SENSITIVITY,
            (iso ?: heldIso).coerceIn(range.minIso, range.maxIso)
        )
    }

    override fun setExposure(shutterNs: Long?, iso: Int?) {
        var request: CaptureRequest? = null
        var activeSession: CameraCaptureSession? = null
        var activeHandler: Handler? = null
        synchronized(lock) {
            if (manualShutterNs == null && manualIso == null && (shutterNs != null || iso != null)) {
                heldShutterNs = lastShutterNs ?: DefaultShutterNs
                heldIso = lastIso ?: DefaultIso
            }
            shutterNs?.let { heldShutterNs = it }
            iso?.let { heldIso = it }
            manualShutterNs = shutterNs
            manualIso = iso
            val builder = previewBuilder
            if (builder != null) {
                applyExposure(builder)
                request = builder.build()
                activeSession = session
                activeHandler = handler
            }
        }
        val r = request
        val s = activeSession
        if (r != null && s != null) {
            runCatching { s.setRepeatingRequest(r, captureCallback, activeHandler) }
        }
    }

    override fun currentExposure(): Pair<Long, Int>? = synchronized(lock) {
        if (manualShutterNs != null || manualIso != null) {
            heldShutterNs to heldIso
        } else {
            val ns = lastShutterNs
            val iso = lastIso
            if (ns != null && iso != null) ns to iso else null
        }
    }

    override fun cameras(): List<CameraOption> {
        val entries = try {
            manager.cameraIdList.mapNotNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.toList()
                    .orEmpty()
                if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) {
                    null
                } else {
                    val kind = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_BACK -> "Back"
                        CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                        else -> "Camera"
                    }
                    id to kind
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
        return buildCameraOptions(entries)
    }

    private fun resolveId(requested: String?): String? =
        if (requested != null && manager.cameraIdList.contains(requested)) {
            requested
        } else {
            CameraBackends.camera2Id(context)
        }

    override fun sizes(cameraId: String?): List<Size> = try {
        val id = resolveId(cameraId)
        if (id == null) {
            emptyList()
        } else {
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)
                ?.toList()
                .orEmpty()
        }
    } catch (e: Exception) {
        emptyList()
    }

    override fun fpsOptions(cameraId: String?, size: Size?): List<Int> = try {
        val id = resolveId(cameraId)
        if (id == null) {
            emptyList()
        } else {
            val chars = manager.getCameraCharacteristics(id)
            val uppers = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { it.upper }
                ?.distinct()
                ?.sorted()
                .orEmpty()
            var limit = Int.MAX_VALUE
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (size != null && map != null) {
                val duration = map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
                if (duration > 0) limit = (1_000_000_000.0 / duration + 0.5).toInt()
            }
            uppers.filter { it <= limit }.ifEmpty { listOfNotNull(uppers.firstOrNull()) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    @SuppressLint("MissingPermission")
    override fun start(
        view: TextureView?,
        texture: SurfaceTexture?,
        config: CameraConfig,
        report: (String) -> Unit
    ) {
        stop()
        if (texture == null && config.encoderSurface == null) return
        try {
            val requested = config.cameraId
            val id = if (requested != null && manager.cameraIdList.contains(requested)) {
                requested
            } else {
                CameraBackends.camera2Id(context)
            }
            if (id == null) {
                report("Camera2 camera not found")
                return
            }
            val chars = manager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            if (sizes.isEmpty()) {
                report("No preview sizes available")
                return
            }
            val chosen = chooseSize(sizes, config)
            val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
            var ratioMode = false
            var zoomLimit = 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (range != null) {
                    ratioMode = true
                    zoomLimit = range.upper
                }
            }
            if (!ratioMode) {
                zoomLimit = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            }
            val array = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val chosenRange = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.upper == config.fps }
                ?.maxByOrNull { it.lower }
            texture?.setDefaultBufferSize(chosen.width, chosen.height)
            val newThread = HandlerThread("WinCamCamera2").also { it.start() }
            val newHandler = Handler(newThread.looper)
            val gen = synchronized(lock) {
                generation += 1
                thread = newThread
                handler = newHandler
                surface = texture?.let { Surface(it) }
                encoderSurface = config.encoderSurface
                this.view = view
                size = chosen
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                contentRotated = false
                zoomRatio = 1f
                useZoomRatio = ratioMode
                activeArray = array
                fpsRange = chosenRange
                limits = readLimits(chars)
                frameCapNs = 1_000_000_000L / config.fps.coerceAtLeast(1)
                maxZoom = zoomLimit.coerceAtLeast(1f)
                afMode = when {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in modes ->
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes ->
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    else -> null
                }
                generation
            }
            refreshTransform()
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val stale = synchronized(lock) {
                        if (gen != generation) {
                            true
                        } else {
                            device = camera
                            false
                        }
                    }
                    if (stale) {
                        camera.close()
                        return
                    }
                    createSession(camera, gen, newHandler, report)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (gen == generation) report("Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (gen == generation) report("Camera2 error $error")
                }
            }, newHandler)
        } catch (e: Exception) {
            stop()
            report("Camera2 start failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(
        camera: CameraDevice,
        gen: Int,
        sessionHandler: Handler,
        report: (String) -> Unit
    ) {
        val target = synchronized(lock) { if (gen == generation) surface else null }
        val extra = synchronized(lock) { if (gen == generation) encoderSurface else null }
        val targets = listOfNotNull(target, extra)
        if (targets.isEmpty()) {
            camera.close()
            return
        }
        try {
            camera.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        val stale = synchronized(lock) {
                            if (gen != generation) {
                                true
                            } else {
                                session = configured
                                false
                            }
                        }
                        if (stale) {
                            configured.close()
                            return
                        }
                        try {
                            val request = synchronized(lock) {
                                val template = if (extra != null) {
                                    CameraDevice.TEMPLATE_RECORD
                                } else {
                                    CameraDevice.TEMPLATE_PREVIEW
                                }
                                val builder = camera.createCaptureRequest(template)
                                targets.forEach { builder.addTarget(it) }
                                afMode?.let { builder.set(CaptureRequest.CONTROL_AF_MODE, it) }
                                fpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                                applyZoom(builder)
                                applyExposure(builder)
                                previewBuilder = builder
                                builder.build()
                            }
                            configured.setRepeatingRequest(request, captureCallback, sessionHandler)
                        } catch (e: Exception) {
                            if (gen == generation) report("Camera2 preview failed: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        if (gen == generation) report("Camera2 session configuration failed")
                    }
                },
                sessionHandler
            )
        } catch (e: Exception) {
            if (gen == generation) report("Camera2 session failed: ${e.message}")
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        if (useZoomRatio) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            }
        } else {
            val array = activeArray ?: return
            val w = (array.width() / zoomRatio).toInt()
            val h = (array.height() / zoomRatio).toInt()
            val cx = array.centerX()
            val cy = array.centerY()
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            )
        }
    }

    override fun setZoom(ratio: Float) {
        var request: CaptureRequest? = null
        var activeSession: CameraCaptureSession? = null
        var activeHandler: Handler? = null
        synchronized(lock) {
            zoomRatio = ratio.coerceIn(1f, maxZoom)
            val builder = previewBuilder
            if (builder != null) {
                applyZoom(builder)
                request = builder.build()
                activeSession = session
                activeHandler = handler
            }
        }
        val r = request
        val s = activeSession
        if (r != null && s != null) {
            val h = activeHandler
            runCatching { s.setRepeatingRequest(r, captureCallback, h) }
        }
    }

    override fun refreshTransform() {
        val v = view ?: return
        val s = size ?: return
        val display = v.displayDegrees()
        val rotation = (360 - display) % 360
        val contentW = if (contentRotated) s.height else s.width
        val contentH = if (contentRotated) s.width else s.height
        info = "$api sensor=$sensorOrientation disp=$display rot=$rotation " +
            "content=${contentW}x${contentH} view=${v.width}x${v.height} emu=${isEmulator()}"
        v.applyPreviewTransform(contentW, contentH, rotation)
    }

    override fun onFrame(texture: SurfaceTexture) {
        val rotated = texture.isContentRotated()
        if (rotated != contentRotated) {
            contentRotated = rotated
            refreshTransform()
        }
    }

    override fun stop() {
        var oldSession: CameraCaptureSession? = null
        var oldDevice: CameraDevice? = null
        var oldThread: HandlerThread? = null
        var oldSurface: Surface? = null
        synchronized(lock) {
            generation += 1
            oldSession = session
            oldDevice = device
            oldThread = thread
            oldSurface = surface
            session = null
            device = null
            thread = null
            handler = null
            surface = null
            encoderSurface = null
            view = null
            size = null
            previewBuilder = null
        }
        runCatching { oldSession?.close() }
        runCatching { oldDevice?.close() }
        oldThread?.quitSafely()
        oldSurface?.release()
    }
}
