package com.audioshare.usbcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStallDetectorTest {
    private val detector = PlaybackStallDetector(timeoutNanos = 2_000_000_000L)

    @Test
    fun doesNotReportStallWhenThereIsNoPendingAudio() {
        assertFalse(
            detector.isStalled(
                nowNanos = 3_000_000_000L,
                focusAvailable = true,
                receivedFrames = 100,
                writtenFrames = 100,
                queuedFrames = 0,
                lastWriteProgressNanos = 0,
                lastPlaybackAdvanceNanos = 0,
            ),
        )
    }

    @Test
    fun reportsPendingAudioOnlyAfterBothProgressSignalsStop() {
        assertFalse(
            detector.isStalled(
                nowNanos = 3_000_000_000L,
                focusAvailable = true,
                receivedFrames = 100,
                writtenFrames = 50,
                queuedFrames = 10,
                lastWriteProgressNanos = 2_000_000_001L,
                lastPlaybackAdvanceNanos = 0,
            ),
        )
        assertTrue(
            detector.isStalled(
                nowNanos = 3_000_000_000L,
                focusAvailable = true,
                receivedFrames = 100,
                writtenFrames = 50,
                queuedFrames = 10,
                lastWriteProgressNanos = 0,
                lastPlaybackAdvanceNanos = 0,
            ),
        )
    }

    @Test
    fun focusLossSuppressesStallUntilPlaybackCanResume() {
        assertFalse(
            detector.isStalled(
                nowNanos = 3_000_000_000L,
                focusAvailable = false,
                receivedFrames = 100,
                writtenFrames = 50,
                queuedFrames = 10,
                lastWriteProgressNanos = 0,
                lastPlaybackAdvanceNanos = 0,
            ),
        )
    }
}
