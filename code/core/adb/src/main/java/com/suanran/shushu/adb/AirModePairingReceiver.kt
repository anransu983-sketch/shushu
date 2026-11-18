// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Implemented by the Application so the notification input reaches the live pairing manager. */
interface AdbPairingHost {
    val pairingManager: AirModePairingManager
}

class AirModePairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AirModeNotificationController.ACTION_SUBMIT_CODE) return
        val host = context.applicationContext as? AdbPairingHost ?: return
        val results = RemoteInput.getResultsFromIntent(intent)
        host.pairingManager.requestPairingFromNotification(
            session = intent.data?.lastPathSegment,
            input = results?.getCharSequence(AirModeNotificationController.KEY_PAIRING_CODE)?.toString(),
            hasResults = results != null
        )
    }
}
