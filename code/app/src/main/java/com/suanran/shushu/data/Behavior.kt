// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

/**
 * Occurrences per time bucket starting at [start], each [stepMs] long (an hour or a local day);
 * [flagged] counts those that happened while the app was not in use.
 */
class Buckets(val start: Long, val stepMs: Long, val total: IntArray, val flagged: IntArray) {
    val size: Int get() = total.size
}

/**
 * One kind of data an app reached over a window, as its row tells it: how often, when last, how
 * often while not in use, and how often refused.
 */
data class BehaviorSummary(
    val category: String,
    val count: Int,
    val unattended: Int,
    val denied: Int,
    val last: Long,
    val buckets: Buckets
)

object Behavior {
    private class Record(val t: Long, val end: Long, val count: Int, val category: String,
                         val unattended: Boolean, val denied: Boolean)

    /**
     * One summary per category in [items] over [from, to], most worrying first: unattended and
     * refused reads before plain counts. [routine] callers have no meaningful app state, so none of
     * their reads count as unattended.
     */
    fun summaries(items: List<TimelineItem>, from: Long, to: Long, routine: Boolean = false,
                  offsetMs: (Long) -> Long = ::localOffset): List<BehaviorSummary> =
        records(items, routine).groupBy { it.category }.map { (category, list) ->
            BehaviorSummary(
                category = category,
                count = list.sumOf { it.count },
                unattended = list.filter { it.unattended }.sumOf { it.count },
                denied = list.filter { it.denied }.sumOf { it.count },
                last = list.maxOf { maxOf(it.t, it.end) },
                buckets = buckets(list, from, to, offsetMs)
            )
        }.sortedWith(compareByDescending<BehaviorSummary> { it.unattended + it.denied }.thenByDescending { it.count })

    /** Every behavior of the app over [from, to], per bucket. */
    fun chart(items: List<TimelineItem>, from: Long, to: Long, routine: Boolean = false,
              offsetMs: (Long) -> Long = ::localOffset): Buckets =
        buckets(records(items, routine), from, to, offsetMs)

    private fun flat(items: List<TimelineItem>) = items.flatMap { (it as? TimelineItem.Segment)?.members ?: listOf(it) }

    private fun records(items: List<TimelineItem>, routine: Boolean): List<Record> = flat(items).mapNotNull { item ->
        when (item) {
            is TimelineItem.AccessItem -> with(item.access) {
                // A refused attempt gave the app nothing, wherever it was.
                Record(t, end, count, item.category, !routine && !denied && unattended(state, screen), denied)
            }
            is TimelineItem.CallItem -> with(item.call) {
                Record(t, end, count, item.category, !routine && !item.refused && unattended(state, screen), item.refused)
            }
            else -> null
        }
    }

    fun unattended(state: String?, screen: Boolean?): Boolean = ReportBuilder.isUnattended(state, screen)

    /**
     * Hours for a window of up to a day, local days beyond. A window starting at local midnight and
     * no longer than a day shows all 24 hours, the ones still to come empty, as a clock face would.
     */
    fun buckets(from: Long, to: Long, offsetMs: (Long) -> Long = ::localOffset): Buckets {
        val step = if (to - from <= DAY) HOUR else DAY
        val offset = offsetMs(from)
        val start = Math.floorDiv(from + offset, step) * step - offset
        val wholeDay = step == HOUR && Math.floorMod(from + offset, DAY) == 0L
        val bars = if (wholeDay) 24 else ((to - start + step - 1) / step).toInt().coerceIn(1, MAX_BARS)
        return Buckets(start, step, IntArray(bars), IntArray(bars))
    }

    private fun buckets(records: List<Record>, from: Long, to: Long, offsetMs: (Long) -> Long): Buckets {
        val b = buckets(from, to, offsetMs)
        records.forEach { r ->
            val i = index(b, r.t, offsetMs)
            if (i !in 0 until b.size) return@forEach
            b.total[i] += r.count
            if (r.unattended) b.flagged[i] += r.count
        }
        return b
    }

    /** Days are counted on the local calendar, so a daylight-saving change does not shift them. */
    private fun index(b: Buckets, t: Long, offsetMs: (Long) -> Long): Int =
        if (b.stepMs == HOUR) Math.floorDiv(t - b.start, HOUR).toInt()
        else (Math.floorDiv(t + offsetMs(t), DAY) - Math.floorDiv(b.start + offsetMs(b.start), DAY)).toInt()

    private fun localOffset(t: Long): Long = java.util.TimeZone.getDefault().getOffset(t).toLong()

    /** A month of days at most; longer windows are not offered. */
    const val MAX_BARS = 31
    const val HOUR = 3_600_000L
    const val DAY = 24 * HOUR
}
