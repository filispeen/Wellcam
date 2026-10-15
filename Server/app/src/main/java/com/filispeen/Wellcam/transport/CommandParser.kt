package com.filispeen.Wellcam.transport

const val CommandStateRequest = 1
const val CommandSet = 2

private const val CommandHeaderSize = 7
private const val MaxBuffered = 128 * 1024
private val CommandMagic = byteArrayOf(0x57, 0x43, 0x4D, 0x44)

class CommandParser(
    private val onHello: () -> Unit,
    private val onCommand: (Int, String) -> Unit
) {
    private var buffer = ByteArray(1024)
    private var size = 0

    fun feed(data: ByteArray, length: Int) {
        if (length <= 0) return
        if (size + length > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + length))
        System.arraycopy(data, 0, buffer, size, length)
        size += length
        var pos = 0
        var helloSent = false
        while (pos < size) {
            val available = size - pos
            var prefix = true
            for (i in 0 until minOf(available, CommandMagic.size)) {
                if (buffer[pos + i] != CommandMagic[i]) {
                    prefix = false
                    break
                }
            }
            if (!prefix) {
                if (!helloSent) {
                    helloSent = true
                    onHello()
                }
                pos++
                continue
            }
            if (available < CommandHeaderSize) break
            val type = buffer[pos + 4].toInt() and 0xFF
            val payloadSize = ((buffer[pos + 5].toInt() and 0xFF) shl 8) or (buffer[pos + 6].toInt() and 0xFF)
            if (available < CommandHeaderSize + payloadSize) break
            val payload = String(buffer, pos + CommandHeaderSize, payloadSize, Charsets.UTF_8)
            pos += CommandHeaderSize + payloadSize
            onCommand(type, payload)
        }
        val left = size - pos
        if (left > 0 && pos > 0) System.arraycopy(buffer, pos, buffer, 0, left)
        size = if (left > MaxBuffered) 0 else left
    }
}
