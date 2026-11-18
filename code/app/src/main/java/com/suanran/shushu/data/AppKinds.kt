// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Who stands behind a record: an app the user installed, an app that came with the phone, an app
 * that runs under a system identity (Settings, Phone, many of the maker's components share uid 1000
 * or another platform uid), or the system itself (system_server, native daemons, hardware services,
 * and the adb shell, which is the user at a computer). Preinstalled and system apps often hold
 * privileges that keep their accesses out of the system record.
 */
enum class AppKind(val id: String) {
    THIRD_PARTY("third_party"), PREINSTALLED("preinstalled"), SYSTEM_APP("system_app"), SYSTEM("system")
}

class AppKinds(private val pm: PackageManager) {
    private val cache = ConcurrentHashMap<AppKey, AppKind>()

    fun kindOf(key: AppKey): AppKind = cache.getOrPut(key) {
        val info = key.pkg?.takeIf { it !in PLATFORM }
            ?.let { runCatching { pm.getApplicationInfo(it, PackageManager.MATCH_UNINSTALLED_PACKAGES) }.getOrNull() }
            ?: return@getOrPut AppKind.SYSTEM
        if (key.uid < FIRST_APP_UID) AppKind.SYSTEM_APP
        else if (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) AppKind.PREINSTALLED
        else AppKind.THIRD_PARTY
    }

    private val persistent = ConcurrentHashMap<String, Boolean>()

    /** Whether [pkg] is flagged persistent: kept running by the system as part of it. */
    fun persistent(pkg: String?): Boolean = pkg != null && persistent.getOrPut(pkg) {
        runCatching { pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_PERSISTENT != 0 }.getOrDefault(false)
    }

    private companion object {
        const val FIRST_APP_UID = 10_000
        /** The platform itself and the adb shell: packages, but not apps anyone uses. */
        val PLATFORM = setOf("android", "com.android.shell")
    }
}
