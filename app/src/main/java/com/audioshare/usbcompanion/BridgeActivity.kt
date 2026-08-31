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
            // Preserve an explicit reason in Android's process/ADB diagnostics.
            // Some Android builds report the Activity failure to `am start -W`;
            // others only expose it through logcat, so the Windows host still
            // retains its bounded connection timeout as the final fallback.
            finishAndRemoveTask()
            throw IllegalStateException(
                "Android refused to start the playback service: " +
                    (error.message ?: error.javaClass.simpleName),
                error,
            )
        }
    }
}
