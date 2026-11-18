// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Parses the system's long-term access record from `dumpsys appops --history`. AppOps keeps, for
 * every operation, how often each app used it and was refused, per process state, in time slots that
 * grow coarser with age (a quarter of an hour now, days or weeks long ago). It covers every caller,
 * the maker's components running as the system included, and time before monitoring began, but keeps
 * no time for each access. (The API behind it answers only the system; the dump answers the shell.)
 *
 * Layout:
 *   snapshot: / begin = 2026-11-14 20:26:30.666  (-28m50s614ms) / end = ...
 *   Uid u0a94: / Package com.android.vending: / Attribution null: / GET_USAGE_STATS: /
 *   [bg-s] = access=3, reject=1688, duration=+1s653ms
 */
internal object AppOpsHistory {
    private val stamp = Regex("^(begin|end) = (\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d(?:\\.\\d{1,3})?)")
    private val counts = Regex("^\\[([a-z]+)-([a-z]+)\\] = (.*)$")
    private val duration = Regex("(\\d+)(d|h|ms|m|s)")

    /** Process states in which the app is not in front of the user, as the report reads them. */
    private val UNATTENDED = setOf("fgsvc", "bg", "cch", "gone")

    private class Totals { var fg = 0L; var bg = 0L; var fgRejected = 0L; var bgRejected = 0L; var durationMs = 0L }
    private class Slot(val from: Long, val to: Long, val ops: LinkedHashMap<Triple<Int, String, String>, Totals>)

    /**
     * Slots that ended after [since] and began before [now], newest first, of the operations in [ops]
     * ("android:camera" form) and of every maker's own operation ("vendor:MIUIOP(10036)"), with counts
     * per app and operation. A maker may also name its operations like the platform's; [isMakerOp]
     * tells those apart, and they are reported as the maker's. Only the apps' own accesses count:
     * one a provider made for another app (a "d" flag, proxied) is that app's.
     * JSON array of {"from","to","ops":[{"uid","pkg","op","fg","bg","fgRejected","bgRejected","durationMs"}]}.
     */
    fun parse(dump: String, ops: Set<String>, since: Long, now: Long, isMakerOp: (String) -> Boolean = { false }): JSONArray {
        val makerOps = HashMap<String, String?>()
        fun named(op: String?): String? = when {
            op == null || op in ops || op.startsWith(DumpText.VENDOR_PREFIX) -> op
            else -> makerOps.getOrPut(op) {
                if (isMakerOp(op)) DumpText.VENDOR_PREFIX + op.removePrefix("android:").uppercase(Locale.US) else null
            }
        }
        val section = dump.substringAfter("History:", missingDelimiterValue = "")
        val slots = ArrayList<Slot>()
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val formatSeconds = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        var begin = -1L
        var current: Slot? = null
        var uid = -1
        var pkg: String? = null
        var op: String? = null
        for (raw in section.lineSequence()) {
            val line = DumpText.normalize(raw)
            when {
                line.isEmpty() || line.startsWith("mode=") -> Unit
                line == "snapshot:" -> { current = null; begin = -1L; uid = -1; pkg = null; op = null }
                stamp.find(line) != null -> {
                    val m = stamp.find(line)!!
                    val text = m.groupValues[2]
                    val t = (if ('.' in text) format else formatSeconds).parse(text)?.time ?: continue
                    if (m.groupValues[1] == "begin") begin = t
                    // Slots wholly outside the window, or with times the clock got wrong, are skipped.
                    else if (begin in 0..now && t > since && t >= begin) current = Slot(begin, t, LinkedHashMap()).also { slots += it }
                }
                line.startsWith("Uid ") -> { uid = DumpText.uid(line.removePrefix("Uid ")); pkg = null; op = null }
                line.startsWith("Package ") -> { pkg = line.removePrefix("Package ").removeSuffix(":"); op = null }
                line.startsWith("Attribution ") -> op = null
                line.startsWith("[") -> {
                    val slot = current ?: continue
                    val m = counts.find(line) ?: continue
                    val name = named(op) ?: continue
                    val owner = pkg ?: continue
                    if (uid < 0 || m.groupValues[2].endsWith("d")) continue
                    val totals = slot.ops.getOrPut(Triple(uid, owner, name)) { Totals() }
                    val background = DumpText.state(m.groupValues[1]) in UNATTENDED
                    for (part in m.groupValues[3].split(", ")) {
                        val key = part.substringBefore('=')
                        val value = part.substringAfter('=')
                        when (key) {
                            "access" -> value.toLongOrNull()?.let { if (background) totals.bg += it else totals.fg += it }
                            "reject" -> value.toLongOrNull()?.let { if (background) totals.bgRejected += it else totals.fgRejected += it }
                            "duration" -> totals.durationMs += millis(value)
                        }
                    }
                }
                // Any other line is an operation header; one that names no operation still ends the
                // one before, so its counts are never added to that one's.
                else -> op = DumpText.operation(line)
            }
        }
        val out = JSONArray()
        for (slot in slots.sortedByDescending { it.from }) {
            val list = JSONArray()
            for ((k, v) in slot.ops) {
                if (v.fg + v.bg + v.fgRejected + v.bgRejected == 0L) continue
                list.put(JSONObject().put("uid", k.first).put("pkg", k.second).put("op", k.third)
                    .put("fg", v.fg).put("bg", v.bg).put("fgRejected", v.fgRejected).put("bgRejected", v.bgRejected)
                    .put("durationMs", v.durationMs))
            }
            out.put(JSONObject().put("from", slot.from).put("to", slot.to).put("ops", list))
        }
        return out
    }

    /** "+1h2m3s456ms" as milliseconds. */
    internal fun millis(text: String): Long = duration.findAll(text).sumOf { m ->
        val n = m.groupValues[1].toLong()
        when (m.groupValues[2]) { "d" -> n * 86_400_000; "h" -> n * 3_600_000; "m" -> n * 60_000; "s" -> n * 1_000; else -> n }
    }
}
