package com.audioshare.usbcompanion

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class BridgeActivity : Activity() {
    companion object {
        const val ACTION_LAUNCH = "com.audioshare.usbcompanion.LAUNCH_SESSION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != ACTION_LAUNCH) {
            finishAndRemoveTask()
            return
        }
        startReceiverAndFinish()
    }

    private fun startReceiverAndFinish() {
        val serviceIntent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_START_SESSION
            putExtra(
                SessionConfig.EXTRA_SOCKET_NAME,
                intent.getStringExtra(SessionConfig.EXTRA_SOCKET_NAME),
            )
            putExtra(
                SessionConfig.EXTRA_TOKEN_HEX,
                intent.getStringExtra(SessionConfig.EXTRA_TOKEN_HEX),
            )
            putExtra(
                SessionConfig.EXTRA_GENERATION,
                intent.getLongExtra(SessionConfig.EXTRA_GENERATION, -1L),
            )
        }
        try {
            startForegroundService(serviceIntent)
            finishAndRemoveTask()
        } catch (error: RuntimeException) {
            // Keep the ADB `am start -W` result explicitly failed and include a
            // useful reason instead of letting the host wait for a handshake
            // timeout when Android rejects foreground-service startup.
            finishAndRemoveTask()
            throw IllegalStateException(
                "Android refused to start the playback service: " +
                    (error.message ?: error.javaClass.simpleName),
                error,
            )
        }
    }
}
