// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.suanran.shushu.R
import com.suanran.shushu.data.AppKey
import java.util.concurrent.ConcurrentHashMap

/** Display names and icons of the apps and system components in the record. */
class AppLabels(private val context: Context) {
    private val pm = context.packageManager
    private val labels = ConcurrentHashMap<AppKey, String>()
    private val icons = ConcurrentHashMap<String, ImageBitmap>()

    fun label(key: AppKey): String {
        labels[key]?.let { return it }
        // A caller that exited before it could be identified.
        if (key.uid < 0) return context.getString(R.string.unknown_caller)
        // Only an installed app's own name is kept; a fallback may change once the app is back.
        key.pkg?.let { pkg ->
            runCatching { pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(pm).toString() }
                .getOrNull()
        }?.let { labels[key] = it; return it }
        return key.pkg?.let(::processLabel)
            ?: if (key.uid == ROOT_UID) context.getString(R.string.root_service) else null
            ?: pm.getNameForUid(key.uid)?.let(::uidLabel)
            ?: "UID ${key.uid}"
    }

    /**
     * Native processes are named by their executable; hardware services follow the platform's
     * android.hardware.<name>-service naming, which is shortened to <name>.
     */
    private fun processLabel(process: String): String {
        if (!process.startsWith("/")) return process
        val name = process.substringAfterLast('/')
        if (!name.startsWith(HARDWARE_PREFIX)) return name
        val service = name.removePrefix(HARDWARE_PREFIX).substringBefore("-service").substringBefore('@')
        return context.getString(R.string.hardware_service, service)
    }

    /** Shared system uids are named like android.uid.<name>:<uid>. */
    private fun uidLabel(name: String): String = name.substringBefore(':').removePrefix("android.uid.")

    private companion object {
        const val ROOT_UID = 0
        const val HARDWARE_PREFIX = "android.hardware."
    }

    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** [pkg]'s icon when it was read already, or is known to have none; never reads the phone. */
    fun cachedIcon(pkg: String?): ImageBitmap? = pkg?.let { icons[it] }

    /** Whether [pkg]'s icon still has to be read; the read is slow, so it is done off the main thread. */
    fun iconPending(pkg: String?): Boolean = pkg != null && !icons.containsKey(pkg) && pkg !in missing

    fun icon(pkg: String?): ImageBitmap? {
        if (pkg == null) return null
        icons[pkg]?.let { return it }
        if (pkg in missing) return null
        // An app removed since it was recorded still has its icon in the package manager's records.
        val icon = runCatching {
            val info = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            pm.getApplicationIcon(info).toBitmap(iconPx, iconPx).asImageBitmap()
        }.getOrNull()
        if (icon == null) missing += pkg else icons[pkg] = icon
        return icon
    }

    private val iconPx = (52 * context.resources.displayMetrics.density).toInt().coerceIn(96, 192)
}
