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

class PlaybackService : Service(), SessionRunner.Listener {
    companion object {
        private const val CHANNEL_ID = "pc_audio_stream"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_SESSION = "com.audioshare.usbcompanion.START_SESSION"
        const val ACTION_STOP_SESSION = "com.audioshare.usbcompanion.STOP_SESSION"
    }

    private val lock = Any()
    private var runner: SessionRunner? = null
    private var currentGeneration = -1L
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
        val previous = synchronized(lock) {
            val old = runner
            runner = null
            currentGeneration = config.generation
            old
        }
        previous?.close()
        val replacement = SessionRunner(applicationContext, config, this)
        synchronized(lock) {
            if (currentGeneration != config.generation) {
                replacement.close()
                return
            }
            runner = replacement
        }
        replacement.start()
    }

    private fun stopSession() {
        val previous = synchronized(lock) {
            val old = runner
            runner = null
            currentGeneration = -1L
            old
        }
        previous?.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onState(generation: Long, state: String) {
        synchronized(lock) {
            if (generation != currentGeneration) return
        }
        this.state = state
        startInForeground(state)
    }

    override fun onStopped(generation: Long, error: String?) {
        synchronized(lock) {
            if (generation != currentGeneration) return
            runner = null
            currentGeneration = -1L
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
            val old = runner
            runner = null
            currentGeneration = -1L
            old
        }
        previous?.close()
        super.onDestroy()
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
