// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-side handle to the monitor. It only receives the binder the monitor offers. */
class ProbeClient(private val context: Context) {
    private val token = Binder()
    private val _service = MutableStateFlow<IProbeService?>(null)
    val service: StateFlow<IProbeService?> = _service.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val binder = validate(intent) ?: return
            if (_service.value?.asBinder() === binder) return
            val service = IProbeService.Stub.asInterface(binder)
            runCatching {
                check(service.protocolVersion() == ProbeProtocol.VERSION)
                service.attach(token)
                binder.linkToDeath({ if (_service.value?.asBinder() === binder) _service.value = null }, 0)
            }.onSuccess {
                _service.value = service
            }.onFailure {
                Log.w(TAG, "monitor binder rejected: ${it.javaClass.simpleName}")
            }
        }
    }

    /** Registered once per process; the sender must hold DUMP, which the shell user has. */
    fun register() {
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ProbeProtocol.ACTION_READY),
            ProbeProtocol.SENDER_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun startCommand(): String = ProbeLaunch.startCommand(context.applicationInfo.sourceDir, context.packageName)

    private fun validate(intent: Intent): IBinder? = try {
        val extras = intent.extras
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        when {
            intent.action != ProbeProtocol.ACTION_READY || extras == null -> null
            extras.getInt(ProbeProtocol.EXTRA_UID, -1) != ProbeProtocol.SHELL_UID -> null
            extras.getInt(ProbeProtocol.EXTRA_PROTOCOL, -1) != ProbeProtocol.VERSION -> null
            extras.getString(ProbeProtocol.EXTRA_SOURCE) != context.applicationInfo.sourceDir -> null
            extras.getLong(ProbeProtocol.EXTRA_UPDATED_AT, -1L) != info.lastUpdateTime -> null
            else -> extras.getBinder(ProbeProtocol.EXTRA_BINDER)?.takeIf { it.isBinderAlive }
        }
    } catch (error: RuntimeException) {
        Log.w(TAG, "ignored invalid monitor offer: ${error.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "JiumiProbe"
    }
}
