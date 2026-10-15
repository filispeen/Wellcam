package com.filispeen.Wellcam.transport

import android.os.SystemClock
import android.view.Surface
import com.filispeen.Wellcam.encoder.BitrateSettings
import com.filispeen.Wellcam.encoder.VideoCodec
import com.filispeen.Wellcam.encoder.VideoEncoder

private const val BacklogLimit = 2 * 1024 * 1024
private const val SyncRetryMs = 500L

class VideoStreamer : ClientListener {
    private val lock = Any()
    private var transport: VideoTransport? = null
    private var encoder: VideoEncoder? = null
    private var header: ByteArray? = null
    private var config: ByteArray? = null
    private var clientPresent = false
    private var ready = false
    private var waitingKey = false
    private var lastSyncMs = 0L
    private var optedIn = false
    private var latestState: ByteArray? = null
    private var lastState: String? = null

    @Volatile
    var commandHandler: ((Int, String) -> Unit)? = null

    val stateRequested: Boolean
        get() = synchronized(lock) { optedIn }

    @Volatile
    var orientationQuarter: Int = 0
    fun attach(next: VideoTransport) {
        synchronized(lock) {
            if (transport === next) return
            transport?.clientListener = null
            transport = next
            clientPresent = false
            ready = false
            waitingKey = false
            resetState()
            next.clientListener = this
        }
    }

    private fun resetState() {
        optedIn = false
        latestState = null
        lastState = null
    }

    fun publishState(json: String) {
        synchronized(lock) {
            if (!optedIn || json == lastState) return
            lastState = json
            val bytes = json.toByteArray(Charsets.UTF_8)
            val packet = Protocol.packet(Protocol.TYPE_STATE, 0, 0L, bytes, 0, bytes.size)
            latestState = packet
            if (clientPresent && ready) transport?.send(packet)
        }
    }

    override fun onCommand(type: Int, payload: String) {
        synchronized(lock) {
            if (!optedIn) {
                optedIn = true
                lastState = null
            }
        }
        commandHandler?.invoke(type, payload)
    }

    fun start(
        codec: VideoCodec,
        width: Int,
        height: Int,
        fps: Int,
        rotation: Int,
        bitrate: BitrateSettings,
        constantBitrate: Boolean,
        onError: (String) -> Unit
    ): Surface {
        stop()
        val created = VideoEncoder(
            codec,
            width,
            height,
            fps,
            bitrate,
            constantBitrate,
            { bytes -> handleConfig(bytes) },
            { bytes, key, pts -> handleFrame(bytes, key, pts) },
            onError
        )
        val surface = created.start()
        synchronized(lock) {
            encoder = created
            header = Protocol.header(codec, width, height, fps, rotation)
            config = null
            ready = false
            waitingKey = false
        }
        return surface
    }

    fun stop() {
        val old = synchronized(lock) {
            val current = encoder
            encoder = null
            header = null
            config = null
            ready = false
            waitingKey = false
            current
        }
        old?.release()
    }

    override fun onClientConnected() {
        val current = synchronized(lock) {
            clientPresent = true
            ready = false
            sendIntro()
            encoder
        }
        current?.requestSync()
    }

    override fun onClientDisconnected() {
        synchronized(lock) {
            clientPresent = false
            ready = false
            resetState()
        }
    }

    private fun handleConfig(bytes: ByteArray) {
        synchronized(lock) {
            config = Protocol.packet(Protocol.TYPE_CONFIG, 0, 0L, bytes, 0, bytes.size)
            if (clientPresent) sendIntro()
        }
    }

    private fun handleFrame(bytes: ByteArray, key: Boolean, ptsUs: Long) {
        var syncTarget: VideoEncoder? = null
        synchronized(lock) {
            if (!ready) return
            val target = transport ?: return
            val congested = target.pendingBytes() > BacklogLimit
            if (congested) waitingKey = true
            if (waitingKey && (congested || !key)) {
                val now = SystemClock.elapsedRealtime()
                if (!congested && now - lastSyncMs > SyncRetryMs) {
                    lastSyncMs = now
                    syncTarget = encoder
                }
            } else {
                waitingKey = false
                val orientation = (orientationQuarter and 3) shl 1
                val flags = (if (key) Protocol.FLAG_KEYFRAME else 0) or orientation
                val packet = Protocol.packet(Protocol.TYPE_FRAME, flags, ptsUs, bytes, 0, bytes.size)
                if (!target.send(packet)) ready = false
            }
        }
        syncTarget?.requestSync()
    }

    private fun sendIntro() {
        val h = header ?: return
        val c = config ?: return
        val target = transport ?: return
        if (target.send(h) && target.send(c)) {
            ready = true
            waitingKey = true
            val state = latestState
            if (optedIn && state != null) target.send(state)
        } else {
            ready = false
        }
    }
}
