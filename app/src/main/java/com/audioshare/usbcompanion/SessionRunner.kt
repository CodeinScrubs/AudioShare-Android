package com.audioshare.usbcompanion

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.PowerManager
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SessionRunner(
    private val context: Context,
    private val config: SessionConfig,
    private val listener: Listener,
) : Closeable {
    interface Listener {
        fun onState(source: SessionRunner, state: String)
        fun onStopped(source: SessionRunner, error: String?)
    }

    private val running = AtomicBoolean(true)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "AudioShare-Watchdog")
    }
    private val thread = Thread(::runSession, "AudioShare-Session")
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioShare:UsbPlayback")
        .apply { setReferenceCounted(false) }

    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var client: LocalSocket? = null

    fun start() = thread.start()

    private fun runSession() {
        var playback: PlaybackWorker? = null
        var connectTimeout: ScheduledFuture<*>? = null
        var errorMessage: String? = null
        try {
            wakeLock.acquire()
            listener.onState(this, "Waiting for PC")
            val localServer = LocalServerSocket(config.socketName)
            server = localServer
            connectTimeout = scheduler.schedule({ closeTransport() }, 20, TimeUnit.SECONDS)
            val localClient = localServer.accept()
            client = localClient
            connectTimeout.cancel(false)
            localClient.soTimeout = 10_000
            listener.onState(this, "Authenticating")

            val first = WireProtocol.readFrame(localClient.inputStream)
                ?: throw ProtocolException("Host disconnected before HELLO")
            val hello = WireProtocol.parseHello(first, config.token)
            playback = PlaybackWorker(context, hello)
            val readyPayload = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                .putInt(hello.sampleRate)
                .putInt(hello.channels)
                .putInt(hello.bitsPerSample)
                .putInt(playback.configuredBufferFrames)
                .array()
            send(WireProtocol.Frame(WireProtocol.Type.READY, first.sequence, readyPayload))
            listener.onState(this, "Receiving audio")

            var lastSequence = first.sequence
            while (running.get()) {
                val frame = WireProtocol.readFrame(localClient.inputStream) ?: break
                if (frame.sequence < lastSequence) throw ProtocolException("Sequence moved backwards")
                lastSequence = frame.sequence
                when (frame.type) {
                    WireProtocol.Type.PCM -> playback.enqueue(frame.payload)
                    WireProtocol.Type.PING -> send(
                        WireProtocol.Frame(WireProtocol.Type.PONG, frame.sequence, ByteArray(0)),
                    )
                    WireProtocol.Type.STATS -> send(
                        WireProtocol.Frame(WireProtocol.Type.STATS, frame.sequence, playback.statsPayload()),
                    )
                    WireProtocol.Type.STOP -> break
                    else -> throw ProtocolException("Unexpected ${frame.type} while streaming")
                }
            }
        } catch (error: Throwable) {
            if (running.get()) {
                errorMessage = safeMessage(error)
                try {
                    send(
                        WireProtocol.Frame(
                            WireProtocol.Type.ERROR,
                            0,
                            errorMessage.toByteArray(StandardCharsets.UTF_8),
                        ),
                    )
                } catch (_: Throwable) {
                    // The transport may be the reason the session failed.
                }
            }
        } finally {
            running.set(false)
            connectTimeout?.cancel(false)
            playback?.close()
            closeTransport()
            scheduler.shutdownNow()
            if (wakeLock.isHeld) wakeLock.release()
            listener.onStopped(this, errorMessage)
        }
    }

    @Synchronized
    private fun send(frame: WireProtocol.Frame) {
        val socket = client ?: throw IOException("No host connection")
        WireProtocol.writeFrame(socket.outputStream, frame)
    }

    private fun closeTransport() {
        try {
            client?.close()
        } catch (_: IOException) {
        }
        try {
            server?.close()
        } catch (_: IOException) {
        }
    }

    private fun safeMessage(error: Throwable): String =
        when (error) {
            is ProtocolException -> error.message ?: "Protocol error"
            is IOException -> "Transport closed: ${error.message ?: "I/O error"}"
            else -> error.message ?: error.javaClass.simpleName
        }.take(240)

    override fun close() {
        if (!running.getAndSet(false)) return
        closeTransport()
        thread.interrupt()
        thread.join(3_000)
        scheduler.shutdownNow()
    }
}
