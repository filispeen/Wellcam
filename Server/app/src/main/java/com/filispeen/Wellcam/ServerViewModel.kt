package com.filispeen.Wellcam

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.filispeen.Wellcam.camera.CameraBackend
import com.filispeen.Wellcam.camera.CameraBackends
import com.filispeen.Wellcam.camera.CameraConfig
import com.filispeen.Wellcam.camera.CameraOption
import com.filispeen.Wellcam.camera.ExposureLimits
import com.filispeen.Wellcam.camera.defaultFps
import com.filispeen.Wellcam.camera.defaultSize
import com.filispeen.Wellcam.camera.groupByAspect
import com.filispeen.Wellcam.encoder.BitrateSettings
import com.filispeen.Wellcam.encoder.EncoderSupport
import com.filispeen.Wellcam.encoder.VideoCodec
import com.filispeen.Wellcam.transport.AoaTransport
import com.filispeen.Wellcam.transport.CommandSet
import com.filispeen.Wellcam.transport.CommandStateRequest
import com.filispeen.Wellcam.transport.ServerState
import com.filispeen.Wellcam.transport.TcpServer
import com.filispeen.Wellcam.transport.VideoStreamer
import com.filispeen.Wellcam.transport.VideoTransport
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

const val ServerPort = 11203

private val FallbackSizes = listOf(Size(1280, 720), Size(1920, 1080), Size(2560, 1440))
private val FallbackFps = listOf(24, 30, 60)

data class EncoderSpec(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val fps: Int,
    val rotation: Int,
    val bitrate: BitrateSettings,
    val constantBitrate: Boolean
)

class ServerViewModel(application: Application) : AndroidViewModel(application) {
    var serverState by mutableStateOf(ServerState.Stopped)
        private set
    var serverInfo by mutableStateOf<String?>(null)
        private set
    var clientDropped by mutableStateOf(false)
        private set
    var encoderSurface by mutableStateOf<Surface?>(null)
        private set
    var streamError by mutableStateOf<String?>(null)
    var zoom by mutableFloatStateOf(1f)
    var maxZoom by mutableFloatStateOf(1f)
    var shutterManual by mutableStateOf(false)
        private set
    var isoManual by mutableStateOf(false)
        private set
    var shutterNs by mutableLongStateOf(16_666_667L)
        private set
    var iso by mutableIntStateOf(400)
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var spec: EncoderSpec? = null

    val cameraBackend: CameraBackend by lazy { CameraBackends.create(application) }

    internal var inspection = false

    val backend: CameraBackend?
        get() = if (inspection) null else cameraBackend

    val cameraOptions: List<CameraOption> by lazy { backend?.cameras().orEmpty() }
    val cameraLabels: List<String> by lazy { cameraOptions.map { it.label } }
    val codecs: List<VideoCodec> by lazy { EncoderSupport.supportedCodecs() }

    var savedCameraId by mutableStateOf<String?>(null)
        private set
    var savedResolution by mutableStateOf<String?>(null)
        private set
    var savedFps by mutableStateOf<String?>(null)
        private set
    var savedCodec by mutableStateOf<String?>(null)
        private set
    var autoBitrate by mutableStateOf(true)
        private set
    var manualBitrate by mutableStateOf("")
        private set
    var manualMax by mutableStateOf("")
        private set
    var manualMin by mutableStateOf("")
        private set
    var constantBitrate by mutableStateOf(true)
        private set

    val activeCamera: CameraOption?
        get() = cameraOptions.firstOrNull { it.id == savedCameraId } ?: cameraOptions.firstOrNull()

    val cameraLabel: String
        get() = activeCamera?.label ?: "No camera"

    val codec: VideoCodec?
        get() = codecs.firstOrNull { it.label == savedCodec } ?: codecs.firstOrNull()

    val exposureLimits: ExposureLimits? by derivedStateOf { backend?.exposureLimits(activeCamera?.id) }

    private val allSizes: List<Size> by derivedStateOf {
        val all = backend?.sizes(activeCamera?.id).orEmpty().ifEmpty { FallbackSizes }
        codec
            ?.let { c -> all.filter { EncoderSupport.supportsSize(c, it.width, it.height) }.ifEmpty { all } }
            ?: all
    }

    val resolutionGroups: List<Pair<String, List<String>>> by derivedStateOf {
        groupByAspect(allSizes).map { group ->
            group.ratio to group.sizes.map { "${it.width}x${it.height}" }
        }
    }

    val resolution: String by derivedStateOf {
        savedResolution?.takeIf { saved -> resolutionGroups.any { saved in it.second } }
            ?: defaultSize(allSizes)?.let { "${it.width}x${it.height}" }
            ?: "1280x720"
    }

    private val selectedSize: Size? by derivedStateOf {
        allSizes.firstOrNull { "${it.width}x${it.height}" == resolution }
    }

    private val fpsValues: List<Int> by derivedStateOf {
        backend?.fpsOptions(activeCamera?.id, selectedSize).orEmpty().ifEmpty { FallbackFps }
    }

    val fpsLabels: List<String> by derivedStateOf { fpsValues.map { "$it FPS" } }

    val fps: String by derivedStateOf {
        savedFps?.takeIf { it in fpsLabels }
            ?: defaultFps(fpsValues)?.let { "$it FPS" }
            ?: "30 FPS"
    }

    val previewConfig: CameraConfig by derivedStateOf {
        CameraConfig(
            selectedSize?.width ?: 1280,
            selectedSize?.height ?: 720,
            activeCamera?.id,
            fps.removeSuffix(" FPS").toIntOrNull() ?: 30
        )
    }

    val autoSettings: BitrateSettings? by derivedStateOf {
        codec?.let {
            EncoderSupport.autoBitrate(it, previewConfig.width, previewConfig.height, previewConfig.fps)
        }
    }

    private val manualSettings: BitrateSettings? by derivedStateOf {
        EncoderSupport.manualSettings(manualBitrate, manualMin, manualMax)
    }

    val settings: BitrateSettings?
        get() = if (autoBitrate) autoSettings else manualSettings

    val bitrateError: String?
        get() {
            val c = codec
            val manual = manualSettings
            return when {
                autoBitrate || c == null -> null
                manual == null -> "Enter valid numbers in Mbps"
                else -> EncoderSupport.validate(c, manual)
            }
        }

    val sensorRotation: Int by derivedStateOf {
        if (backend == null) 0 else CameraBackends.sensorOrientation(getApplication(), activeCamera?.id)
    }

    private var stateBatch = 0

    private fun pushState() {
        if (stateBatch > 0 || !streamer.stateRequested) return
        streamer.publishState(stateJson())
    }

    private fun stateJson(): String {
        val root = JSONObject()
        root.put("v", 1)
        root.put("cameras", JSONArray(cameraLabels))
        root.put("camera", cameraLabel)
        root.put(
            "resolutions",
            JSONArray(
                resolutionGroups.map { (ratio, sizes) ->
                    JSONObject().put("ratio", ratio).put("sizes", JSONArray(sizes))
                }
            )
        )
        root.put("resolution", resolution)
        root.put("fpsList", JSONArray(fpsLabels.mapNotNull { it.removeSuffix(" FPS").toIntOrNull() }))
        root.put("fps", previewConfig.fps)
        root.put("codecs", JSONArray(codecs.map { it.label }))
        root.put("codec", codec?.label ?: JSONObject.NULL)
        root.put("autoBitrate", autoBitrate)
        root.put("constantBitrate", constantBitrate)
        val auto = autoSettings
        if (autoBitrate && auto != null) {
            root.put("bitrate", EncoderSupport.formatMbps(auto.bitrate))
            root.put("max", EncoderSupport.formatMbps(auto.max))
            root.put("min", EncoderSupport.formatMbps(auto.min))
        } else {
            root.put("bitrate", manualBitrate)
            root.put("max", manualMax)
            root.put("min", manualMin)
        }
        root.put("bitrateError", bitrateError ?: JSONObject.NULL)
        val limits = exposureLimits
        val shutter = JSONObject().put("manual", shutterManual).put("ns", shutterNs)
        val isoState = JSONObject().put("manual", isoManual).put("value", iso)
        if (limits != null) {
            shutter.put("minNs", limits.minNs).put("maxNs", limits.maxNs)
            isoState.put("min", limits.minIso).put("max", limits.maxIso)
        }
        root.put("shutter", shutter)
        root.put("iso", isoState)
        return root.toString()
    }

    private fun handleCommand(type: Int, payload: String) {
        if (type == CommandStateRequest) {
            pushState()
            return
        }
        if (type != CommandSet) return
        val json = try {
            JSONObject(payload)
        } catch (e: JSONException) {
            return
        }
        stateBatch++
        try {
            applySet(json)
        } finally {
            stateBatch--
        }
        pushState()
    }

    private fun applySet(json: JSONObject) {
        json.optString("camera").takeIf { it.isNotEmpty() }?.let { selectCamera(it) }
        json.optString("codec").takeIf { label -> label.isNotEmpty() && codecs.any { it.label == label } }
            ?.let { selectCodec(it) }
        json.optString("resolution").takeIf { value -> resolutionGroups.any { value in it.second } }
            ?.let { selectResolution(it) }
        if (json.has("fps")) {
            val label = "${json.optInt("fps")} FPS"
            if (label in fpsLabels) selectFps(label)
        }
        if (json.has("constantBitrate")) changeConstantBitrate(json.optBoolean("constantBitrate"))
        if (json.has("autoBitrate")) changeAutoBitrate(json.optBoolean("autoBitrate"))
        if (!autoBitrate && json.has("bitrate") && json.has("max") && json.has("min")) {
            val bitrate = json.optString("bitrate")
            val max = json.optString("max")
            val min = json.optString("min")
            val candidate = EncoderSupport.manualSettings(bitrate, min, max)
            val c = codec
            if (candidate != null && c != null && EncoderSupport.validate(c, candidate) == null) {
                changeManualBitrate(bitrate)
                changeManualMax(max)
                changeManualMin(min)
            }
        }
        if (json.has("shutterManual")) enableShutterManual(json.optBoolean("shutterManual"))
        if (json.has("isoManual")) enableIsoManual(json.optBoolean("isoManual"))
        val limits = exposureLimits
        if (json.has("shutterNs")) {
            val ns = json.optLong("shutterNs")
            changeShutter(if (limits != null) ns.coerceIn(limits.minNs, limits.maxNs) else ns)
        }
        if (json.has("iso")) {
            val value = json.optInt("iso")
            changeIso(if (limits != null) value.coerceIn(limits.minIso, limits.maxIso) else value)
        }
    }

    fun selectCamera(label: String) {
        cameraOptions.firstOrNull { it.label == label }?.let {
            savedCameraId = it.id
            zoom = 1f
            pushState()
        }
    }

    fun selectResolution(value: String) {
        savedResolution = value
        pushState()
    }

    fun selectFps(value: String) {
        savedFps = value
        pushState()
    }

    fun selectCodec(label: String) {
        savedCodec = label
        pushState()
    }

    fun changeConstantBitrate(on: Boolean) {
        constantBitrate = on
        pushState()
    }

    fun changeAutoBitrate(on: Boolean) {
        val auto = autoSettings
        if (!on && auto != null) {
            manualBitrate = EncoderSupport.formatMbps(auto.bitrate)
            manualMax = EncoderSupport.formatMbps(auto.max)
            manualMin = EncoderSupport.formatMbps(auto.min)
        }
        autoBitrate = on
        pushState()
    }

    fun changeManualBitrate(value: String) {
        manualBitrate = value
        pushState()
    }

    fun changeManualMax(value: String) {
        manualMax = value
        pushState()
    }

    fun changeManualMin(value: String) {
        manualMin = value
        pushState()
    }

    private fun pushExposure() {
        cameraBackend.setExposure(
            if (shutterManual) shutterNs else null,
            if (isoManual) iso else null
        )
    }

    fun enableShutterManual(on: Boolean) {
        if (on && !shutterManual) cameraBackend.currentExposure()?.let { shutterNs = it.first }
        shutterManual = on
        pushExposure()
        pushState()
    }

    fun enableIsoManual(on: Boolean) {
        if (on && !isoManual) cameraBackend.currentExposure()?.let { iso = it.second }
        isoManual = on
        pushExposure()
        pushState()
    }

    fun changeShutter(ns: Long) {
        shutterNs = ns
        if (shutterManual) pushExposure()
        pushState()
    }

    fun changeIso(value: Int) {
        iso = value
        if (isoManual) pushExposure()
        pushState()
    }

    private val onState: (ServerState, String?) -> Unit = { state, info ->
        if (state == ServerState.Listening && serverState == ServerState.Connected) clientDropped = true
        if (state != ServerState.Listening) clientDropped = false
        if (state == ServerState.Connected) streamError = null
        if (state == ServerState.Stopped || state == ServerState.Error) {
            setOrientationTracking(false)
            StreamService.stop(getApplication())
        }
        serverState = state
        serverInfo = info
    }
    private val tcpTransport = TcpServer(ServerPort, onState)
    private val aoaTransport = AoaTransport(application) { state, info ->
        if (state == ServerState.Error) streamError = info
        onState(state, info)
    }
    private var server: VideoTransport = tcpTransport
    private val streamer = VideoStreamer().also { it.attach(tcpTransport) }

    init {
        streamer.commandHandler = { type, payload -> mainHandler.post { handleCommand(type, payload) } }
    }

    private var pendingQuarter = 0
    private var appliedQuarter = 0
    private var orientationReady = false

    var rotationPrompt by mutableStateOf(false)
        private set

    private val orientationListener = object : OrientationEventListener(application) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            pendingQuarter = ((orientation + 45) / 90) % 4
            if (!orientationReady) {
                orientationReady = true
                appliedQuarter = pendingQuarter
                streamer.orientationQuarter = appliedQuarter
            }
            rotationPrompt = pendingQuarter != appliedQuarter
        }
    }

    fun applyRotation() {
        appliedQuarter = pendingQuarter
        streamer.orientationQuarter = appliedQuarter
        rotationPrompt = false
    }

    private fun setOrientationTracking(enabled: Boolean) {
        rotationPrompt = false
        if (enabled) {
            orientationReady = false
            if (orientationListener.canDetectOrientation()) orientationListener.enable()
        } else {
            orientationListener.disable()
        }
    }

    fun selectAoa(enabled: Boolean) {
        val next: VideoTransport = if (enabled) aoaTransport else tcpTransport
        if (next === server) return
        server.stop()
        server = next
        streamer.attach(next)
    }

    fun refreshAccessory() {
        aoaTransport.refresh()
    }

    fun startServer() {
        StreamService.start(getApplication())
        setOrientationTracking(true)
        server.start()
    }

    fun stopServer() {
        server.stop()
        setOrientationTracking(false)
        StreamService.stop(getApplication())
    }

    fun isServerRunning(): Boolean = server.isRunning()

    fun failStream(message: String) {
        streamError = message
        server.stop()
        setOrientationTracking(false)
        StreamService.stop(getApplication())
    }

    fun syncEncoder(next: EncoderSpec?) {
        if (next == spec) return
        spec = next
        if (next == null) {
            streamer.stop()
            encoderSurface = null
            return
        }
        encoderSurface = try {
            streamer.start(
                next.codec,
                next.width,
                next.height,
                next.fps,
                next.rotation,
                next.bitrate,
                next.constantBitrate
            ) { message -> mainHandler.post { failStream(message) } }
        } catch (e: Exception) {
            spec = null
            failStream(e.message ?: "Encoder start failed")
            null
        }
    }

    override fun onCleared() {
        streamer.stop()
        server.stop()
        cameraBackend.stop()
        setOrientationTracking(false)
        StreamService.stop(getApplication())
    }
}
