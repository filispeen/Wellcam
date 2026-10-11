package com.filispeen.WinCamServer.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

const val AoaManufacturer = "Filispeen"
const val AoaModel = "WinCam"
const val AoaVersion = "1"

private const val AoaWriteTimeoutMs = 3000L
private const val AoaMaxPendingBytes = 8 * 1024 * 1024
private const val AoaWriteSlice = 16 * 1024
private const val ActionPermission = "com.filispeen.WinCamServer.USB_ACCESSORY_PERMISSION"

class AoaTransport(
    context: Context,
    private val onState: (ServerState, String?) -> Unit
) : VideoTransport {
    @Volatile
    override var clientListener: ClientListener? = null

    private val app = context.applicationContext
    private val usb by lazy { app.getSystemService(Context.USB_SERVICE) as UsbManager }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var token: Any? = null
    private var session: Session? = null
    private var receiver: BroadcastReceiver? = null
    private var permissionRequested = false

    override fun start() {
        val t = Any()
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handleBroadcast(t, intent)
            }
        }
        synchronized(lock) {
            if (token != null) return
            token = t
            receiver = r
        }
        val filter = IntentFilter().apply {
            addAction(ActionPermission)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        post(t, ServerState.Listening, null)
        main.post { if (alive(t)) tryOpen(t) }
    }

    override fun stop() {
        val s: Session?
        val r: BroadcastReceiver?
        synchronized(lock) {
            if (token == null) return
            token = null
            s = session
            session = null
            r = receiver
            receiver = null
            permissionRequested = false
        }
        unregister(r)
        s?.close()
        if (s != null) clientListener?.onClientDisconnected()
        post(null, ServerState.Stopped, null)
    }

    override fun isRunning(): Boolean = synchronized(lock) { token != null }

    override fun pendingBytes(): Int = synchronized(lock) { session }?.pending() ?: 0

    override fun send(data: ByteArray, offset: Int, length: Int): Boolean {
        val s = synchronized(lock) { session } ?: return false
        return s.enqueue(data, offset, length)
    }

    fun refresh() {
        val t = synchronized(lock) { token } ?: return
        main.post { if (alive(t)) tryOpen(t) }
    }

    private fun alive(t: Any): Boolean = synchronized(lock) { token === t }

    private fun unregister(r: BroadcastReceiver?) {
        if (r == null) return
        try {
            app.unregisterReceiver(r)
        } catch (e: IllegalArgumentException) {
        }
    }

    private fun handleBroadcast(t: Any, intent: Intent) {
        when (intent.action) {
            ActionPermission -> {
                synchronized(lock) { permissionRequested = false }
                if (!alive(t)) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) tryOpen(t) else fail(t, "USB accessory permission denied")
            }
            UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                val s = synchronized(lock) { session }
                if (s != null) endSession(s)
            }
        }
    }

    private fun permissionIntent(): PendingIntent {
        val intent = Intent(ActionPermission).setPackage(app.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(app, 0, intent, flags)
    }

    private fun tryOpen(t: Any) {
        if (synchronized(lock) { session != null }) return
        val accessory = usb.accessoryList?.firstOrNull {
            it.manufacturer == AoaManufacturer && it.model == AoaModel
        } ?: return
        if (usb.hasPermission(accessory)) {
            open(t, accessory)
            return
        }
        synchronized(lock) {
            if (permissionRequested) return
            permissionRequested = true
        }
        usb.requestPermission(accessory, permissionIntent())
    }

    private fun open(t: Any, accessory: UsbAccessory) {
        val pfd = try {
            usb.openAccessory(accessory)
        } catch (e: Exception) {
            null
        }
        if (pfd == null) {
            fail(t, "Cannot open USB accessory")
            return
        }
        val s = Session(pfd)
        val installed = synchronized(lock) {
            if (token === t && session == null) {
                session = s
                true
            } else {
                false
            }
        }
        if (!installed) {
            closeQuietly(pfd)
            return
        }
        s.start()
        post(t, ServerState.Connected, "USB host")
        clientListener?.onClientConnected()
    }

    private fun endSession(s: Session) {
        val current = synchronized(lock) {
            if (session === s) {
                session = null
                permissionRequested = false
                true
            } else {
                false
            }
        }
        s.close()
        if (!current) return
        clientListener?.onClientDisconnected()
        val t = synchronized(lock) { token }
        if (t != null) post(t, ServerState.Listening, null)
    }

    private fun fail(t: Any, message: String) {
        val s: Session?
        val r: BroadcastReceiver?
        synchronized(lock) {
            if (token !== t) return
            token = null
            s = session
            session = null
            r = receiver
            receiver = null
            permissionRequested = false
        }
        unregister(r)
        s?.close()
        if (s != null) clientListener?.onClientDisconnected()
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

    private inner class Session(private val pfd: ParcelFileDescriptor) {
        private val input = FileInputStream(pfd.fileDescriptor)
        private val output = FileOutputStream(pfd.fileDescriptor)
        private val queue = LinkedBlockingQueue<Chunk>()
        private val pendingCount = AtomicInteger(0)
        private val writerThread = Thread({ writeLoop() }, "wincam-aoa-write").apply { isDaemon = true }
        private val readerThread = Thread({ readLoop() }, "wincam-aoa-read").apply { isDaemon = true }

        @Volatile
        private var writeStartedAt = 0L

        @Volatile
        private var closed = false

        fun start() {
            writerThread.start()
            readerThread.start()
        }

        fun pending(): Int = pendingCount.get()

        fun enqueue(data: ByteArray, offset: Int, length: Int): Boolean {
            if (closed) return false
            val started = writeStartedAt
            if (started != 0L && SystemClock.elapsedRealtime() - started > AoaWriteTimeoutMs) {
                abort()
                return false
            }
            if (pendingCount.get() + length > AoaMaxPendingBytes) {
                abort()
                return false
            }
            pendingCount.addAndGet(length)
            queue.put(Chunk(data, offset, length))
            return true
        }

        fun close() {
            closed = true
            writerThread.interrupt()
            closeQuietly(pfd)
        }

        private fun abort() {
            closed = true
            main.post { endSession(this) }
        }

        private fun writeLoop() {
            try {
                while (!closed) {
                    val chunk = queue.take()
                    var offset = chunk.offset
                    var left = chunk.length
                    while (left > 0) {
                        val n = minOf(left, AoaWriteSlice)
                        writeStartedAt = SystemClock.elapsedRealtime()
                        output.write(chunk.data, offset, n)
                        writeStartedAt = 0L
                        offset += n
                        left -= n
                    }
                    pendingCount.addAndGet(-chunk.length)
                }
            } catch (e: IOException) {
            } catch (e: InterruptedException) {
            }
            writeStartedAt = 0L
            endSession(this)
        }

        private fun readLoop() {
            try {
                val buffer = ByteArray(1024)
                while (!closed && input.read(buffer) >= 0) {
                }
            } catch (e: IOException) {
            }
            endSession(this)
        }
    }
}
