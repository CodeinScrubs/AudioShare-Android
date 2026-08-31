package com.audioshare.usbcompanion

/**
 * Converts the latency policy into frame-aligned AudioTrack settings.
 *
 * The platform minimum remains the lower bound for the streaming buffer. On
 * API 31+ the start threshold is intentionally smaller than the capacity, so
 * startup does not require filling the whole platform buffer with silence.
 */
internal data class PlaybackBufferPlan(
    val capacityBytes: Int,
    val effectiveBufferFrames: Int,
    val startThresholdFrames: Int,
)

internal fun planPlaybackBuffer(
    sampleRate: Int,
    bytesPerFrame: Int,
    minimumBytes: Int,
    targetBufferMillis: Int = TARGET_BUFFER_MILLIS,
    targetStartThresholdMillis: Int = TARGET_START_THRESHOLD_MILLIS,
): PlaybackBufferPlan {
    require(sampleRate > 0)
    require(bytesPerFrame > 0)
    require(minimumBytes > 0)
    require(targetBufferMillis > 0)
    require(targetStartThresholdMillis > 0)

    val minimumFrames = (minimumBytes + bytesPerFrame - 1) / bytesPerFrame
    val targetFrames = maxOf(1, sampleRate * targetBufferMillis / 1_000)
    val effectiveFrames = maxOf(minimumFrames, targetFrames)
    val startFrames = maxOf(
        1,
        minOf(effectiveFrames, sampleRate * targetStartThresholdMillis / 1_000),
    )
    return PlaybackBufferPlan(
        capacityBytes = Math.multiplyExact(effectiveFrames, bytesPerFrame),
        effectiveBufferFrames = effectiveFrames,
        startThresholdFrames = startFrames,
    )
}

internal const val TARGET_BUFFER_MILLIS = 40
internal const val TARGET_START_THRESHOLD_MILLIS = 20
