// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import org.json.JSONObject

/**
 * Follows process starts in the system event log, including what started each process
 * (activity, service, broadcast, provider...). The read blocks inside logcat, so it costs
 * nothing while nothing happens.
 */
internal class EventLogWatcher(
    private val spool: EventSpool,
    private val ownPackage: String,
    private val register: (Process) -> Unit,
    private val unregister: (Process) -> Unit,
    private val onOwnStart: () -> Unit
) : Thread("event-log-watch") {
    init { isDaemon = true }

    // "1794279187.669  639  677 I am_proc_start: [0,3599,10150,com.example,service,{com.example/.Sync}]"
    private val procStart = Regex("^\\s*(\\d+)\\.(\\d{3})\\s.*am_proc_start: \\[(\\d+),(\\d+),(\\d+),([^,]+),([^,]*),(.*)]\\s*$")

    override fun run() {
        while (true) {
            runCatching {
                val process = ProcessBuilder(COMMAND).redirectErrorStream(true).start()
                register(process)
                try {
                    process.inputStream.bufferedReader().useLines { lines -> lines.forEach(::handle) }
                } finally {
                    unregister(process)
                    process.destroy()
                }
            }.onFailure { ProbeMain.log("event log watch failed: ${it.javaClass.simpleName}") }
            sleep(30_000)
        }
    }

    private fun handle(line: String) {
        val m = procStart.find(line) ?: return
        val processName = m.groupValues[6]
        if (processName == ownPackage) onOwnStart()
        runCatching {
            spool.append(JSONObject()
                .put("kind", "proc_start")
                .put("t", m.groupValues[1].toLong() * 1000 + m.groupValues[2].toLong())
                .put("pid", m.groupValues[4].toInt())
                .put("uid", m.groupValues[5].toInt())
                .put("process", processName)
                .put("reason", m.groupValues[7])
                .put("component", m.groupValues[8].trim('{', '}')))
        }
    }

    companion object {
        /** Starts from new entries only; history before the monitor started is not replayed. */
        val COMMAND = listOf("logcat", "-b", "events", "-v", "epoch", "-T", "1", "am_proc_start:I", "*:S")
    }
}
