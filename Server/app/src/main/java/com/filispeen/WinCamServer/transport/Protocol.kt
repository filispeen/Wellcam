package com.filispeen.WinCamServer.transport

import com.filispeen.WinCamServer.encoder.VideoCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Protocol {
    const val VERSION = 2
    const val TYPE_CONFIG = 0
    const val TYPE_FRAME = 1
    const val FLAG_KEYFRAME = 1
    const val HEADER_SIZE = 14
    const val PACKET_HEADER_SIZE = 14

    private val MAGIC = byteArrayOf(0x57, 0x43, 0x41, 0x4D)

    fun codecId(codec: VideoCodec): Int = when (codec) {
        VideoCodec.H264 -> 1
        VideoCodec.H265 -> 2
    }

    fun header(codec: VideoCodec, width: Int, height: Int, fps: Int, rotation: Int): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC)
        buffer.put(VERSION.toByte())
        buffer.put(codecId(codec).toByte())
        buffer.putShort(width.toShort())
        buffer.putShort(height.toShort())
        buffer.putShort(fps.toShort())
        buffer.putShort(rotation.toShort())
        return buffer.array()
    }

    fun packet(type: Int, flags: Int, ptsUs: Long, payload: ByteArray, offset: Int, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(PACKET_HEADER_SIZE + length).order(ByteOrder.BIG_ENDIAN)
        buffer.put(type.toByte())
        buffer.put(flags.toByte())
        buffer.putLong(ptsUs)
        buffer.putInt(length)
        buffer.put(payload, offset, length)
        return buffer.array()
    }
}
