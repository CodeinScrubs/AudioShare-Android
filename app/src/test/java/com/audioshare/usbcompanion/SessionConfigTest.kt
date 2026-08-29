package com.audioshare.usbcompanion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionConfigTest {
    private val token = "01".repeat(32)

    @Test
    fun acceptsValidLaunchParameters() {
        val result = SessionConfig.parse("as_1_abcdefgh", token, 7L)!!

        assertEquals("as_1_abcdefgh", result.socketName)
        assertEquals(7L, result.generation)
        assertArrayEquals(ByteArray(32) { 1 }, result.token)
    }

    @Test
    fun rejectsPredictableOrMalformedInputs() {
        assertNull(config("audioshare", token, 1))
        assertNull(config("as_1_abcdefgh", "00".repeat(16), 1))
        assertNull(config("as_1_abcdefgh", token, -1))
    }

    private fun config(socket: String, token: String, generation: Long): SessionConfig? =
        SessionConfig.parse(socket, token, generation)
}
