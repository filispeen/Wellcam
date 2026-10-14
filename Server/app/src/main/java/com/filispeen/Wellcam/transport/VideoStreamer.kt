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
            next.clientListener = this
        }
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
        } else {
            ready = false
        }
    }
}
