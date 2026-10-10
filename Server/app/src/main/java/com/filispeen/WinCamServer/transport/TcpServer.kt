package com.filispeen.WinCamServer.transport

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

enum class ServerState { Stopped, Listening, Connected, Error }

interface ClientListener {
    fun onClientConnected()
    fun onClientDisconnected()
}

private const val WriteTimeoutMs = 3000L
private const val MaxPendingBytes = 8 * 1024 * 1024

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
    private var writer: Writer? = null

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
            releaseClient()
            closeQuietly(server)
            server = null
        }
        post(null, ServerState.Stopped, null)
    }

    fun isRunning(): Boolean = synchronized(lock) { token != null }

    fun pendingBytes(): Int = synchronized(lock) { writer }?.pending() ?: 0

    fun send(data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean {
        val w = synchronized(lock) { writer } ?: return false
        return w.enqueue(data, offset, length)
    }

    private fun releaseClient() {
        writer?.shutdown()
        writer = null
        closeQuietly(client)
        client = null
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
        val w = Writer(socket)
        synchronized(lock) {
            if (token !== t) {
                closeQuietly(socket)
                return
            }
            releaseClient()
            client = socket
            writer = w
        }
        w.start()
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
                writer = null
                true
            } else {
                false
            }
        }
        w.shutdown()
        closeQuietly(socket)
        if (wasCurrent) clientListener?.onClientDisconnected()
        if (alive(t)) post(t, ServerState.Listening, null)
    }

    private fun fail(t: Any, e: IOException) {
        synchronized(lock) {
            if (token !== t) return
            token = null
            releaseClient()
            closeQuietly(server)
            server = null
        }
        val message = when {
            e is BindException && e.message.orEmpty().let {
                it.contains("EADDRINUSE") || it.contains("already in use", ignoreCase = true)
            } -> "Port $port is already in use"
            e is BindException -> "Cannot bind port $port"
            else -> e.message ?: "Socket error"
        }
        post(null, ServerState.Error, message)
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

    private class Chunk(val data: ByteArray, val offset: Int, val length: Int)

    private class Writer(private val socket: Socket) : Thread("wincam-write") {
        private val queue = LinkedBlockingQueue<Chunk>()
        private val pendingBytes = AtomicInteger(0)

        @Volatile
        private var writeStartedAt = 0L

        @Volatile
        private var closed = false

        init {
            isDaemon = true
        }

        fun pending(): Int = pendingBytes.get()

        fun enqueue(data: ByteArray, offset: Int, length: Int): Boolean {
            if (closed) return false
            val started = writeStartedAt
            if (started != 0L && SystemClock.elapsedRealtime() - started > WriteTimeoutMs) {
                abort()
                return false
            }
            if (pendingBytes.get() + length > MaxPendingBytes) {
                abort()
                return false
            }
            pendingBytes.addAndGet(length)
            queue.put(Chunk(data, offset, length))
            return true
        }

        fun shutdown() {
            closed = true
            interrupt()
        }

        private fun abort() {
            closed = true
            try {
                socket.close()
            } catch (e: IOException) {
            }
            interrupt()
        }

        override fun run() {
            try {
                val out = socket.getOutputStream()
                while (!closed) {
                    val chunk = queue.take()
                    writeStartedAt = SystemClock.elapsedRealtime()
                    out.write(chunk.data, chunk.offset, chunk.length)
                    writeStartedAt = 0L
                    pendingBytes.addAndGet(-chunk.length)
                }
            } catch (e: IOException) {
            } catch (e: InterruptedException) {
            }
            closed = true
            writeStartedAt = 0L
            try {
                socket.close()
            } catch (e: IOException) {
            }
        }
    }
}
