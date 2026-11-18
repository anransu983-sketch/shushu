// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

/**
 * What state each uid was in, and whether the screen was on, at any moment of the last [keepMs]:
 * events are judged at their own time, not at the time the monitor gets round to them. Each entry
 * holds from its time until the next one; the latest entry of a uid is kept however old it is.
 */
internal class StateHistory(private val keepMs: Long = KEEP_MS) {
    private val byUid = HashMap<Int, ArrayDeque<Pair<Long, String>>>()
    private val screen = ArrayDeque<Pair<Long, Boolean>>()
    /** Before this time nothing was observed. */
    @Volatile var since = Long.MAX_VALUE
        private set

    @Synchronized
    fun begin(t: Long, states: Map<Int, String>, screenOn: Boolean) {
        since = t
        states.forEach { (uid, state) -> record(uid, t, state) }
        recordScreen(t, screenOn)
    }

    @Synchronized
    fun record(uid: Int, t: Long, state: String) {
        val list = byUid.getOrPut(uid) { ArrayDeque() }
        if (list.lastOrNull()?.second == state) return
        list.addLast(t to state)
        prune(list, t)
    }

    @Synchronized
    fun recordScreen(t: Long, on: Boolean) {
        if (screen.lastOrNull()?.second == on) return
        screen.addLast(t to on)
        prune(screen, t)
    }

    /**
     * The uid's state at [t]: the latest entry at or before it, "gone" when the uid was not seen
     * running by then, null when [t] predates observation. The system reports a change a little
     * after it happens, so a change reported within [REPORT_DELAY_MS] after [t] counts if it is
     * the more active state: an app opening the camera as it comes to the front is in the front.
     */
    @Synchronized
    fun stateAt(uid: Int, t: Long): String? {
        if (t < since) return null
        val list = byUid[uid] ?: return GONE
        val at = list.lastOrNull { it.first <= t }?.second ?: GONE
        val soon = list.filter { it.first > t && it.first <= t + REPORT_DELAY_MS }.map { it.second }
        return (soon + at).minBy(::rank)
    }

    @Synchronized
    fun screenAt(t: Long): Boolean? = if (t < since) null else screen.lastOrNull { it.first <= t }?.second

    private fun <T> prune(list: ArrayDeque<Pair<Long, T>>, now: Long) {
        while (list.size > 1 && list[1].first < now - keepMs) list.removeFirst()
    }

    companion object {
        const val GONE = "gone"
        const val REPORT_DELAY_MS = 2_000L
        private val ORDER = listOf("pers", "top", "fgsvc", "fg", "bg", "cch", GONE)

        /** Lower is more active; unknown states rank with the least active. */
        fun rank(state: String): Int = ORDER.indexOf(state).let { if (it < 0) ORDER.size else it }
        /** Long enough for the slowest consumer: a trace chunk parsed a few minutes after it was written. */
        const val KEEP_MS = 30 * 60_000L
    }
}
