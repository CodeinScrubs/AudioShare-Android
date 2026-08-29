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
        if (intent?.action == ACTION_LAUNCH) {
            val serviceIntent = Intent(this, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_START_SESSION
                putExtras(intent.extras ?: Bundle.EMPTY)
            }
            startForegroundService(serviceIntent)
        }
        finishAndRemoveTask()
    }
}
