package com.audioshare.usbcompanion

/**
 * Keeps a short, renewable grace period for transiently invalid route data.
 * Android can briefly report no route or an old route while rebuilding its
 * audio policy. Either condition is fatal only when it remains continuous.
 */
internal class RouteGraceTracker(
    private val graceNanos: Long,
) {
    private var invalidSinceNanos: Long? = null

    fun observe(
        nowNanos: Long,
        routedToSpeaker: Boolean,
        routedToOtherDevice: Boolean,
    ): Observation {
        require(!(routedToSpeaker && routedToOtherDevice))
        if (routedToSpeaker) {
            invalidSinceNanos = null
            return Observation.VALID
        }
        // Missing and explicitly wrong routes receive the same bounded
        // recovery window; the separate booleans reject ambiguous callers.
        val since = invalidSinceNanos ?: nowNanos.also { invalidSinceNanos = it }
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
    }
}
