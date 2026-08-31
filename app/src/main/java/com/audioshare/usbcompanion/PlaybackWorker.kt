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
    private val terminated = CountDownLatch(1)
    private val startupError = AtomicReference<Throwable?>()
    private val runtimeError = AtomicReference<Throwable?>()
    private val activeTrack = AtomicReference<AudioTrack?>()
    private val droppedFrames = AtomicLong(0)
    private val receivedFrames = AtomicLong(0)
    private val writtenFrames = AtomicLong(0)
    private val inFlightFrames = AtomicLong(0)
    private val playbackHeadFrames = AtomicLong(0)
    private val lastWriteProgressNanos = AtomicLong(0)
    private val lastPlaybackAdvanceNanos = AtomicLong(0)
    private val focusState = AtomicInteger(FOCUS_NONE)
    private val thread = Thread(::runPlayback, "AudioShare-Playback")
    private var watchdogThread: Thread? = null
    private val stallDetector = PlaybackStallDetector(PLAYBACK_STALL_TIMEOUT_NANOS)
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

    @Volatile
    private var configuredPerformanceMode: Int = AudioTrack.PERFORMANCE_MODE_NONE

    init {
        thread.start()
        try {
            if (!ready.await(5, TimeUnit.SECONDS)) {
                close()
                throw IllegalStateException("AudioTrack initialization timed out")
            }
        } catch (error: InterruptedException) {
            // Construction can be interrupted by SessionRunner.close() before
            // the new worker is assigned to the runner's local `playback`
            // variable. Own cleanup here so that thread, track, and audio focus
            // cannot escape the interrupted constructor.
            close()
            Thread.currentThread().interrupt()
            throw error
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
        val nowNanos = System.nanoTime()
        val playState = try {
            track?.playState ?: AudioTrack.PLAYSTATE_STOPPED
        } catch (_: IllegalStateException) {
            AudioTrack.PLAYSTATE_STOPPED
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
                writtenFrames = writtenFrames.get(),
                playbackHeadFrames = playbackHeadFrames.get(),
                lastWriteProgressAgeMillis = progressAgeMillis(
                    nowNanos,
                    lastWriteProgressNanos.get(),
                ),
                lastPlaybackAdvanceAgeMillis = progressAgeMillis(
                    nowNanos,
                    lastPlaybackAdvanceNanos.get(),
                ),
                playState = playState,
                performanceMode = configuredPerformanceMode,
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
            val configuredTrack = buildConfiguredAudioTrack(channelMask, bufferPlan)
            track = configuredTrack.track
            activeTrack.set(track)
            bufferCapacityFrames = configuredTrack.bufferCapacityFrames
            configuredBufferFrames = configuredTrack.configuredBufferFrames
            startThresholdFrames = configuredTrack.startThresholdFrames
            configuredPerformanceMode = configuredTrack.performanceMode
            val speaker = configuredTrack.speaker
            requestAudioFocus()
            track.play()
            awaitPlaybackReady(track, speaker)
            ready.countDown()
            startPlaybackWatchdog(track)

            var nextRouteCheck = 0L
            while (running.get()) {
                val now = System.nanoTime()
                if (now >= nextRouteCheck) {
                    verifyBuiltInSpeakerRoute(track, speaker, now)
                    nextRouteCheck = now + TimeUnit.MILLISECONDS.toNanos(500)
                }
                val chunk = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                inFlightFrames.set(chunk.size.toLong() / bytesPerFrame())
                try {
                    var offset = 0
                    var zeroWrites = 0
                    while (offset < chunk.size && running.get()) {
                        if (!focusAvailable.get()) break
                        val written = track.write(
                            chunk,
                            offset,
                            chunk.size - offset,
                            AudioTrack.WRITE_BLOCKING,
                        )
                        if (written < 0) throw IllegalStateException("AudioTrack write failed: $written")
                        if (written == 0) {
                            zeroWrites++
                            if (zeroWrites >= 5) {
                                throw IllegalStateException("AudioTrack made no write progress")
                            }
                        } else {
                            if (written % bytesPerFrame() != 0) {
                                throw IllegalStateException("AudioTrack returned a partial PCM frame")
                            }
                            offset += written
                            val framesWritten = written.toLong() / bytesPerFrame()
                            writtenFrames.addAndGet(framesWritten)
                            inFlightFrames.addAndGet(-framesWritten)
                            lastWriteProgressNanos.set(System.nanoTime())
                            zeroWrites = 0
                        }
                    }
                } finally {
                    // Any part of the claimed chunk not written because focus
                    // disappeared, shutdown began, or AudioTrack failed is a
                    // deliberate drop. Clear it so historical loss cannot
                    // poison the future stall decision.
                    droppedFrames.addAndGet(inFlightFrames.getAndSet(0))
                }
            }
        } catch (error: Throwable) {
            startupError.compareAndSet(null, error)
            runtimeError.compareAndSet(null, error)
            ready.countDown()
        } finally {
            running.set(false)
            try {
                focusAvailable.set(false)
                try {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                } catch (_: RuntimeException) {
                    // Cleanup must continue on vendor AudioManager failures.
                }
                try {
                    track?.pause()
                    track?.flush()
                    track?.stop()
                } catch (_: RuntimeException) {
                    // Release remains mandatory even if the track changed
                    // state or a vendor implementation rejects teardown.
                }
                try {
                    track?.release()
                } catch (_: RuntimeException) {
                    // Do not crash the service while reporting playback
                    // failure. A close-side rescue may already have released
                    // the same track to unblock a stalled write.
                }
            } finally {
                activeTrack.compareAndSet(track, null)
                ready.countDown()
                terminated.countDown()
            }
        }
    }

    private data class ConfiguredAudioTrack(
        val track: AudioTrack,
        val speaker: AudioDeviceInfo,
        val bufferCapacityFrames: Int,
        val configuredBufferFrames: Int,
        val startThresholdFrames: Int,
        val performanceMode: Int,
    )

    private fun buildConfiguredAudioTrack(
        channelMask: Int,
        bufferPlan: PlaybackBufferPlan,
    ): ConfiguredAudioTrack {
        fun create(performanceMode: Int): AudioTrack = AudioTrack.Builder()
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
            .setPerformanceMode(performanceMode)
            .build()

        fun configure(performanceMode: Int, label: String): ConfiguredAudioTrack {
            var candidate: AudioTrack? = null
            try {
                candidate = create(performanceMode)
                if (candidate.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("$label AudioTrack is not initialized")
                }
                val capacityFrames = candidate.bufferCapacityInFrames
                val effectiveBufferFrames = candidate.setBufferSizeInFrames(
                    minOf(bufferPlan.effectiveBufferFrames, capacityFrames),
                )
                if (effectiveBufferFrames <= 0) {
                    throw IllegalStateException(
                        "$label AudioTrack rejected buffer size: $effectiveBufferFrames",
                    )
                }
                val configuredFrames = candidate.bufferSizeInFrames
                val thresholdFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val threshold = candidate.setStartThresholdInFrames(
                        minOf(bufferPlan.startThresholdFrames, configuredFrames),
                    )
                    if (threshold <= 0) {
                        throw IllegalStateException(
                            "$label AudioTrack rejected start threshold: $threshold",
                        )
                    }
                    candidate.startThresholdInFrames
                } else {
                    configuredFrames
                }
                val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?: throw IllegalStateException("This Android device has no built-in speaker output")
                if (!candidate.setPreferredDevice(speaker)) {
                    throw IllegalStateException("$label AudioTrack rejected the built-in speaker route")
                }
                return ConfiguredAudioTrack(
                    track = candidate,
                    speaker = speaker,
                    bufferCapacityFrames = capacityFrames,
                    configuredBufferFrames = configuredFrames,
                    startThresholdFrames = thresholdFrames,
                    performanceMode = candidate.performanceMode,
                )
            } catch (error: RuntimeException) {
                try {
                    candidate?.release()
                } catch (_: RuntimeException) {
                    // The next compatibility attempt must remain available
                    // even if a vendor rejects release of a bad track.
                }
                throw error
            }
        }

        try {
            return configure(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY, "Low-latency")
        } catch (error: RuntimeException) {
            try {
                // Treat construction, buffer tuning, start threshold, and
                // initial route selection as one atomic configuration. OEMs
                // sometimes accept the low-latency builder but reject one of
                // the later operations; the ordinary performance mode is the
                // compatibility fallback for that entire sequence.
                return configure(AudioTrack.PERFORMANCE_MODE_NONE, "Compatibility")
            } catch (fallbackError: RuntimeException) {
                fallbackError.addSuppressed(error)
                throw fallbackError
            }
        }
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

    private fun awaitPlaybackReady(track: AudioTrack, speaker: AudioDeviceInfo) {
        // READY means the receiver can actually consume audio, not merely that
        // AudioTrack.play() returned. A cold Android process can otherwise
        // stall its first writes during a screen-state transition and overflow
        // the bounded live-edge queue. Pace silent 10 ms periods until the
        // playback head advances and confirm the real speaker route before
        // allowing Windows capture to begin.
        val prime = ByteArray((format.sampleRate / 100) * bytesPerFrame())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var nextPrimeWrite = 0L
        var nextRouteRequest = 0L
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
            val routedToSpeaker = routed?.id == speaker.id &&
                routed.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            if (!routedToSpeaker && now >= nextRouteRequest) {
                // Some OEM policies briefly expose a stale Bluetooth/wired
                // route while switching. Reassert the requested speaker for
                // the same bounded interval already used for a missing route.
                track.setPreferredDevice(speaker)
                nextRouteRequest = now + TimeUnit.MILLISECONDS.toNanos(100)
            }
            if (routedToSpeaker &&
                (track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL) > 0L
            ) {
                return
            }
            if (now >= deadline) {
                if (routed == null) {
                    throw IllegalStateException("Android did not confirm the phone speaker route")
                }
                if (!routedToSpeaker) {
                    throw IllegalStateException(
                        "Android routed PC audio to ${deviceTypeName(routed.type)} instead of the phone speaker",
                    )
                }
                throw IllegalStateException("Android speaker playback did not begin")
            }
            Thread.sleep(5)
        }
        throw InterruptedException("Playback stopped during startup")
    }

    private fun verifyBuiltInSpeakerRoute(
        track: AudioTrack,
        speaker: AudioDeviceInfo,
        nowNanos: Long,
    ) {
        val routed = track.routedDevice
        routedDeviceType = routed?.type ?: 0
        val observation = routeGraceTracker.observe(
            nowNanos = nowNanos,
            routedToSpeaker = routed?.id == speaker.id &&
                routed.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            routedToOtherDevice = routed != null &&
                (routed.id != speaker.id || routed.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        )
        if (observation == RouteGraceTracker.Observation.VALID) return
        // Retrying is harmless and shortens recovery when Android reports a
        // transiently stale route after Bluetooth or a headset disconnects.
        track.setPreferredDevice(speaker)
        if (observation == RouteGraceTracker.Observation.EXPIRED) {
            if (routed != null) {
                throw IllegalStateException(
                    "Android routed PC audio to ${deviceTypeName(routed.type)} instead of the phone speaker",
                )
            }
            throw IllegalStateException("Android did not confirm the phone speaker route")
        }
    }

    private fun startPlaybackWatchdog(track: AudioTrack) {
        val now = System.nanoTime()
        lastWriteProgressNanos.set(now)
        lastPlaybackAdvanceNanos.set(now)
        watchdogThread = Thread({ runPlaybackWatchdog(track) }, "AudioShare-PlaybackWatchdog")
            .apply {
                isDaemon = true
                start()
            }
    }

    private fun runPlaybackWatchdog(track: AudioTrack) {
        var previousRawHead: Long? = null
        var cumulativeHead = 0L
        while (running.get()) {
            val now = System.nanoTime()
            val head = try {
                track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            } catch (_: RuntimeException) {
                0L
            }
            val previous = previousRawHead
            if (previous == null) {
                cumulativeHead = head
            } else if (head >= previous) {
                cumulativeHead += head - previous
            } else if (previous >= 0xF000_0000L && head <= 0x0FFF_FFFFL) {
                // AudioTrack exposes a wrapping unsigned 32-bit playback head.
                cumulativeHead += (1L shl 32) - previous + head
            }
            // A backward jump away from the wrap boundary is a pause/flush
            // reset. Preserve the cumulative total and use the new baseline.
            playbackHeadFrames.set(cumulativeHead)
            if (previous == null || previous != head) {
                lastPlaybackAdvanceNanos.set(now)
                previousRawHead = head
            }
            val queuedFrames = queue.snapshot().frames
            if (stallDetector.isStalled(
                    nowNanos = now,
                    focusAvailable = focusAvailable.get(),
                    queuedFrames = queuedFrames,
                    inFlightFrames = inFlightFrames.get(),
                    lastWriteProgressNanos = lastWriteProgressNanos.get(),
                    lastPlaybackAdvanceNanos = lastPlaybackAdvanceNanos.get(),
                )
            ) {
                val error = IllegalStateException(
                    "Android speaker playback stopped consuming PC audio",
                )
                runtimeError.compareAndSet(null, error)
                running.set(false)
                try {
                    track.release()
                } catch (_: RuntimeException) {
                    // The playback thread still performs bounded cleanup.
                }
                thread.interrupt()
                return
            }
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private val routeGraceTracker = RouteGraceTracker(ROUTE_GRACE_NANOS)

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
        closeAndAwait()
    }

    fun closeAndAwait(): Boolean {
        running.set(false)
        watchdogThread?.interrupt()
        thread.interrupt()
        try {
            if (!terminated.await(3, TimeUnit.SECONDS)) {
                // A vendor AudioTrack can occasionally ignore interruption
                // while a blocking write is in progress. Never call vendor
                // teardown synchronously from the service/session thread: a
                // broken OEM implementation must not hold the session wake
                // lock forever. A daemon rescue attempts the documented
                // release escape hatch while this caller waits only two more
                // seconds.
                Thread(
                    {
                        try {
                            activeTrack.get()?.release()
                        } catch (_: RuntimeException) {
                            // The bounded caller reports failure below if the
                            // playback thread still does not terminate.
                        }
                    },
                    "AudioShare-PlaybackRescue",
                ).apply {
                    isDaemon = true
                    start()
                }
                if (!terminated.await(2, TimeUnit.SECONDS)) {
                    runtimeError.compareAndSet(
                        null,
                        IllegalStateException("AudioTrack worker did not stop within 5 seconds"),
                    )
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            runtimeError.compareAndSet(
                null,
                IllegalStateException("AudioTrack worker shutdown was interrupted"),
            )
        }
        try {
            watchdogThread?.join(1_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        droppedFrames.addAndGet(queue.discardAll())
        return terminated.count == 0L
    }

    /**
     * Used only after bounded shutdown failed. The owning SessionRunner must
     * not announce full termination (and allow a replacement AudioTrack) while
     * a vendor-blocked writer is still alive.
     */
    fun awaitTerminationUninterruptibly() {
        var interrupted = false
        while (terminated.count != 0L) {
            try {
                terminated.await()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun Long.saturatedInt(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun progressAgeMillis(nowNanos: Long, progressNanos: Long): Int {
        if (progressNanos <= 0L || nowNanos <= progressNanos) return 0
        return TimeUnit.NANOSECONDS.toMillis(nowNanos - progressNanos)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    companion object {
        const val FOCUS_NONE = 0
        const val FOCUS_GAINED = 1
        const val FOCUS_DUCKED = 2
        const val FOCUS_TRANSIENT_LOSS = 3
        const val FOCUS_PERMANENT_LOSS = 4
        private const val DUCK_VOLUME = 0.2f
        private val ROUTE_GRACE_NANOS = TimeUnit.SECONDS.toNanos(2)
        private val PLAYBACK_STALL_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2)
    }
}
