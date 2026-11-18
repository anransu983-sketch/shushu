// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

/**
 * Finds calls to selected AIDL methods in a Perfetto trace and the thread that made each call.
 *
 * The method name comes from the server-side atrace slice "AIDL::<lang>::<Interface>::<method>::server"
 * (Java) or "::cppServer" (native). The caller is found through the binder driver events: the
 * server thread's binder_transaction_received names a transaction whose binder_transaction event
 * was emitted by the calling thread. Every language is read from the server side, so nothing is
 * counted twice.
 *
 * Content providers implement their binder interface by hand and leave no slice. A transaction no
 * slice claims is reported when [providerOperation] names it from the server process and the
 * transaction code, so reads and writes of a provider are seen by caller and operation.
 */
object BinderCalls {
    /** [timestampNs] is the trace clock (boot time). [clientTid] is a thread id, not a process id. */
    data class Call(val timestampNs: Long, val clientTid: Int, val iface: String, val method: String)

    data class Result(val calls: List<Call>, val watermarkNs: Long)

    private val slice = Regex("^B\\|\\d+\\|AIDL::\\w+::([A-Za-z0-9_.$]+)::(\\w+)::(server|cppServer)\\s*$")

    private class Transaction(val timestampNs: Long, val clientTid: Int, val toProc: Int, val code: Int)
    private class Received(val timestampNs: Long, val debugId: Long)
    private class Slice(val timestampNs: Long, val tid: Int, val iface: String, val method: String)

    /**
     * Calls whose slice is later than [watermarkNs] and accepted by [selected]. The returned
     * watermark is the latest timestamp seen, for the next, overlapping trace.
     */
    fun parse(
        trace: ByteArray,
        watermarkNs: Long,
        providerOperation: (serverPid: Int, code: Int) -> Pair<String, String>? = { _, _ -> null },
        selected: (iface: String, method: String) -> Boolean
    ): Result {
        val transactions = HashMap<Long, Transaction>()
        val received = HashMap<Int, MutableList<Received>>()
        val allSlices = ArrayList<Slice>()
        var latest = watermarkNs
        ProtoCursor.forEachFtraceEvent(trace) { event ->
            var ts = 0L
            var tid = 0
            while (event.next()) {
                when (event.field) {
                    1 -> ts = event.value
                    2 -> tid = event.value.toInt()
                    50 -> if (event.isMessage) {
                        val fields = event.child()
                        var debugId = -1L
                        var reply = 0L
                        var toProc = 0
                        var code = 0
                        while (fields.next()) when (fields.field) {
                            1 -> debugId = fields.value
                            3 -> toProc = fields.value.toInt()
                            5 -> reply = fields.value
                            6 -> code = fields.value.toInt()
                        }
                        if (debugId >= 0 && reply == 0L) transactions[debugId] = Transaction(ts, tid, toProc, code)
                    }
                    51 -> if (event.isMessage) {
                        val fields = event.child()
                        while (fields.next()) if (fields.field == 1) {
                            received.getOrPut(tid) { ArrayList() } += Received(ts, fields.value)
                        }
                    }
                    3 -> if (event.isMessage) {
                        val fields = event.child()
                        while (fields.next()) if (fields.field == 2 && fields.isMessage) {
                            val m = slice.find(fields.text(256)) ?: continue
                            allSlices += Slice(ts, tid, m.groupValues[1].substringAfterLast('.'), m.groupValues[2])
                        }
                    }
                }
            }
            if (ts > latest) latest = ts
        }
        received.values.forEach { list -> list.sortBy { it.timestampNs } }
        val claimed = HashSet<Long>()
        val calls = ArrayList<Call>()
        // Every slice claims its transaction, whether or not its method is selected.
        for (s in allSlices) {
            val onThread = received[s.tid] ?: continue
            // The newest transaction this server thread received before the slice began.
            val index = onThread.binarySearchBy(s.timestampNs) { it.timestampNs }.let { if (it >= 0) it else -it - 2 }
            val r = onThread.getOrNull(index) ?: continue
            if (s.timestampNs - r.timestampNs > MAX_DISPATCH_NS) continue
            claimed += r.debugId
            if (s.timestampNs <= watermarkNs || !selected(s.iface, s.method)) continue
            val t = transactions[r.debugId] ?: continue
            calls += Call(s.timestampNs, t.clientTid, s.iface, s.method)
        }
        for ((debugId, t) in transactions) {
            if (t.timestampNs <= watermarkNs || debugId in claimed) continue
            val (iface, method) = providerOperation(t.toProc, t.code) ?: continue
            if (selected(iface, method)) calls += Call(t.timestampNs, t.clientTid, iface, method)
        }
        return Result(calls.sortedBy { it.timestampNs }, latest)
    }

    private const val MAX_DISPATCH_NS = 100_000_000L
}
