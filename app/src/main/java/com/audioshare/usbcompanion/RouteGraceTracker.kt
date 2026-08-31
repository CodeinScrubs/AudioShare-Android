package com.audioshare.usbcompanion

/**
 * Keeps a short, renewable grace period for transiently missing route data.
 * Android can briefly report no routed device while rebuilding its audio
 * policy. A missing route is only fatal when it remains missing continuously.
 */
internal class RouteGraceTracker(
    private val graceNanos: Long,
) {
    private var missingSinceNanos: Long? = null

    fun observe(
        nowNanos: Long,
        routedToSpeaker: Boolean,
        routedToOtherDevice: Boolean,
    ): Observation {
        if (routedToSpeaker) {
            missingSinceNanos = null
            return Observation.VALID
        }
        if (routedToOtherDevice) {
            missingSinceNanos = null
            return Observation.WRONG_DEVICE
        }
        val since = missingSinceNanos ?: nowNanos.also { missingSinceNanos = it }
        return if (nowNanos - since >= graceNanos) {
            Observation.EXPIRED
        } else {
            Observation.GRACE
        }
    }

    enum class Observation {
        VALID,
        GRACE,
        EXPIRED,
        WRONG_DEVICE,
    }
}
