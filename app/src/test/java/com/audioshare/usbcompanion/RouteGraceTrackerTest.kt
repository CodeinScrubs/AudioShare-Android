package com.audioshare.usbcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteGraceTrackerTest {
    private val tracker = RouteGraceTracker(graceNanos = 2_000_000_000L)

    @Test
    fun transientMissingRouteGetsFreshGraceWindow() {
        assertEquals(
            RouteGraceTracker.Observation.GRACE,
            tracker.observe(10_000L, routedToSpeaker = false, routedToOtherDevice = false),
        )
        assertEquals(
            RouteGraceTracker.Observation.GRACE,
            tracker.observe(500_000_000L, routedToSpeaker = false, routedToOtherDevice = false),
        )
        assertEquals(
            RouteGraceTracker.Observation.VALID,
            tracker.observe(500_000_001L, routedToSpeaker = true, routedToOtherDevice = false),
        )
        assertEquals(
            RouteGraceTracker.Observation.GRACE,
            tracker.observe(3_000_000_000L, routedToSpeaker = false, routedToOtherDevice = false),
        )
    }

    @Test
    fun continuouslyMissingRouteExpires() {
        assertEquals(
            RouteGraceTracker.Observation.GRACE,
            tracker.observe(0L, routedToSpeaker = false, routedToOtherDevice = false),
        )
        assertEquals(
            RouteGraceTracker.Observation.EXPIRED,
            tracker.observe(2_000_000_000L, routedToSpeaker = false, routedToOtherDevice = false),
        )
    }

    @Test
    fun wrongRouteGetsTimeToRecoverBeforeItExpires() {
        assertEquals(
            RouteGraceTracker.Observation.GRACE,
            tracker.observe(1L, routedToSpeaker = false, routedToOtherDevice = true),
        )
        assertEquals(
            RouteGraceTracker.Observation.GRACE,
            tracker.observe(1_000_000_001L, routedToSpeaker = false, routedToOtherDevice = true),
        )
        assertEquals(
            RouteGraceTracker.Observation.EXPIRED,
            tracker.observe(2_000_000_001L, routedToSpeaker = false, routedToOtherDevice = true),
        )
        assertEquals(
            RouteGraceTracker.Observation.VALID,
            tracker.observe(2_000_000_002L, routedToSpeaker = true, routedToOtherDevice = false),
        )
        assertEquals(
            RouteGraceTracker.Observation.GRACE,
            tracker.observe(4_000_000_003L, routedToSpeaker = false, routedToOtherDevice = false),
        )
    }
}
