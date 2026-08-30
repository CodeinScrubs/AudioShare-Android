package com.audioshare.usbcompanion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class PlaybackWorker(
    private val context: Context,
    private val format: WireProtocol.Hello,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    // The socket reader and AudioTrack writer are deliberately decoupled, but
    // stalled audio is never allowed to become permanent lag. The duration-
    // based queue retains at most an 80 ms live edge (or one indivisible input
    // chunk) and accounts every older complete frame it drops.
    private val queue = LiveEdgePcmQueue(bytesPerFrame(), format.sampleRate)
    private val ready = CountDownLatch(1)
    private val startupError = AtomicReference<Throwable?>()
    private val runtimeError = AtomicReference<Throwable?>()
    private val activeTrack = AtomicReference<AudioTrack?>()
    private val droppedFrames = AtomicLong(0)
    private val receivedFrames = AtomicLong(0)
    private val thread = Thread(::runPlayback, "AudioShare-Playback")

    @Volatile
    var configuredBufferFrames: Int = 0
        private set

    init {
        thread.start()
        if (!ready.await(5, TimeUnit.SECONDS)) {
            close()
            throw IllegalStateException("AudioTrack initialization timed out")
        }
        startupError.get()?.let { throw IllegalStateException("AudioTrack initialization failed", it) }
    }

    fun enqueue(payload: ByteArray) {
        if (!running.get()) return
        runtimeError.get()?.let { throw IllegalStateException("AudioTrack playback failed", it) }
        if (payload.isEmpty() || payload.size % bytesPerFrame() != 0) {
            throw IllegalArgumentException("PCM payload is not frame-aligned")
        }
        receivedFrames.addAndGet(payload.size.toLong() / bytesPerFrame())
        droppedFrames.addAndGet(queue.offer(payload))
    }

    fun statsPayload(): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putLong(receivedFrames.get())
        buffer.putLong(droppedFrames.get())
        buffer.putInt(queue.snapshot().chunks)
        buffer.putInt(configuredBufferFrames)
        return buffer.array()
    }

    private fun runPlayback() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var track: AudioTrack? = null
        try {
            val channelMask = if (format.channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val minimumBytes = AudioTrack.getMinBufferSize(
                format.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimumBytes <= 0) throw IllegalStateException("Invalid AudioTrack minimum buffer: $minimumBytes")
            val capacityBytes = max(minimumBytes * 2, WireProtocol.MAX_PCM_PAYLOAD * 4)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(capacityBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("AudioTrack is not initialized")
            }
            activeTrack.set(track)
            configuredBufferFrames = track.bufferSizeInFrames
            val speakerId = requireBuiltInSpeaker(track)
            track.play()
            awaitPlaybackReady(track, speakerId)
            ready.countDown()

            val routeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var nextRouteCheck = 0L

            while (running.get()) {
                val chunk = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                var offset = 0
                var zeroWrites = 0
                while (offset < chunk.size && running.get()) {
                    val written = track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) throw IllegalStateException("AudioTrack write failed: $written")
                    if (written == 0) {
                        zeroWrites++
                        if (zeroWrites >= 5) throw IllegalStateException("AudioTrack made no write progress")
                    } else {
                        offset += written
                        zeroWrites = 0
                        val now = System.nanoTime()
                        if (now >= nextRouteCheck) {
                            verifyBuiltInSpeakerRoute(track, speakerId, routeDeadline, now)
                            nextRouteCheck = now + TimeUnit.MILLISECONDS.toNanos(500)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            startupError.compareAndSet(null, error)
            runtimeError.compareAndSet(null, error)
            ready.countDown()
        } finally {
            activeTrack.compareAndSet(track, null)
            try {
                track?.pause()
                track?.flush()
                track?.stop()
            } catch (_: IllegalStateException) {
                // Release remains mandatory even if the track changed state.
            }
            track?.release()
            ready.countDown()
        }
    }

    private fun requireBuiltInSpeaker(track: AudioTrack): Int {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: throw IllegalStateException("This Android device has no built-in speaker output")
        if (!track.setPreferredDevice(speaker)) {
            throw IllegalStateException("Android rejected the built-in speaker route")
        }
        return speaker.id
    }

    private fun awaitPlaybackReady(track: AudioTrack, speakerId: Int) {
        // READY means the receiver can actually consume audio, not merely that
        // AudioTrack.play() returned. A cold Android process can otherwise
        // stall its first writes during a screen-state transition and overflow
        // the bounded live-edge queue. Pace silent 10 ms periods until the
        // playback head advances and confirm the real speaker route before
        // allowing Windows capture to begin.
        val prime = ByteArray((format.sampleRate / 100) * bytesPerFrame())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var nextPrimeWrite = 0L
        while (running.get()) {
            val now = System.nanoTime()
            if (now >= nextPrimeWrite) {
                // setPreferredDevice may asynchronously recreate the native
                // track on a cold process. Keep supplying silence at its real
                // playback rate until the replacement advances; non-blocking
                // writes keep the startup deadline enforceable.
                val written = track.write(prime, 0, prime.size, AudioTrack.WRITE_NON_BLOCKING)
                if (written < 0) {
                    throw IllegalStateException("AudioTrack priming write failed: $written")
                }
                nextPrimeWrite = now + TimeUnit.MILLISECONDS.toNanos(10)
            }
            val routed = track.routedDevice
            if (routed != null &&
                (routed.id != speakerId || routed.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            ) {
                throw IllegalStateException(
                    "Android routed PC audio to ${deviceTypeName(routed.type)} instead of the phone speaker",
                )
            }
            if (routed?.id == speakerId &&
                routed.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
                (track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL) > 0L
            ) {
                return
            }
            if (now >= deadline) {
                if (routed == null) {
                    throw IllegalStateException("Android did not confirm the phone speaker route")
                }
                throw IllegalStateException("Android speaker playback did not begin")
            }
            Thread.sleep(5)
        }
        throw InterruptedException("Playback stopped during startup")
    }

    private fun verifyBuiltInSpeakerRoute(
        track: AudioTrack,
        speakerId: Int,
        deadlineNanos: Long,
        nowNanos: Long,
    ) {
        val routed = track.routedDevice
        if (routed?.id == speakerId && routed.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return
        if (routed != null) {
            throw IllegalStateException(
                "Android routed PC audio to ${deviceTypeName(routed.type)} instead of the phone speaker",
            )
        }
        if (nowNanos >= deadlineNanos) {
            throw IllegalStateException("Android did not confirm the phone speaker route")
        }
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        -> "Bluetooth audio"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        -> "wired audio"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> "USB audio"
        else -> "output type $type"
    }

    private fun bytesPerFrame(): Int = format.channels * (format.bitsPerSample / 8)

    override fun close() {
        if (!running.getAndSet(false)) return
        try {
            activeTrack.get()?.let { track ->
                track.pause()
                track.flush()
                track.stop()
            }
        } catch (_: IllegalStateException) {
            // Interrupt/join below still owns final release on the worker.
        }
        thread.interrupt()
        thread.join(3_000)
        queue.clear()
    }
}
