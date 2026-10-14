package com.filispeen.WinCamServer

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.filispeen.WinCamServer.camera.CameraBackend
import com.filispeen.WinCamServer.camera.CameraBackends
import com.filispeen.WinCamServer.encoder.BitrateSettings
import com.filispeen.WinCamServer.encoder.VideoCodec
import com.filispeen.WinCamServer.transport.AoaTransport
import com.filispeen.WinCamServer.transport.ServerState
import com.filispeen.WinCamServer.transport.TcpServer
import com.filispeen.WinCamServer.transport.VideoStreamer
import com.filispeen.WinCamServer.transport.VideoTransport

const val ServerPort = 11203

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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var spec: EncoderSpec? = null

    val cameraBackend: CameraBackend by lazy { CameraBackends.create(application) }

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
