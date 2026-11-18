// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

/**
 * Counts binder transactions and the atrace slices that name an AIDL method; nothing is kept
 * from the trace afterwards.
 *
 * Field numbers follow perfetto/protos: Trace.packet = 1, TracePacket.ftrace_events = 1,
 * FtraceEventBundle.event = 2, FtraceEvent.print = 3, FtraceEvent.binder_transaction = 50,
 * PrintFtraceEvent.buf = 2.
 */
object PerfettoTraceScan {
    data class Counts(val binderTransactions: Long, val namedCalls: Long, val traceBytes: Long)

    // Atrace begin markers are "B|pid|name". Platform AIDL names are "AIDL::<lang>::IFoo::bar::server"
    // on newer releases and "IFoo:bar" on older ones.
    private val aidlName = Regex("^B\\|\\d+\\|(AIDL::|I[A-Z][A-Za-z0-9_]*:[a-z])")

    fun scan(trace: ByteArray): Counts {
        var binder = 0L
        var named = 0L
        ProtoCursor.forEachFtraceEvent(trace) { event ->
            while (event.next()) {
                if (!event.isMessage) continue
                when (event.field) {
                    50 -> binder++
                    3 -> {
                        val print = event.child()
                        while (print.next()) {
                            if (print.field == 2 && print.isMessage && aidlName.containsMatchIn(print.text(160))) named++
                        }
                    }
                }
            }
        }
        return Counts(binder, named, trace.size.toLong())
    }
}
