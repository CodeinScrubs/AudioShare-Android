package com.audioshare.usbcompanion

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded PCM queue that deliberately favors current audio over stale audio.
 *
 * AudioTrack can stop accepting writes briefly while Android changes power or
 * routing state. Buffering an entire stall would leave playback permanently
 * behind because the producer and consumer subsequently run at the same rate.
 * This queue therefore retains only a small, duration-based live edge and
 * accounts every complete frame it discards.
 */
internal class LiveEdgePcmQueue(
    private val bytesPerFrame: Int,
    sampleRate: Int,
    maximumBufferedMillis: Int = DEFAULT_MAXIMUM_BUFFERED_MILLIS,
    private val maximumChunks: Int = HARD_MAXIMUM_CHUNKS,
) {
    data class Snapshot(
        val chunks: Int,
        val frames: Long,
    )

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()
    private val maximumBufferedFrames: Long
    private var queuedFrames = 0L

    init {
        require(bytesPerFrame > 0)
        require(sampleRate > 0)
        require(maximumBufferedMillis > 0)
        require(maximumChunks > 0)
        maximumBufferedFrames = maxOf(
            1L,
            sampleRate.toLong() * maximumBufferedMillis / 1_000L,
        )
    }

    /** Returns the number of complete frames discarded while accepting [payload]. */
    fun offer(payload: ByteArray): Long {
        require(payload.isNotEmpty() && payload.size % bytesPerFrame == 0)
        val copy = payload.copyOf()
        val incomingFrames = copy.size.toLong() / bytesPerFrame
        return lock.withLock {
            var discardedFrames = 0L
            while (
                chunks.isNotEmpty() &&
                (chunks.size >= maximumChunks || queuedFrames + incomingFrames > maximumBufferedFrames)
            ) {
                val discarded = chunks.removeFirst()
                val frames = discarded.size.toLong() / bytesPerFrame
                queuedFrames -= frames
                discardedFrames += frames
            }
            chunks.addLast(copy)
            queuedFrames += incomingFrames
            notEmpty.signal()
            discardedFrames
        }
    }

    @Throws(InterruptedException::class)
    fun poll(timeout: Long, unit: TimeUnit): ByteArray? {
        var remaining = unit.toNanos(timeout)
        return lock.withLock {
            while (chunks.isEmpty()) {
                if (remaining <= 0L) return@withLock null
                remaining = notEmpty.awaitNanos(remaining)
            }
            val chunk = chunks.removeFirst()
            queuedFrames -= chunk.size.toLong() / bytesPerFrame
            chunk
        }
    }

    fun snapshot(): Snapshot = lock.withLock {
        Snapshot(chunks = chunks.size, frames = queuedFrames)
    }

    fun clear() = lock.withLock {
        chunks.clear()
        queuedFrames = 0L
    }

    companion object {
        const val DEFAULT_MAXIMUM_BUFFERED_MILLIS = 80
        private const val HARD_MAXIMUM_CHUNKS = 32
    }
}
