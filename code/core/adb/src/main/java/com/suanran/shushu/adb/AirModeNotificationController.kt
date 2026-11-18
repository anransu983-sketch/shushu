// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.adb

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.annotation.StringRes
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import java.util.UUID

enum class AirPairingNotificationAvailability {
    AVAILABLE,
    PERMISSION_REQUIRED,
    APP_NOTIFICATIONS_DISABLED,
    CHANNEL_DISABLED,
    POST_FAILED
}

data class PairingNotificationPostResult(
    val posted: Boolean,
    val availability: AirPairingNotificationAvailability,
    val detail: String = ""
)

class AirModeNotificationController(
    private val context: Context,
    private val log: PairingLog
) {
    companion object {
        const val CHANNEL_ID = "jiumi_adb_pairing"
        const val NOTIFICATION_ID = 7301
        const val ACTION_SUBMIT_CODE = "com.suanran.shushu.adb.SUBMIT_PAIRING_CODE"
        const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_PAIRING_SESSION = "com.suanran.shushu.adb.pairing_session"
        private const val KEY_PAIRING_BOOT = "com.suanran.shushu.adb.pairing_boot"
    }

    @Volatile private var pairingNotificationActive = false

    private val notificationManager = NotificationManagerCompat.from(context)
    private val localizedContext get() = ContextCompat.getContextForLanguage(context)

    private fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManager.IMPORTANCE_HIGH
        ).setName(localizedContext.getString(R.string.settings_air_notification_channel)).build()
        notificationManager.createNotificationChannel(channel)
    }

    fun refreshLanguage() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) ensureChannel()
        LocalizedNotifications.refresh(context, setOf(NOTIFICATION_ID)) { builder, notification ->
            if (notification.actions?.isNotEmpty() == true) {
                val session = notification.extras.getString(KEY_PAIRING_SESSION)
                builder.clearActions()
                if (session != null) {
                    builder.addAction(pairingCodeAction(session))
                }
            }
        }
    }

    /** Android retains the live input notification across app process recreation. */
    fun currentPairingSession(): String? = runCatching {
        val notification = notificationManager.activeNotifications
            .firstOrNull { it.id == NOTIFICATION_ID && it.tag == null }?.notification
            ?: return@runCatching null
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot < 0 || notification.extras.getInt(KEY_PAIRING_BOOT, -2) != boot ||
            notification.actions.isNullOrEmpty()) return@runCatching null
        notification.extras.getString(KEY_PAIRING_SESSION)
    }.getOrNull()

    fun availability(): AirPairingNotificationAvailability {
        if (runCatching { ensureChannel() }.isFailure) {
            return AirPairingNotificationAvailability.POST_FAILED
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return AirPairingNotificationAvailability.PERMISSION_REQUIRED
        }
        if (!notificationManager.areNotificationsEnabled()) {
            return AirPairingNotificationAvailability.APP_NOTIFICATIONS_DISABLED
        }
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID)
        if (channel == null) {
            return AirPairingNotificationAvailability.POST_FAILED
        }
        if (channel.importance < NotificationManager.IMPORTANCE_HIGH) {
            return AirPairingNotificationAvailability.CHANNEL_DISABLED
        }
        return AirPairingNotificationAvailability.AVAILABLE
    }

    private fun builder(@StringRes title: Int, @StringRes text: Int): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .apply { LocalizedNotifications.setText(this, context, title, text) }
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)

    private fun showStatus(@StringRes title: Int, @StringRes text: Int, ongoing: Boolean, indeterminate: Boolean = false, timeoutMs: Long = 0L) {
        ensureChannel()
        val notification = builder(title, text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setProgress(0, 0, indeterminate)
            .setTimeoutAfter(timeoutMs)
            .build()
        post("status", notification)
    }

    fun showPairingRequest(
        failure: AirPairingFailure? = null,
        alert: Boolean = true
    ): PairingNotificationPostResult {
        val availability = availability()
        if (availability != AirPairingNotificationAvailability.AVAILABLE) {
            logPairingEvent(
                "pairing notification blocked: availability=${availability.name.lowercase()}"
            )
            return PairingNotificationPostResult(false, availability)
        }
        pairingNotificationActive = true
        val session = currentPairingSession() ?: UUID.randomUUID().toString()
        val text = failure?.messageRes() ?: R.string.settings_air_notification_text
        val title = if (failure == null) R.string.settings_air_notification_title
            else R.string.settings_air_notification_failed_title
        val notification = builder(title, text)
            .setSilent(!alert)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(if (alert) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .addExtras(android.os.Bundle().apply {
                putString(KEY_PAIRING_SESSION, session)
                putInt(KEY_PAIRING_BOOT, Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1))
            })
            .addAction(pairingCodeAction(session))
            .build()

        // Clear the silent/previous input before alerting so heads-up presentation triggers reliably.
        if (alert || failure != null) notificationManager.cancel(NOTIFICATION_ID)
        return post(if (alert) "pairing_request" else "pairing_input_ready", notification).fold(
            onSuccess = {
                PairingNotificationPostResult(
                    posted = true,
                    availability = AirPairingNotificationAvailability.AVAILABLE
                )
            },
            onFailure = { error ->
                PairingNotificationPostResult(
                    posted = false,
                    availability = AirPairingNotificationAvailability.POST_FAILED,
                    detail = error.message.orEmpty().take(160)
                )
            }
        )
    }

    private fun pairingCodeAction(session: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
            .setLabel(localizedContext.getString(R.string.settings_air_notification_input_label))
            .build()
        val submitIntent = Intent(context, AirModePairingReceiver::class.java).apply {
            action = ACTION_SUBMIT_CODE
            data = Uri.parse("jiumi://adb-pairing/$session")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, submitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return NotificationCompat.Action.Builder(
            0, localizedContext.getString(R.string.settings_air_notification_action), pendingIntent
        ).addRemoteInput(remoteInput).build()
    }

    private fun post(kind: String, notification: android.app.Notification): Result<Unit> =
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
            .onSuccess {
                logPairingEvent(
                    "pairing notification sent: kind=$kind notifications_enabled=${notificationManager.areNotificationsEnabled()}"
                )
            }
            .onFailure { error ->
                logPairingEvent(
                    "pairing notification failed: kind=$kind ${error::class.java.simpleName}: " +
                        error.message.orEmpty().take(160)
                )
            }

    private fun logPairingEvent(event: String) = log.event(event)

    fun showPairingSubmitted() {
        pairingNotificationActive = true
        showStatus(
            R.string.settings_air_notification_submitted_title,
            R.string.settings_air_notification_submitted_text,
            ongoing = false,
            indeterminate = true
        )
    }

    fun showPairingWaitingForService() {
        pairingNotificationActive = true
        showStatus(
            R.string.settings_air_notification_submitted_title,
            R.string.settings_air_notification_waiting_text,
            ongoing = false,
            indeterminate = true
        )
    }

    fun showPairingSucceeded() {
        pairingNotificationActive = false
        showStatus(
            R.string.settings_air_notification_succeeded_title,
            R.string.settings_air_notification_succeeded_text,
            ongoing = false,
            indeterminate = false
        )
    }

    fun showInvalidPairingCode() {
        showPairingFailure(AirPairingFailure.INVALID_PAIRING_CODE, allowRetry = true)
    }

    fun showPairingUnavailable() {
        showPairingFailure(AirPairingFailure.PAIRING_REQUEST_UNAVAILABLE)
    }

    fun showPairingFailure(failure: AirPairingFailure, allowRetry: Boolean = false) {
        if (allowRetry) {
            // A RemoteInput submission changes the notification's system state. Replace it
            // with a fresh ongoing input notification, rather than updating the submitted one.
            showPairingRequest(failure)
            return
        }
        pairingNotificationActive = false
        ensureChannel()
        val notification = builder(
            R.string.settings_air_notification_failed_title,
            failure.messageRes()
        ).setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0, 0, false)
        post("pairing_failure", notification.build())
    }

    fun showConnectionFailure(failure: AirPairingFailure) {
        if (!pairingNotificationActive) return
        showStatus(
            R.string.settings_air_notification_connection_failed_title,
            failure.messageRes(),
            ongoing = false
        )
    }

    fun showPairingRequired() {
        ensureChannel()
        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        val contentIntent = PendingIntent.getActivity(
            context,
            1,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = builder(
            R.string.settings_air_notification_pairing_required_title,
            R.string.settings_air_notification_pairing_required_text
        )
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        post("pairing_required", notification)
    }

    fun cancel() {
        pairingNotificationActive = false
        notificationManager.cancel(NOTIFICATION_ID)
        logPairingEvent("pairing notification canceled: id=$NOTIFICATION_ID")
    }
}
