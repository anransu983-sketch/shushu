// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Sent by the system once this app has been updated; picks up an open monitoring session again. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        (context.applicationContext as ShushuApp).controller.resumeAfterUpdate().invokeOnCompletion { pending.finish() }
    }
}
