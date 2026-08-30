package com.audioshare.usbcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class LiveEdgePcmQueueTest {
    @Test
    fun retainsOnlyNewestEightyMillisecondsAfterWriterStall() {
        val framesPerChunk = 480 // 10 ms at 48 kHz
        val queue = LiveEdgePcmQueue(bytesPerFrame = 4, sampleRate = 48_000)
        var discardedFrames = 0L

        repeat(20) { marker ->
            val payload = ByteArray(framesPerChunk * 4) { marker.toByte() }
            discardedFrames += queue.offer(payload)
        }

        val snapshot = queue.snapshot()
        assertEquals(8, snapshot.chunks)
        assertEquals(3_840L, snapshot.frames)
        assertEquals(12L * framesPerChunk, discardedFrames)
        repeat(8) { index ->
            val payload = queue.poll(0, TimeUnit.MILLISECONDS)!!
            assertEquals(index + 12, payload[0].toInt())
        }
    }

    @Test
    fun maximumHostChunksCannotAccumulateHundredsOfMilliseconds() {
        val queue = LiveEdgePcmQueue(bytesPerFrame = 4, sampleRate = 48_000)
        var discardedFrames = 0L

        repeat(32) {
            discardedFrames += queue.offer(ByteArray(WireProtocol.MAX_PCM_PAYLOAD) { it.toByte() })
        }

        val snapshot = queue.snapshot()
        val queuedMillis = snapshot.frames * 1_000L / 48_000L
        assertEquals(1, snapshot.chunks)
        assertTrue("queue retained $queuedMillis ms", queuedMillis <= 80L)
        assertTrue(discardedFrames > 0L)
    }

    @Test
    fun oneIndivisibleMonoChunkIsRetainedAtLowLiveEdge() {
        val queue = LiveEdgePcmQueue(bytesPerFrame = 2, sampleRate = 48_000)

        repeat(4) {
            queue.offer(ByteArray(WireProtocol.MAX_PCM_PAYLOAD))
        }

        val snapshot = queue.snapshot()
        val queuedMillis = snapshot.frames * 1_000L / 48_000L
        assertEquals(1, snapshot.chunks)
        assertTrue("queue retained $queuedMillis ms", queuedMillis <= 86L)
    }
}
