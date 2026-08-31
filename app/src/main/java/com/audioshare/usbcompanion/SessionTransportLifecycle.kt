package com.audioshare.usbcompanion

/**
 * Keeps the accept watchdog from tearing down a client that has already been
 * accepted. The timeout only needs to close the listening socket; a connected
 * LocalSocket is owned by the session and is closed during normal shutdown.
 */
internal class SessionTransportLifecycle(
    private val closeClient: () -> Unit,
    private val closeServer: () -> Unit,
) {
    fun closeAcceptWait() = closeServer()

    /**
     * Called only after SessionRunner has published the accepted client. If a
     * concurrent close already stopped the session, close that just-accepted
     * socket before the runner can enter its authentication read.
     */
    fun continueAfterAccept(isRunning: Boolean): Boolean {
        if (isRunning) return true
        closeClient()
        return false
    }

    fun closeAll() {
        closeClient()
        closeServer()
    }
}
