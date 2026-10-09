package com.filispeen.WinCamServer.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface

class VideoEncoder(
    private val codec: VideoCodec,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: BitrateSettings,
    private val constantBitrate: Boolean,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (ByteArray, Boolean, Long) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile
    private var running = false
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var thread: Thread? = null

    fun start(): Surface {
        val encoder = MediaCodec.createEncoderByType(codec.mime)
        try {
            val format = MediaFormat.createVideoFormat(codec.mime, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate.bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode(encoder))
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()
            mediaCodec = encoder
            inputSurface = surface
            running = true
            thread = Thread({ drain(encoder) }, "wincam-encoder").also { it.start() }
            return surface
        } catch (e: Exception) {
            runCatching { encoder.release() }
            throw IllegalStateException("Encoder start failed: ${e.message}", e)
        }
    }

    private fun bitrateMode(encoder: MediaCodec): Int {
        val wanted = if (constantBitrate) {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        } else {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        }
        val supported = try {
            encoder.codecInfo.getCapabilitiesForType(codec.mime).encoderCapabilities?.isBitrateModeSupported(wanted) == true
        } catch (e: Exception) {
            false
        }
        return if (supported) wanted else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
    }

    private fun drain(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var base = -1L
        try {
            while (running) {
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                if (index < 0) continue
                val buffer = encoder.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buffer.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        onConfig(bytes)
                    } else {
                        if (base < 0) base = info.presentationTimeUs
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        onFrame(bytes, key, info.presentationTimeUs - base)
                    }
                }
                encoder.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } catch (e: Exception) {
            if (running) onError("Encoder failed: ${e.message}")
        }
    }

    fun requestSync() {
        val encoder = mediaCodec ?: return
        runCatching {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            encoder.setParameters(params)
        }
    }

    fun release() {
        running = false
        val worker = thread
        thread = null
        if (worker != null && worker !== Thread.currentThread()) {
            runCatching { worker.join(500) }
        }
        val encoder = mediaCodec
        val surface = inputSurface
        mediaCodec = null
        inputSurface = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { surface?.release() }
    }
}
