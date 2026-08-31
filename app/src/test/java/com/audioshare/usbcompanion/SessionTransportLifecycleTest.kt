package com.audioshare.usbcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTransportLifecycleTest {
    @Test
    fun `accept timeout closes only the listening server`() {
        var clientCloses = 0
        var serverCloses = 0
        val lifecycle = SessionTransportLifecycle(
            closeClient = { clientCloses++ },
            closeServer = { serverCloses++ },
        )

        lifecycle.closeAcceptWait()

        assertEquals(0, clientCloses)
        assertEquals(1, serverCloses)
    }

    @Test
    fun `session shutdown closes both client and server`() {
        var clientCloses = 0
        var serverCloses = 0
        val lifecycle = SessionTransportLifecycle(
            closeClient = { clientCloses++ },
            closeServer = { serverCloses++ },
        )

        lifecycle.closeAll()

        assertEquals(1, clientCloses)
        assertEquals(1, serverCloses)
    }

    @Test
    fun `client accepted after concurrent shutdown is closed immediately`() {
        var clientCloses = 0
        val lifecycle = SessionTransportLifecycle(
            closeClient = { clientCloses++ },
            closeServer = {},
        )

        val shouldAuthenticate = lifecycle.continueAfterAccept(isRunning = false)

        assertEquals(false, shouldAuthenticate)
        assertEquals(1, clientCloses)
    }
}
