// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process

/**
 * Context for the app_process monitor. Framework managers capture the context they come from and
 * use its package for permission checks, so every manager is re-pointed at the shell package.
 * These are Android platform identities, not app-specific rules.
 */
internal class ShellContext private constructor(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = SHELL_PACKAGE
    override fun getOpPackageName(): String = SHELL_PACKAGE
    override fun getApplicationContext(): Context = this

    @SuppressLint("NewApi")
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(ProbeProtocol.SHELL_UID).setPackageName(SHELL_PACKAGE).build()

    override fun getSystemService(name: String): Any? {
        val service = super.getSystemService(name) ?: return null
        var type: Class<*>? = service.javaClass
        while (type != null) {
            try {
                type.getDeclaredField("mContext").apply { isAccessible = true }.set(service, this)
                return service
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            } catch (_: Throwable) {
                return service
            }
        }
        return service
    }

    companion object {
        private const val SHELL_PACKAGE = "com.android.shell"

        fun create(): Context {
            check(Process.myUid() == ProbeProtocol.SHELL_UID) { "unsupported uid ${Process.myUid()}" }
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getDeclaredMethod("systemMain").invoke(null)
            val system = activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            val base = runCatching {
                system.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            }.getOrDefault(system)
            return ShellContext(base)
        }

        val supportsAttributionSource: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}
