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
    private val terminated = java.util.concurrent.CountDownLatch(1)
    private var started = false
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "AudioShare-Watchdog")
    }
    private val thread = Thread(::runSession, "AudioShare-Session")
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioShare:UsbPlayback")
        .apply { setReferenceCounted(false) }

    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var client: LocalSocket? = null

    @Synchronized
    fun start(): Boolean {
        if (started || !running.get()) {
            if (!started) terminated.countDown()
            return false
        }
        started = true
        return try {
            thread.start()
            true
        } catch (error: RuntimeException) {
            terminated.countDown()
            throw error
        }
    }

    private fun runSession() {
        var playback: PlaybackWorker? = null
        var connectTimeout: ScheduledFuture<*>? = null
        var errorMessage: String? = null
        try {
            // A non-reference-counted timeout is a final leak guard if an OEM
            // wedges every normal cleanup path. Twelve hours is well above
            // the supported two-hour soak gate and ordinary listening use.
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            listener.onState(this, "Waiting for PC")
            val localServer = LocalServerSocket(config.socketName)
            server = localServer
            // The watchdog closes only the listening socket. Closing the whole
            // transport here races with accept(): a client accepted at the
            // deadline is already valid and must be allowed to authenticate.
            connectTimeout = scheduler.schedule(
                { transportLifecycle.closeAcceptWait() },
                20,
                TimeUnit.SECONDS,
            )
            val localClient = localServer.accept()
            client = localClient
            if (!transportLifecycle.continueAfterAccept(running.get())) return
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
                WireProtocol.requireStrictlyIncreasingSequence(lastSequence, frame.sequence)
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
            val playbackStopped = playback?.closeAndAwait() ?: true
            if (!playbackStopped) {
                // Keep this session's termination latch closed until the old
                // AudioTrack thread really exits. PlaybackService therefore
                // times out and refuses to overlap a replacement session.
                playback?.awaitTerminationUninterruptibly()
            }
            closeTransport()
            scheduler.shutdownNow()
            if (wakeLock.isHeld) wakeLock.release()
            try {
                listener.onStopped(this, errorMessage)
            } finally {
                terminated.countDown()
            }
        }
    }

    @Synchronized
    private fun send(frame: WireProtocol.Frame) {
        val socket = client ?: throw IOException("No host connection")
        WireProtocol.writeFrame(socket.outputStream, frame)
    }

    private fun closeTransport() {
        transportLifecycle.closeAll()
    }

    private val transportLifecycle = SessionTransportLifecycle(
        closeClient = {
            try {
                client?.close()
            } catch (_: IOException) {
            }
        },
        closeServer = {
            try {
                server?.close()
            } catch (_: IOException) {
            }
        },
    )

    private fun safeMessage(error: Throwable): String =
        when (error) {
            is ProtocolException -> error.message ?: "Protocol error"
            is IOException -> "Transport closed: ${error.message ?: "I/O error"}"
            else -> error.message ?: error.javaClass.simpleName
        }.take(240)

    override fun close() {
        closeAndAwait(SESSION_SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    }

    fun closeAndAwait(timeout: Long, unit: TimeUnit): Boolean {
        running.set(false)
        closeTransport()
        scheduler.shutdownNow()
        val shouldInterrupt = synchronized(this) {
            if (!started) {
                terminated.countDown()
                false
            } else {
                true
            }
        }
        if (shouldInterrupt) thread.interrupt()
        return try {
            terminated.await(timeout, unit)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    companion object {
        private val WAKE_LOCK_TIMEOUT_MILLIS = TimeUnit.HOURS.toMillis(12)
        val SESSION_SHUTDOWN_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(8)
    }
}
