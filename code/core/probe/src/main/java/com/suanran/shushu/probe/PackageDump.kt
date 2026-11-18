// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import android.content.pm.PackageManager
import java.text.SimpleDateFormat
import java.util.Locale

internal class PackageDump(
    val firstInstall: Map<String, Long>,
    val userStates: Map<String, UserState>
) {
    data class UserState(val installed: Boolean, val enabled: Int, val suspended: Boolean?, val suspendedByShell: Boolean = false) {
        val disabledByUser get() = installed && enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    }

    companion object {
        private val userLine = Regex("User (\\d+): .*\\binstalled=(true|false)\\b.*")
        private val enabled = Regex("\\benabled=(\\d+)\\b")
        private val suspended = Regex("\\bsuspended=(true|false)\\b")
        private const val PROCESS_HEADER = "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)"
        private const val PER_USER_RANGE = 100_000
        private val processEntry = Regex("^\\*[A-Z]+\\* UID (\\d+) ProcessRecord\\{\\S+ (\\d+):")

        /** Activity Manager filters this dump by package membership, including custom process names. */
        fun processRunning(dump: String, user: Int): Boolean? {
            // Some builds print a timestamp before the Activity Manager header.
            val lines = dump.lineSequence().dropWhile { it.trim() != PROCESS_HEADER }.iterator()
            if (!lines.hasNext()) return null
            lines.next()
            var unreadable = false
            for (line in lines) {
                val entry = line.trimStart()
                if (!entry.startsWith('*') || "ProcessRecord{" !in entry) continue
                val match = processEntry.find(entry)
                val uid = match?.groupValues?.get(1)?.toIntOrNull()
                val pid = match?.groupValues?.get(2)?.toIntOrNull()
                if (uid == null || pid == null) unreadable = true
                else if (uid / PER_USER_RANGE == user && pid > 0) return true
            }
            return if (unreadable) null else false
        }

        /** Reads the current package entries, excluding the separate copies of updated system apps. */
        fun parse(dump: String, user: Int): PackageDump {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val times = HashMap<String, Long>()
            val states = HashMap<String, UserState>()
            var packages = false
            var pkg: String? = null
            var targetUser = false
            for (line in dump.lineSequence()) {
                val t = line.trim()
                if (t.isEmpty()) continue
                if (!line.first().isWhitespace()) {
                    packages = t == "Packages:"
                    pkg = null
                    targetUser = false
                }
                if (!packages) continue
                if (t.startsWith("Package [")) {
                    pkg = t.substringAfter('[').substringBefore(']')
                    targetUser = false
                    continue
                }
                val name = pkg ?: continue
                val userMatch = if (t.startsWith("User ")) userLine.matchEntire(t) else null
                when {
                    userMatch != null -> {
                        targetUser = userMatch.groupValues[1].toIntOrNull() == user
                        if (!targetUser) continue
                        val setting = enabled.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                        val suspension = suspended.find(t)?.groupValues?.get(1)?.toBooleanStrictOrNull()
                        states[name] = UserState(userMatch.groupValues[2] == "true", setting, suspension)
                    }
                    t.startsWith("firstInstallTime=") && name !in times ->
                        runCatching { format.parse(t.substringAfter('='))?.time }.getOrNull()?.let { times[name] = it }
                    targetUser && t.startsWith("suspendingPackage=") -> {
                        // Android prints either the package name or a user-qualified name like <0>com.android.shell.
                        val suspender = t.substringAfter('=').substringBefore(' ').substringAfter('>')
                        if (suspender == "com.android.shell") {
                            states[name]?.takeIf { it.suspended == true }?.let { states[name] = it.copy(suspendedByShell = true) }
                        }
                    }
                }
            }
            return PackageDump(times, states)
        }
    }
}
