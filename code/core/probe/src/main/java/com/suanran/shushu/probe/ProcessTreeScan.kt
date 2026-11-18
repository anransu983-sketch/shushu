// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

/**
 * Processes and threads that Perfetto recorded while the trace ran, so callers that exited before
 * the trace is read are still known. Field numbers follow perfetto/protos: TracePacket.process_tree
 * = 2; ProcessTree.processes = 1 (pid 1, uid 5, cmdline 3), threads = 2 (tid 1, name 2, tgid 3).
 */
object ProcessTreeScan {
    data class Process(val uid: Int, val name: String)
    data class Thread(val pid: Int, val name: String?)
    data class Tree(val processes: Map<Int, Process>, val threads: Map<Int, Thread>)

    fun parse(trace: ByteArray): Tree {
        val processes = HashMap<Int, Process>()
        val threads = HashMap<Int, Thread>()
        val packets = ProtoCursor(trace, 0, trace.size)
        while (packets.next()) {
            if (packets.field != 1 || !packets.isMessage) continue
            val packet = packets.child()
            while (packet.next()) {
                if (packet.field != 2 || !packet.isMessage) continue
                val tree = packet.child()
                while (tree.next()) {
                    if (!tree.isMessage) continue
                    val entry = tree.child()
                    var id = -1; var uid = -1; var other = -1; var name: String? = null
                    while (entry.next()) when (entry.field) {
                        1 -> id = entry.value.toInt()
                        2 -> if (entry.isMessage) name = entry.text(256)
                        3 -> if (entry.isMessage) name = name ?: entry.text(256) else other = entry.value.toInt()
                        5 -> uid = entry.value.toInt()
                    }
                    if (id < 0) continue
                    when (tree.field) {
                        // cmdline is field 3 of a process; only its first argument names it.
                        1 -> if (uid >= 0 && name != null) processes[id] = Process(uid, name.substringBefore('\u0000'))
                        2 -> if (other >= 0) threads[id] = Thread(other, name)
                    }
                }
            }
        }
        return Tree(processes, threads)
    }
}
