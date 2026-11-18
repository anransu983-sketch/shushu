// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

/**
 * How much of a monitoring session the monitor actually covered. The monitor notes "start" when it
 * launches, "alive" every few minutes and "stop" when it is stopped; two consecutive notes no more
 * than [MAX_GAP_MS] apart mean it ran in between. A longer silence, or a fresh start within the
 * session, is an interruption (a reboot, Wireless debugging turned off, a crash).
 */
object Coverage {
    /** Heartbeats come every five minutes; one and a half periods of silence is a gap. */
    const val MAX_GAP_MS = 7 * 60_000L + 30_000L

    data class Result(val coveredMs: Long, val interruptions: Int, val lostEvents: Long, val lastSeen: Long?)

    /** [events] may begin up to [MAX_GAP_MS] before [start]. */
    fun compute(start: Long, end: Long?, events: List<JiumiStore.MonitorEvent>, now: Long): Result {
        val until = end ?: now
        var covered = 0L
        var interruptions = 0
        var lost = 0L
        var last: Long? = null
        var stopped = false
        for (e in events.sortedBy { it.t }) {
            if (e.t > until) continue
            // The monitor usually starts just before the session opens: it was running at the start.
            if (e.t < start) {
                if (start - e.t <= MAX_GAP_MS && e.state != "lost") { last = start; stopped = e.state == "stop" }
                continue
            }
            if (e.state == "lost") { lost += e.reason?.toLongOrNull() ?: 0L; continue }
            val previous = last
            when {
                previous == null -> if (e.t - start <= MAX_GAP_MS) covered += e.t - start
                e.state == "start" -> interruptions++
                stopped -> interruptions++
                e.t - previous <= MAX_GAP_MS -> covered += e.t - previous
                else -> interruptions++
            }
            last = e.t
            stopped = e.state == "stop"
        }
        // Still running: the time since the last note counts while it is recent.
        val tail = last
        if (end == null && tail != null && !stopped && now - tail <= MAX_GAP_MS) covered += now - tail
        return Result(covered, interruptions, lost, last)
    }
}
