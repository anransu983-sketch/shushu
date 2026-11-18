// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Parses the per-access history from `dumpsys appops --include-discrete 0`. This is the record
 * behind the system Privacy Dashboard; it survives restarts of the monitor and of the phone.
 *
 * Layout (indentation varies by release; Android 16 adds a "Device:" level; makers add operations
 * of their own, which are read as such and never taken for the operation above them):
 *   Uid: 10094 / Package: com.example / CAMERA / Attribution: null /
 *   Access [top-s] at 2026-11-08 15:49:29.506 for 60000 milliseconds
 */
internal object DiscreteHistory {
    private val access = Regex("^Access \\[([a-z]+)-([a-z]+)\\] at (\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d(?:\\.\\d{1,3})?)(?: for (\\d+) milliseconds)?")

    fun parse(dump: String, ops: Set<String>, fromTime: Long, max: Int): JSONArray {
        val section = dump.substringAfter("Discrete accesses:", missingDelimiterValue = "")
        val found = ArrayList<JSONObject>()
        var uid = -1
        var pkg: String? = null
        var op: String? = null
        var attribution: String? = null
        val withMillis = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val withoutMillis = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (raw in section.lineSequence()) {
            val line = DumpText.normalize(raw)
            when {
                line.isEmpty() || line.startsWith("Largest chain id") || line.startsWith("Device:") -> Unit
                line.startsWith("Uid: ") -> { uid = DumpText.uid(line.removePrefix("Uid: ")); pkg = null; op = null }
                line.startsWith("Package: ") -> { pkg = line.removePrefix("Package: "); op = null }
                line.startsWith("Attribution: ") -> attribution = line.removePrefix("Attribution: ").takeUnless { it == "null" }
                line.startsWith("Access ") || line.startsWith("Reject ") -> {
                    val match = access.find(line) ?: continue
                    val name = op ?: continue
                    if (name !in ops || uid < 0) continue
                    val stamp = match.groupValues[3]
                    val t = (if ('.' in stamp) withMillis else withoutMillis).parse(stamp)?.time ?: continue
                    if (t < fromTime) continue
                    val duration = match.groupValues[4].toLongOrNull() ?: 0L
                    found += JSONObject()
                        .put("kind", "history")
                        .put("t", t)
                        .put("end", t + duration)
                        .put("op", name)
                        .put("uid", uid)
                        .put("pkg", pkg)
                        .put("attr", attribution)
                        .put("state", DumpText.state(match.groupValues[1]))
                        .put("via", match.groupValues[2])
                }
                // Any other line is an operation header: one that names no known operation still
                // ends the one before, so its accesses are never counted as that one's.
                else -> { op = DumpText.operation(line); attribution = null }
            }
        }
        // An access can be listed twice, with and without its duration; keep the longer one.
        val unique = found.groupBy { listOf(it.getInt("uid"), it.optString("pkg"), it.getString("op"), it.optString("attr"), it.getLong("t")) }
            .values.map { same -> same.maxBy { it.getLong("end") } }
        // The dump is grouped by app, not time; callers page forward by time.
        return JSONArray(unique.sortedBy { it.getLong("t") }.take(max))
    }
}
