package com.filispeen.Wellcam.transport

enum class ServerState { Stopped, Listening, Connected, Error }

interface ClientListener {
    fun onClientConnected()
    fun onClientDisconnected()

    fun onCommand(type: Int, payload: String) {
    }
}

interface VideoTransport {
    var clientListener: ClientListener?

    fun start()

    fun stop()

    fun isRunning(): Boolean

    fun pendingBytes(): Int

    fun send(data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean
}
