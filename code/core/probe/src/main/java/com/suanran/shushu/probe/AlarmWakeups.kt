// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import org.json.JSONArray
import org.json.JSONObject

/** How often an app's alarms woke the phone, as the system's alarm service counts it. */
internal object AlarmWakeups {
    /**
     * The app's alarms that woke the phone since the device booted, from the "Alarm Stats:" part of
     * `dumpsys alarm` (AOSP), kept by the system for every app while it stays installed:
     *   Alarm Stats:
     *   u0a84:com.example +1s2ms running, 140 wakeups:
     *     +900ms 140 wakes 140 alarms, last -12m3s4ms:
     *       *walarm*:com.example/.SyncReceiver
     * JSON {"wakeups":n,"receivers":[{"tag","wakes","lastAgoMs"}]}, the busiest first; alarms that did
     * not wake the phone are left out. Null when the dump does not list the app.
     */
    fun parse(dump: String, pkg: String, user: Int): JSONObject? {
        val section = dump.substringAfter("\n  Alarm Stats:\n", missingDelimiterValue = "")
        var total: Long? = null
        val receivers = JSONArray()
        var wakes = 0L
        var lastAgo = 0L
        for (raw in section.lineSequence()) {
            if (raw.isBlank()) continue
            val line = DumpText.normalize(raw)
            val indent = raw.length - raw.trimStart().length
            if (indent <= 2) {
                // Each app's header; anything else at this level ends the list.
                val header = appHeader.find(line) ?: break
                if (total != null) break
                if (header.groupValues[2] == pkg && userOf(header.groupValues[1]) == user) total = header.groupValues[3].toLong()
                continue
            }
            if (total == null) continue
            val filter = filterLine.find(line)
            if (filter != null) {
                wakes = filter.groupValues[1].toLong()
                lastAgo = AppOpsHistory.millis(filter.groupValues[2])
                continue
            }
            if (wakes > 0) receivers.put(JSONObject().put("tag", line).put("wakes", wakes).put("lastAgoMs", lastAgo))
            wakes = 0
        }
        val found = total ?: return null
        val sorted = (0 until receivers.length()).map { receivers.getJSONObject(it) }.sortedByDescending { it.getLong("wakes") }
        return JSONObject().put("wakeups", found).put("receivers", JSONArray(sorted))
    }

    private val appHeader = Regex("^(\\S+):(\\S+) \\+\\S+ running, (\\d+) wakeups:$")
    private val filterLine = Regex("^\\+\\S+ (\\d+) wakes \\d+ alarms, last (-?\\S+):$")

    /** The user of a uid label: "u10a84" is user 10; plain system uids ("1000") are user 0. */
    private fun userOf(label: String): Int = DumpText.uid(label).let { if (it < 0) -1 else it / 100_000 }
}
