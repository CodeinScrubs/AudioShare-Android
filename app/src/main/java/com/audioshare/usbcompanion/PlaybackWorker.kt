package com.audioshare.usbcompanion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.ArrayBlockingQueue
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
    private val queue = ArrayBlockingQueue<ByteArray>(8)
    private val ready = CountDownLatch(1)
    private val startupError = AtomicReference<Throwable?>()
    private val runtimeError = AtomicReference<Throwable?>()
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
        if (!queue.offer(payload.copyOf())) {
            val removed = queue.poll()
            if (removed != null) droppedFrames.addAndGet(removed.size.toLong() / bytesPerFrame())
            if (!queue.offer(payload.copyOf())) {
                droppedFrames.addAndGet(payload.size.toLong() / bytesPerFrame())
            }
        }
    }

    fun statsPayload(): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putLong(receivedFrames.get())
        buffer.putLong(droppedFrames.get())
        buffer.putInt(queue.size)
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
            configuredBufferFrames = track.bufferSizeInFrames
            preferBuiltInSpeaker(track)
            track.play()
            ready.countDown()

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
                    }
                }
            }
        } catch (error: Throwable) {
            startupError.compareAndSet(null, error)
            runtimeError.compareAndSet(null, error)
            ready.countDown()
        } finally {
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

    private fun preferBuiltInSpeaker(track: AudioTrack) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (speaker != null) track.preferredDevice = speaker
    }

    private fun bytesPerFrame(): Int = format.channels * (format.bitsPerSample / 8)

    override fun close() {
        if (!running.getAndSet(false)) return
        thread.interrupt()
        thread.join(3_000)
    }
}
