package com.audioshare.usbcompanion

/** Pure timing policy used by the Android playback watchdog. */
internal class PlaybackStallDetector(
    private val timeoutNanos: Long,
) {
    fun isStalled(
        nowNanos: Long,
        focusAvailable: Boolean,
        receivedFrames: Long,
        writtenFrames: Long,
        queuedFrames: Long,
        lastWriteProgressNanos: Long,
        lastPlaybackAdvanceNanos: Long,
    ): Boolean {
        if (!focusAvailable) return false
        if (receivedFrames <= writtenFrames && queuedFrames <= 0L) return false
        return nowNanos - lastWriteProgressNanos >= timeoutNanos &&
            nowNanos - lastPlaybackAdvanceNanos >= timeoutNanos
    }
}
