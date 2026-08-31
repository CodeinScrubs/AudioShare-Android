package com.audioshare.usbcompanion

/** Pure timing policy used by the Android playback watchdog. */
internal class PlaybackStallDetector(
    private val timeoutNanos: Long,
) {
    fun isStalled(
        nowNanos: Long,
        focusAvailable: Boolean,
        queuedFrames: Long,
        inFlightFrames: Long,
        lastWriteProgressNanos: Long,
        lastPlaybackAdvanceNanos: Long,
    ): Boolean {
        if (!focusAvailable) return false
        // Lifetime received/written counters intentionally diverge whenever
        // live-edge overflow or focus loss drops old audio. They therefore
        // cannot tell us whether work is pending now. Only queued frames and
        // the chunk currently owned by the AudioTrack writer can do that.
        if (queuedFrames <= 0L && inFlightFrames <= 0L) return false
        return nowNanos - lastWriteProgressNanos >= timeoutNanos &&
            nowNanos - lastPlaybackAdvanceNanos >= timeoutNanos
    }
}
