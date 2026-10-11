package com.filispeen.WinCamServer.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
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

    private companion object {
        const val TAG = "WinCamEncoder"
    }

    fun start(): Surface {
        var lastError: Exception? = null
        for (extras in listOf(true, false)) {
            var encoder: MediaCodec? = null
            try {
                val created = MediaCodec.createEncoderByType(codec.mime)
                encoder = created
                checkSupport(created)
                val format = MediaFormat.createVideoFormat(codec.mime, width, height)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate.bitrate)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (extras) {
                    format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                    format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode(created))
                }
                created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = created.createInputSurface()
                created.start()
                mediaCodec = created
                inputSurface = surface
                running = true
                thread = Thread({ drain(created) }, "wincam-encoder").also { it.start() }
                return surface
            } catch (e: Exception) {
                Log.e(TAG, "Encoder attempt failed (extras=$extras, ${codec.mime} ${width}x$height@$fps, ${bitrate.bitrate} bps)", e)
                runCatching { encoder?.release() }
                lastError = e
                if (e is UnsupportedConfigException) break
            }
        }
        throw IllegalStateException("Encoder start failed: ${describe(lastError)}", lastError)
    }

    private class UnsupportedConfigException(message: String) : IllegalStateException(message)

    private fun checkSupport(encoder: MediaCodec) {
        val caps = try {
            encoder.codecInfo.getCapabilitiesForType(codec.mime).videoCapabilities
        } catch (e: Exception) {
            null
        } ?: return
        val name = encoder.name
        if (!caps.supportedWidths.contains(width) || !caps.supportedHeights.contains(height)) {
            throw UnsupportedConfigException(
                "$name does not support ${width}x$height (width ${caps.supportedWidths}, height ${caps.supportedHeights})"
            )
        }
        if (!caps.supportedFrameRates.contains(fps)) {
            throw UnsupportedConfigException("$name does not support $fps fps (supported ${caps.supportedFrameRates})")
        }
        if (!caps.bitrateRange.contains(bitrate.bitrate)) {
            throw UnsupportedConfigException("$name bitrate ${bitrate.bitrate} is outside ${caps.bitrateRange}")
        }
    }

    private fun describe(error: Throwable?): String {
        if (error == null) return "unknown error"
        val detail = error.message ?: error.cause?.message
        val extra = if (error is MediaCodec.CodecException) " code=${error.errorCode} diag=${error.diagnosticInfo}" else ""
        return buildString {
            append(error.javaClass.simpleName)
            if (detail != null) append(": ").append(detail)
            append(extra)
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
            Log.e(TAG, "Encoder drain failed", e)
            if (running) onError("Encoder failed: ${describe(e)}")
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
