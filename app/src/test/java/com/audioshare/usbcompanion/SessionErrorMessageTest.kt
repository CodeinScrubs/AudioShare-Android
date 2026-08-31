package com.audioshare.usbcompanion

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionErrorMessageTest {
    @Test
    fun `nested playback cause remains visible to the Windows host`() {
        val error = IllegalStateException(
            "AudioTrack playback failed",
            IllegalStateException("Android audio focus was lost"),
        )

        assertEquals(
            "AudioTrack playback failed: Android audio focus was lost",
            sessionErrorMessage(error),
        )
    }

    @Test
    fun `duplicate nested messages are emitted once`() {
        val error = IllegalStateException(
            "Android audio focus was lost",
            IllegalStateException("Android audio focus was lost"),
        )

        assertEquals("Android audio focus was lost", sessionErrorMessage(error))
    }

    @Test
    fun `transport and protocol errors retain their stable wording`() {
        assertEquals(
            "Transport closed: socket reset",
            sessionErrorMessage(IOException("socket reset")),
        )
        assertEquals(
            "bad sequence",
            sessionErrorMessage(ProtocolException("bad sequence")),
        )
    }

    @Test
    fun `wire error remains bounded`() {
        val error = IllegalStateException("x".repeat(400))

        val message = sessionErrorMessage(error)

        assertEquals(240, message.length)
        assertTrue(message.all { it == 'x' })
    }
}
