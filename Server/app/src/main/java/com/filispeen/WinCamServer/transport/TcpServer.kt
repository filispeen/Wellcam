package com.filispeen.WinCamServer.transport

import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

enum class ServerState { Stopped, Listening, Connected, Error }

interface ClientListener {
    fun onClientConnected()
    fun onClientDisconnected()
}

class TcpServer(
    private val port: Int,
    private val onState: (ServerState, String?) -> Unit
) {
    @Volatile
    var clientListener: ClientListener? = null

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var token: Any? = null
    private var server: ServerSocket? = null
    private var client: Socket? = null

    fun start() {
        val t = Any()
        synchronized(lock) {
            if (token != null) return
            token = t
        }
        Thread({ acceptLoop(t) }, "wincam-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        synchronized(lock) {
            if (token == null) return
            token = null
            closeQuietly(client)
            closeQuietly(server)
            client = null
            server = null
        }
        post(null, ServerState.Stopped, null)
    }

    fun send(data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean {
        val socket = synchronized(lock) { client } ?: return false
        return try {
            synchronized(socket) { socket.getOutputStream().write(data, offset, length) }
            true
        } catch (e: IOException) {
            closeQuietly(socket)
            false
        }
    }

    private fun alive(t: Any): Boolean = synchronized(lock) { token === t }

    private fun acceptLoop(t: Any) {
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: IOException) {
            fail(t, e)
            return
        }
        synchronized(lock) {
            if (token !== t) {
                closeQuietly(ss)
                return
            }
            server = ss
        }
        post(t, ServerState.Listening, null)
        while (alive(t)) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                if (alive(t)) fail(t, e)
                return
            }
            handle(t, socket)
        }
    }

    private fun handle(t: Any, socket: Socket) {
        socket.tcpNoDelay = true
        synchronized(lock) {
            if (token !== t) {
                closeQuietly(socket)
                return
            }
            closeQuietly(client)
            client = socket
        }
        post(t, ServerState.Connected, socket.inetAddress?.hostAddress)
        clientListener?.onClientConnected()
        try {
            val input = socket.getInputStream()
            val buffer = ByteArray(1024)
            while (alive(t) && input.read(buffer) >= 0) {
            }
        } catch (e: IOException) {
        }
        val wasCurrent = synchronized(lock) {
            if (client === socket) {
                client = null
                true
            } else {
                false
            }
        }
        closeQuietly(socket)
        if (wasCurrent) clientListener?.onClientDisconnected()
        if (alive(t)) post(t, ServerState.Listening, null)
    }

    private fun fail(t: Any, e: IOException) {
        synchronized(lock) {
            if (token !== t) return
            token = null
            closeQuietly(client)
            closeQuietly(server)
            client = null
            server = null
        }
        post(null, ServerState.Error, e.message ?: "Socket error")
    }

    private fun post(t: Any?, state: ServerState, info: String?) {
        main.post {
            if (t == null || synchronized(lock) { token === t }) onState(state, info)
        }
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (e: IOException) {
        }
    }
}
