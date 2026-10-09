package com.filispeen.WinCamServer.encoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Range
import java.util.Locale

enum class VideoCodec(val label: String, val mime: String, val bitsPerPixel: Float) {
    H264("H.264", MediaFormat.MIMETYPE_VIDEO_AVC, 0.20f),
    H265("H.265", MediaFormat.MIMETYPE_VIDEO_HEVC, 0.14f)
}

data class BitrateSettings(val bitrate: Int, val min: Int, val max: Int)

object EncoderSupport {
    private fun encoders(codec: VideoCodec): List<MediaCodecInfo> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(codec.mime, ignoreCase = true) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun supportedCodecs(): List<VideoCodec> =
        VideoCodec.values().filter { encoders(it).isNotEmpty() }

    private fun bitrateRange(codec: VideoCodec): Range<Int>? {
        val ranges = encoders(codec).mapNotNull { info ->
            try {
                info.getCapabilitiesForType(codec.mime).videoCapabilities?.bitrateRange
            } catch (e: Exception) {
                null
            }
        }
        if (ranges.isEmpty()) return null
        return Range(ranges.minOf { it.lower }, ranges.maxOf { it.upper })
    }

    fun autoBitrate(codec: VideoCodec, width: Int, height: Int, fps: Int): BitrateSettings {
        val range = bitrateRange(codec)
        val low = range?.lower?.toLong() ?: 0L
        val high = range?.upper?.toLong() ?: Int.MAX_VALUE.toLong()
        val raw = (width.toLong() * height * fps * codec.bitsPerPixel).toLong()
        val bitrate = raw.coerceIn(low, high)
        val maxValue = (bitrate * 3 / 2).coerceAtMost(high)
        val minValue = (bitrate / 2).coerceAtLeast(low)
        return BitrateSettings(bitrate.toInt(), minValue.toInt(), maxValue.toInt())
    }

    fun formatMbps(bitsPerSecond: Int): String =
        String.format(Locale.US, "%.1f", bitsPerSecond / 1_000_000f)
}
