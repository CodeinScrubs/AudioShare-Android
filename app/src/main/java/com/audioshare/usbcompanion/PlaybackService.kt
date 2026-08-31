package com.audioshare.usbcompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class PlaybackService : Service(), SessionRunner.Listener {
    companion object {
        private const val CHANNEL_ID = "pc_audio_stream"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_SESSION = "com.audioshare.usbcompanion.START_SESSION"
        const val ACTION_STOP_SESSION = "com.audioshare.usbcompanion.STOP_SESSION"
    }

    private val lock = Any()
    private val sessionExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "AudioShare-SessionLifecycle")
    }
    private var runner: SessionRunner? = null
    @Volatile private var destroyed = false
    @Volatile private var state = "Preparing"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground("Preparing receiver")
        when (intent?.action) {
            ACTION_STOP_SESSION -> stopSession()
            ACTION_START_SESSION -> {
                val config = SessionConfig.fromIntent(intent)
                if (config == null) {
                    stopWithError("Invalid session parameters")
                } else {
                    startSession(config)
                }
            }
            else -> stopWithError("Unsupported service action")
        }
        return START_NOT_STICKY
    }

    private fun startSession(config: SessionConfig) {
        val replacement = SessionRunner(applicationContext, config, this)
        val previous = synchronized(lock) {
            if (destroyed) return
            val old = runner
            runner = replacement
            old
        }
        // Publish the replacement before stopping the old runner. Its final
        // callback is then rejected by object identity even when a restarted
        // host reuses the same small generation number.
        enqueueLifecycleTask {
            previous?.close()
            val stillCurrent = synchronized(lock) {
                !destroyed && runner === replacement
            }
            if (!stillCurrent) {
                replacement.close()
            } else {
                replacement.start()
            }
        }
    }

    private fun stopSession() {
        val previous = synchronized(lock) {
            val old = runner
            runner = null
            old
        }
        enqueueLifecycleTask { previous?.close() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onState(source: SessionRunner, state: String) {
        synchronized(lock) {
            if (runner !== source) return
        }
        this.state = state
        startInForeground(state)
    }

    override fun onStopped(source: SessionRunner, error: String?) {
        synchronized(lock) {
            if (runner !== source) return
            runner = null
        }
        stopWithError(error)
    }

    private fun stopWithError(error: String?) {
        this.state = error ?: "Disconnected"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val previous = synchronized(lock) {
            destroyed = true
            val old = runner
            runner = null
            old
        }
        enqueueLifecycleTask { previous?.close() }
        sessionExecutor.shutdown()
        super.onDestroy()
    }

    private fun enqueueLifecycleTask(task: () -> Unit) {
        try {
            sessionExecutor.execute(task)
        } catch (_: RejectedExecutionException) {
            // Android can deliver a final stop callback after onDestroy has
            // begun. Closing synchronously is safer than leaking a session.
            task()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PC audio playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown only while receiving or preparing PC audio"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_STOP_SESSION
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PC Audio")
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stopPending).build())
            .build()
    }
}
