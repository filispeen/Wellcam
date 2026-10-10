package com.filispeen.WinCamServer.transport

enum class ServerState { Stopped, Listening, Connected, Error }

interface ClientListener {
    fun onClientConnected()
    fun onClientDisconnected()
}

interface VideoTransport {
    var clientListener: ClientListener?

    fun start()

    fun stop()

    fun isRunning(): Boolean

    fun pendingBytes(): Int

    fun send(data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean
}
