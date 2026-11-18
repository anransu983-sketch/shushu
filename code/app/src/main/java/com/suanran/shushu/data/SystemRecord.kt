// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

/**
 * The system's own per-access record repeats what the monitor saw while it ran. Where both hold the
 * same access, the monitor's row, which knows more (the screen, how often within its minute), stands
 * for both; the system's row counts only where the monitor saw nothing of it.
 *
 * Same access: one operation, uid and package, at overlapping times. The monitor folds repeats into
 * one row spanning up to a minute, so its whole span is compared, not its start. Many phones keep the
 * record's times to the minute, so the access may lie up to a minute after the time the record gives.
 * The record holds only what went through, so a refusal the monitor saw never stands for it.
 */
object SystemRecord {
    /** Clock slack between the two sources. */
    const val SLACK_MS = 2_000L
    /** How far a record time kept to the minute may lie before the access it stands for. */
    const val MINUTE_MS = 60_000L

    /** Earliest start and latest end of monitor rows that can cover a record row in [from, to]. */
    fun monitorWindow(from: Long, to: Long): Pair<Long, Long> =
        from - SLACK_MS to (if (to == Long.MAX_VALUE) to else to + MINUTE_MS + SLACK_MS)

    /**
     * [rows] without the system's rows that a row in [monitor] already stands for. [monitor] holds the
     * monitor's rows around [rows], which may reach past them at either end.
     */
    fun withoutRepeats(rows: List<JiumiStore.Access>, monitor: List<JiumiStore.Access>): List<JiumiStore.Access> {
        if (rows.none { it.source == SYSTEM }) return rows
        val spans = monitor.filter { it.source in MONITOR && !it.denied }
            .groupBy { Key(it.op, it.uid, it.pkg.orEmpty()) }
            .mapValues { (_, same) -> Spans(same.sortedBy { it.t }) }
        return rows.filterNot { a ->
            a.source == SYSTEM && spans[Key(a.op, a.uid, a.pkg.orEmpty())]
                ?.overlaps(a.t - SLACK_MS, maxOf(a.t, a.end) + MINUTE_MS + SLACK_MS) == true
        }
    }

    private data class Key(val op: String, val uid: Int, val pkg: String)

    /** Monitor rows of one key by start, with the latest end among each prefix. */
    private class Spans(sorted: List<JiumiStore.Access>) {
        private val starts = LongArray(sorted.size) { sorted[it].t }
        private val latestEnd = LongArray(sorted.size).also { ends ->
            var latest = Long.MIN_VALUE
            sorted.forEachIndexed { i, b -> latest = maxOf(latest, b.t, b.end); ends[i] = latest }
        }

        /** Whether any span meets [lo, hi]: among those starting by [hi], the latest end reaches [lo]. */
        fun overlaps(lo: Long, hi: Long): Boolean {
            var low = 0
            var high = starts.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (starts[mid] <= hi) low = mid + 1 else high = mid
            }
            return low > 0 && latestEnd[low - 1] >= lo
        }
    }

    private const val SYSTEM = "system"
    private val MONITOR = setOf("live", "active")
}
