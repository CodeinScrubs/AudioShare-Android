package com.audioshare.usbcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBufferPlanTest {
    @Test
    fun respectsPlatformMinimumWithoutMultiplyingIt() {
        val plan = planPlaybackBuffer(
            sampleRate = 48_000,
            bytesPerFrame = 4,
            minimumBytes = 17_440,
        )

        assertEquals(17_440, plan.capacityBytes)
        assertEquals(4_360, plan.effectiveBufferFrames)
        assertEquals(960, plan.startThresholdFrames)
    }

    @Test
    fun usesFortyMillisecondCapacityWhenPlatformMinimumIsSmaller() {
        val plan = planPlaybackBuffer(
            sampleRate = 48_000,
            bytesPerFrame = 4,
            minimumBytes = 1_024,
        )

        assertEquals(7_680, plan.capacityBytes)
        assertEquals(1_920, plan.effectiveBufferFrames)
        assertEquals(960, plan.startThresholdFrames)
    }

    @Test
    fun roundsUnalignedPlatformMinimumUpToWholeFrames() {
        val plan = planPlaybackBuffer(
            sampleRate = 44_100,
            bytesPerFrame = 4,
            minimumBytes = 7_681,
        )

        assertEquals(7_684, plan.capacityBytes)
        assertEquals(1_921, plan.effectiveBufferFrames)
        assertEquals(882, plan.startThresholdFrames)
    }
}
