// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

/**
 * One action, seen twice: when a method call and a permission record of the same caller fall
 * together, in the call's category or one the rules say records it, the system noted the call it
 * served (getConnectionInfo is noted as a Wi-Fi scan, a contacts query as reading contacts). Such
 * calls are counted once, as the access; each access absorbs at most as many calls as it counts,
 * accesses of the call's own category first, and the rest of a call stays a call of its own.
 */
object Overlap {
    /** Clock slack between the two sources, which bucket their records separately. */
    const val SLACK_MS = 5_000L

    /** [calls] left to count on their own, with what was absorbed taken off; [absorbed] per access. */
    class Result(
        val calls: List<Pair<JiumiStore.Call, String>>,
        val absorbed: Map<JiumiStore.Access, List<JiumiStore.Call>>
    )

    fun absorb(
        accesses: List<Pair<JiumiStore.Access, String>>,
        calls: List<Pair<JiumiStore.Call, String>>,
        recordedAs: (category: String) -> Set<String> = { emptySet() }
    ): Result {
        val room = HashMap<JiumiStore.Access, Int>()
        accesses.forEach { (a, _) -> room[a] = a.count }
        val byKey = accesses.groupBy({ it.first.uid to it.second }, { it.first })
        val absorbed = LinkedHashMap<JiumiStore.Access, MutableList<JiumiStore.Call>>()
        val left = ArrayList<Pair<JiumiStore.Call, String>>()
        for (entry in calls.sortedBy { it.first.t }) {
            val (call, category) = entry
            var remaining = call.count
            val candidates = (listOf(category) + (recordedAs(category) - category)).flatMap { byKey[call.uid to it].orEmpty() }
            for (a in candidates) {
                if (remaining == 0) break
                if (call.t > maxOf(a.t, a.end) + SLACK_MS || call.end < a.t - SLACK_MS) continue
                val take = minOf(remaining, room.getValue(a))
                if (take == 0) continue
                room[a] = room.getValue(a) - take
                remaining -= take
                absorbed.getOrPut(a) { ArrayList() } += call.copy(count = take)
            }
            if (remaining > 0) left += (if (remaining == call.count) call else call.copy(count = remaining)) to category
        }
        return Result(left, absorbed)
    }
}
