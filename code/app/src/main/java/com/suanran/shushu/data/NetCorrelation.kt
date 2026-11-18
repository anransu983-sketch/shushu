// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

/** Relates recorded connections to each other and to accesses in time. */
object NetCorrelation {
    /** Connections are seen at the monitor's polling interval, so one may be noticed a little late. */
    const val POLL_SLACK_MS = 5_000L
    /** The monitor ends a contact after 60 s unseen; a later span at most one poll beyond that continues it. */
    const val CONTINUATION_MS = 70_000L

    /**
     * Conversations with a destination. The monitor splits long contacts into spans; a span that
     * begins while the previous one with the same destination was still being seen continues it,
     * and is merged into it with its sockets and bytes.
     */
    fun conversations(contacts: List<JiumiStore.Connection>): List<JiumiStore.Connection> =
        contacts.groupBy { Triple(it.proto, it.addr, it.port) }.values.flatMap { list ->
            val merged = ArrayList<JiumiStore.Connection>()
            for (c in list.sortedBy { it.t }) {
                val previous = merged.lastOrNull()
                if (previous == null || c.t - previous.end > CONTINUATION_MS) {
                    merged += c
                } else {
                    merged[merged.lastIndex] = previous.copy(
                        end = maxOf(previous.end, c.end),
                        count = previous.count + c.count,
                        sent = sum(previous.sent, c.sent),
                        received = sum(previous.received, c.received),
                        sentUnattended = sum(previous.sentUnattended, c.sentUnattended)
                    )
                }
            }
            merged
        }.sortedBy { it.t }

    private fun sum(a: Long?, b: Long?): Long? = if (a == null && b == null) null else (a ?: 0L) + (b ?: 0L)
}
