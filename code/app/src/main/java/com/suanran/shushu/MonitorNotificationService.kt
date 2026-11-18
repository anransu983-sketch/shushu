// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * The persistent notification while a monitoring session runs: how long it has run, a tap to open
 * the record and an action to stop. The monitor itself runs outside the app; this only shows it.
 */
class MonitorNotificationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as ShushuApp).controller.stopMonitor()
            stop()
            return START_NOT_STICKY
        }
        val since = intent?.getLongExtra(EXTRA_SINCE, 0L)?.takeIf { it > 0 } ?: System.currentTimeMillis()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(since), type)
        return START_NOT_STICKY
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(since: Long): Notification {
        // A service does not get the in-app language on Android 11 and 12, so ask for it.
        val text = ContextCompat.getContextForLanguage(this)
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(text.getString(R.string.notification_channel_monitoring))
                .setShowBadge(false)
                .build()
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MonitorNotificationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_monitoring)
            .setContentTitle(text.getString(R.string.notification_monitoring))
            .setWhen(since)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, text.getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "monitoring"
        private const val NOTIFICATION_ID = 2001
        private const val EXTRA_SINCE = "since"
        private const val ACTION_STOP = "com.suanran.shushu.STOP_MONITORING"

        /** Shows the notification for a session that began at [since]; refused while the app is in the background. */
        fun show(context: Context, since: Long): Boolean = runCatching {
            context.startForegroundService(Intent(context, MonitorNotificationService::class.java).putExtra(EXTRA_SINCE, since))
        }.isSuccess

        fun hide(context: Context) {
            context.stopService(Intent(context, MonitorNotificationService::class.java))
        }
    }
}
