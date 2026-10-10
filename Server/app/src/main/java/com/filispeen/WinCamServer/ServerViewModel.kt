package com.filispeen.WinCamServer

import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.filispeen.WinCamServer.encoder.BitrateSettings
import com.filispeen.WinCamServer.encoder.VideoCodec
import com.filispeen.WinCamServer.transport.ServerState
import com.filispeen.WinCamServer.transport.TcpServer
import com.filispeen.WinCamServer.transport.VideoStreamer

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

class ServerViewModel : ViewModel() {
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

    private val server = TcpServer(ServerPort) { state, info ->
        if (state == ServerState.Listening && serverState == ServerState.Connected) clientDropped = true
        if (state != ServerState.Listening) clientDropped = false
        if (state == ServerState.Connected) streamError = null
        serverState = state
        serverInfo = info
    }
    private val streamer = VideoStreamer(server)

    fun startServer() {
        server.start()
    }

    fun stopServer() {
        server.stop()
    }

    fun isServerRunning(): Boolean = server.isRunning()

    fun failStream(message: String) {
        streamError = message
        server.stop()
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
    }
}
