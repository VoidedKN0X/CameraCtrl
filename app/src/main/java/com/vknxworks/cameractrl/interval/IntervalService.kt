package com.vknxworks.cameractrl.interval

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.vknxworks.cameractrl.MainActivity
import com.vknxworks.cameractrl.R
import com.vknxworks.cameractrl.container

/**
 * Keeps an interval run going with the screen dimmed or off: a foreground service of the camera type, with a
 * notification (shown when notifications are allowed; it is always listed in the phone's active apps).
 * It does nothing but hold the run; the run itself is [IntervalRunner]. Not exported.
 */
class IntervalService : Service() {
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runner = container.interval
        if (intent?.action == ACTION_STOP) {
            runner.stop()
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } catch (_: Exception) {
            // Android refused a camera service (no camera permission, or not started from the app on screen).
            runner.failToStart()
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        runner.begin {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.interval_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.interval_channel_desc)
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, IntervalService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle(getString(R.string.interval_notif_title))
            .setContentText(getString(R.string.interval_notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.interval_stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "interval"
        private const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "com.vknxworks.cameractrl.action.STOP_INTERVAL"
    }
}
