package com.audioshare.usbcompanion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PlaybackWorker(
    private val context: Context,
    private val format: WireProtocol.Hello,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val focusAvailable = AtomicBoolean(false)
    // The socket reader and AudioTrack writer are deliberately decoupled, but
    // stalled audio is never allowed to become permanent lag. The duration-
    // based queue retains at most a 40 ms live edge (or one indivisible input
    // chunk) and accounts every older complete frame it drops.
    private val queue = LiveEdgePcmQueue(bytesPerFrame(), format.sampleRate)
    private val ready = CountDownLatch(1)
    private val startupError = AtomicReference<Throwable?>()
    private val runtimeError = AtomicReference<Throwable?>()
    private val activeTrack = AtomicReference<AudioTrack?>()
    private val droppedFrames = AtomicLong(0)
    private val receivedFrames = AtomicLong(0)
    private val focusState = AtomicInteger(FOCUS_NONE)
    private val thread = Thread(::runPlayback, "AudioShare-Playback")
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(::onAudioFocusChange)
        .build()

    @Volatile
    var configuredBufferFrames: Int = 0
        private set

    @Volatile
    private var bufferCapacityFrames: Int = 0

    @Volatile
    private var startThresholdFrames: Int = 0

    @Volatile
    private var routedDeviceType: Int = 0

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
        val payloadFrames = payload.size.toLong() / bytesPerFrame()
        receivedFrames.addAndGet(payloadFrames)
        if (!focusAvailable.get()) {
            droppedFrames.addAndGet(payloadFrames)
            return
        }
        droppedFrames.addAndGet(queue.offerOwned(payload))
        // Close the race in which focus is lost after the first check but
        // before the queue lock is acquired. No pre-interruption audio may be
        // replayed after focus returns.
        if (!focusAvailable.get()) {
            droppedFrames.addAndGet(queue.discardAll())
        }
    }

    fun statsPayload(): ByteArray {
        // The playback thread can fail independently of the socket reader
        // (for example when Android revokes the speaker route). Surface that
        // failure on the next heartbeat even when no PCM frame is arriving;
        // otherwise the host could keep reporting a healthy session with no
        // audible output.
        runtimeError.get()?.let {
            throw IllegalStateException("AudioTrack playback failed", it)
        }
        val snapshot = queue.snapshot()
        val track = activeTrack.get()
        val underruns = try {
            track?.underrunCount ?: 0
        } catch (_: IllegalStateException) {
            0
        }
        return WireProtocol.encodePlaybackStats(
            WireProtocol.PlaybackStats(
                receivedFrames = receivedFrames.get(),
                droppedFrames = droppedFrames.get(),
                queueDepth = snapshot.chunks,
                bufferFrames = configuredBufferFrames,
                queueFrames = snapshot.frames.saturatedInt(),
                bufferCapacityFrames = bufferCapacityFrames,
                startThresholdFrames = startThresholdFrames,
                underrunCount = underruns,
                routedDeviceType = routedDeviceType,
                focusState = focusState.get(),
                mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                mediaVolumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                queueHighWaterFrames = snapshot.highWaterFrames.saturatedInt(),
            ),
        )
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
            val bufferPlan = planPlaybackBuffer(
                sampleRate = format.sampleRate,
                bytesPerFrame = bytesPerFrame(),
                minimumBytes = minimumBytes,
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferPlan.capacityBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("AudioTrack is not initialized")
            }
            activeTrack.set(track)
            bufferCapacityFrames = track.bufferCapacityInFrames
            val effectiveBufferFrames = track.setBufferSizeInFrames(
                minOf(bufferPlan.effectiveBufferFrames, bufferCapacityFrames),
            )
            if (effectiveBufferFrames <= 0) {
                throw IllegalStateException(
                    "Android rejected AudioTrack buffer size: $effectiveBufferFrames",
                )
            }
            configuredBufferFrames = track.bufferSizeInFrames
            startThresholdFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val threshold = track.setStartThresholdInFrames(
                    minOf(bufferPlan.startThresholdFrames, configuredBufferFrames),
                )
                if (threshold <= 0) {
                    throw IllegalStateException(
                        "Android rejected AudioTrack start threshold: $threshold",
                    )
                }
                track.startThresholdInFrames
            } else {
                configuredBufferFrames
            }
            val speakerId = requireBuiltInSpeaker(track)
            requestAudioFocus()
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
                    if (!focusAvailable.get()) {
                        droppedFrames.addAndGet(
                            (chunk.size - offset).toLong() / bytesPerFrame(),
                        )
                        break
                    }
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
            focusAvailable.set(false)
            audioManager.abandonAudioFocusRequest(focusRequest)
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
        val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: throw IllegalStateException("This Android device has no built-in speaker output")
        if (!track.setPreferredDevice(speaker)) {
            throw IllegalStateException("Android rejected the built-in speaker route")
        }
        return speaker.id
    }

    private fun requestAudioFocus() {
        val result = audioManager.requestAudioFocus(focusRequest)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw IllegalStateException("Android denied audio focus")
        }
        focusState.set(FOCUS_GAINED)
        focusAvailable.set(true)
    }

    private fun onAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusState.set(FOCUS_GAINED)
                activeTrack.get()?.let { track ->
                    try {
                        track.setVolume(1.0f)
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        focusAvailable.set(true)
                    } catch (error: IllegalStateException) {
                        runtimeError.compareAndSet(null, error)
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusState.set(FOCUS_DUCKED)
                focusAvailable.set(true)
                try {
                    activeTrack.get()?.setVolume(DUCK_VOLUME)
                } catch (error: IllegalStateException) {
                    runtimeError.compareAndSet(null, error)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusState.set(FOCUS_TRANSIENT_LOSS)
                suspendForFocusLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusState.set(FOCUS_PERMANENT_LOSS)
                suspendForFocusLoss()
                runtimeError.compareAndSet(
                    null,
                    IllegalStateException("Android audio focus was lost"),
                )
            }
        }
    }

    private fun suspendForFocusLoss() {
        focusAvailable.set(false)
        droppedFrames.addAndGet(queue.discardAll())
        try {
            activeTrack.get()?.let { track ->
                track.pause()
                track.flush()
            }
        } catch (error: IllegalStateException) {
            runtimeError.compareAndSet(null, error)
        }
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
            routedDeviceType = routed?.type ?: 0
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
        routedDeviceType = routed?.type ?: 0
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
        droppedFrames.addAndGet(queue.discardAll())
    }

    private fun Long.saturatedInt(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    companion object {
        const val FOCUS_NONE = 0
        const val FOCUS_GAINED = 1
        const val FOCUS_DUCKED = 2
        const val FOCUS_TRANSIENT_LOSS = 3
        const val FOCUS_PERMANENT_LOSS = 4
        private const val DUCK_VOLUME = 0.2f
    }
}
