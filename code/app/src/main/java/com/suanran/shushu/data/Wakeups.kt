// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

import org.json.JSONObject

/**
 * How often an app's alarms woke the phone since it started [sinceBootMs] ago, as the system's alarm
 * service counts it: [total] in all, and per alarm in [receivers], the busiest first.
 */
data class Wakeups(val sinceBootMs: Long, val total: Long, val receivers: List<Receiver>) {
    /** [name] is the alarm's tag, shortened to its receiver's class where it names one. */
    data class Receiver(val name: String, val wakes: Long, val lastAt: Long)

    companion object {
        /** The monitor's alarmWakeups answer for [pkg]; null when it is not one or the app has no alarms. */
        fun parse(json: String, pkg: String, now: Long): Wakeups? = runCatching {
            val o = JSONObject(json)
            if (!o.has("wakeups")) return null
            val receivers = o.optJSONArray("receivers")?.let { a ->
                (0 until a.length()).map { i ->
                    val r = a.getJSONObject(i)
                    Receiver(alarmName(r.getString("tag"), pkg), r.getLong("wakes"), now - r.getLong("lastAgoMs"))
                }
            }.orEmpty()
            Wakeups(o.getLong("sinceBootMs"), o.getLong("wakeups"), receivers)
        }.getOrNull()

        /**
         * "*walarm*:com.example/.control.SyncReceiver" as "SyncReceiver": the class an alarm starts,
         * when its tag names one of the app's components; any other tag as the system wrote it.
         */
        fun alarmName(tag: String, pkg: String): String {
            val bare = tag.removePrefix("*walarm*:").removePrefix("*alarm*:")
            if (!bare.startsWith("$pkg/")) return bare
            return bare.substringAfter('/').substringAfterLast('.').ifEmpty { bare }
        }
    }
}
