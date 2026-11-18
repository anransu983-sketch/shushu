// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import org.json.JSONObject
import java.io.File

/**
 * Records which uid talks to which remote address by reading the kernel socket tables at a fixed
 * interval, and how many bytes each conversation moved from the packet trace ([addTraffic]). The
 * tables are read-only snapshots and the trace holds packet metadata only; no process is touched
 * and no content is seen.
 *
 * Sockets are folded per uid and destination: an event spans the time the destination was seen and
 * counts the distinct sockets in that span. An event ends when the destination has not been seen
 * for [IDLE_MS], or after [MAX_SPAN_MS] so long connections still reach the record. Each event is
 * written as soon as its destination is first seen and again every [PROGRESS_MS] while it grows,
 * always with the same start, so the record follows a conversation live and the last write stands.
 * Traffic of a connection too short to appear in any snapshot is kept per uid and remote port, with
 * no address. (The kernel's TCP state trace point would catch those, but user builds do not let
 * Perfetto enable it.)
 */
internal class ConnectionWatcher(
    private val spool: EventSpool,
    private val states: UidStates
) : Thread("net-watcher") {
    init { isDaemon = true }

    private val lock = Object()
    @Volatile private var enabled = false
    @Volatile private var intervalMs = DEFAULT_INTERVAL_MS
    /** Set while the packet trace runs; events then carry byte counts, otherwise none. */
    @Volatile var countingBytes = false

    private class Contact(val event: JSONObject, val first: Long, val counted: Boolean) {
        var last = first
        val sockets = HashSet<Long>()
        /** When this event was last written, and what it said then; never written yet when [writtenAt] is 0. */
        var writtenAt = 0L
        var written = ""
        fun summary() = "$last|${sockets.size}|$sent|$received|$sentUnattended"
        var sent = 0L
        var received = 0L
        /** Bytes sent while the app was in the background or the screen was off. */
        var sentUnattended = 0L
    }

    /** Whether each uid with open connections was in use, sampled at every poll for the last minutes. */

    /** Where a local port last led, so bytes reported after the socket closed still find it. */
    private class PortOwner(val key: String, val template: JSONObject, var seen: Long)

    private val contacts = HashMap<String, Contact>()
    private val ports = HashMap<String, PortOwner>()

    fun configure(on: Boolean, interval: Long) {
        synchronized(lock) {
            enabled = on
            intervalMs = interval.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
            lock.notifyAll()
        }
        ProbeMain.log("connection watch ${if (on) "on, every ${intervalMs} ms" else "off"}")
    }

    override fun run() {
        while (true) {
            try {
                if (!enabled) {
                    synchronized(lock) {
                        flushAll()
                        while (!enabled) lock.wait()
                    }
                    continue
                }
                synchronized(lock) {
                    poll(System.currentTimeMillis())
                    // Waiting does not hold the device awake; while it is suspended nothing is polled.
                    lock.wait(intervalMs)
                }
            } catch (error: Exception) {
                ProbeMain.log("connection watch failed: ${error.javaClass.simpleName}: ${error.message}")
                sleep(MAX_INTERVAL_MS)
            }
        }
    }

    /**
     * Adds the bytes of one trace; called from the tracing thread. [toWallMs] converts trace time,
     * so each upload is matched with whether the app was in use when it happened.
     */
    fun addTraffic(flows: Map<NetworkPackets.Flow, NetworkPackets.Bytes>, toWallMs: (Long) -> Long) {
        if (flows.isEmpty()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            for ((flow, bytes) in flows) {
                if (bytes.sent == 0L && bytes.received == 0L) continue
                val owner = ports[portKey(flow.uid, flow.proto, flow.localPort)]
                val contact = if (owner != null) {
                    contacts.getOrPut(owner.key) { Contact(JSONObject(owner.template.toString()), now, countingBytes) }
                } else {
                    // Too short to be seen in the socket table: kept per uid and remote port, without an address.
                    contacts.getOrPut("${flow.uid}|${flow.proto}||${flow.remotePort}") {
                        Contact(template(flow.uid, flow.proto, "", flow.remotePort), now, countingBytes)
                    }
                }
                contact.sent += bytes.sent
                contact.received += bytes.received
                for ((ts, n) in bytes.sentAt) if (!wasAttended(flow.uid, toWallMs(ts))) contact.sentUnattended += n
                contact.last = maxOf(contact.last, now)
            }
        }
    }

    private fun poll(now: Long) {
        val stateCache = HashMap<Int, String>()
        for ((file, proto) in TABLES) {
            val text = runCatching { File(file).readText() }.getOrNull() ?: continue
            for (s in ProcNet.parse(text, proto)) {
                stateCache.getOrPut(s.uid) { states.state(s.uid) }
                val key = "${s.uid}|${s.proto}|${s.addr}|${s.port}"
                val contact = contacts.getOrPut(key) {
                    Contact(template(s.uid, s.proto, s.addr, s.port, stateCache), now, countingBytes)
                }
                contact.last = now
                contact.sockets += s.inode
                val pk = portKey(s.uid, s.proto, s.localPort)
                val owner = ports[pk]
                if (owner == null || owner.key != key) ports[pk] = PortOwner(key, contact.event, now) else owner.seen = now
            }
        }
        ports.values.removeAll { now - it.seen > PORT_MEMORY_MS }
        contacts.entries.removeAll { (_, c) ->
            (now - c.last >= IDLE_MS || now - c.first >= MAX_SPAN_MS).also { if (it) flush(c) }
        }
        // New destinations at once, growing ones now and then; the store keeps the latest of each.
        for (c in contacts.values) {
            if (c.writtenAt == 0L || now - c.writtenAt >= PROGRESS_MS && c.summary() != c.written) flush(c, now)
        }
    }

    private fun template(uid: Int, proto: String, addr: String, port: Int, cache: HashMap<Int, String>? = null) =
        JSONObject()
            .put("kind", "net")
            .put("uid", uid)
            .put("proto", proto)
            .put("addr", addr)
            .put("port", port)
            .put("state", cache?.getOrPut(uid) { states.state(uid) } ?: states.state(uid))
            .put("screen", states.screenOn)

    /** The app was in use when the packet left: the screen on and the app not in the background, at that moment. */
    private fun wasAttended(uid: Int, t: Long): Boolean = states.screenAt(t) && states.stateAt(uid, t) !in UNATTENDED_STATES

    private fun portKey(uid: Int, proto: String, localPort: Int) = "$uid|$proto|$localPort"

    private fun flushAll() {
        contacts.values.forEach(::flush)
        contacts.clear()
    }

    /** Writes [c] as it stands; [now] marks a progress write, after which the event goes on. */
    private fun flush(c: Contact, now: Long = 0L) {
        if (now != 0L) { c.writtenAt = now; c.written = c.summary() }
        else if (c.writtenAt != 0L && c.summary() == c.written) return
        val event = JSONObject(c.event.toString()).put("t", c.first).put("end", c.last).put("count", c.sockets.size)
        if (c.counted) event.put("sent", c.sent).put("received", c.received).put("sentUnattended", c.sentUnattended)
        runCatching { spool.append(event) }.onFailure { ProbeMain.log("spool write failed: ${it.javaClass.simpleName}") }
    }

    private companion object {
        val TABLES = listOf(
            "/proc/net/tcp" to "tcp", "/proc/net/tcp6" to "tcp",
            "/proc/net/udp" to "udp", "/proc/net/udp6" to "udp"
        )
        const val DEFAULT_INTERVAL_MS = 5_000L
        const val MIN_INTERVAL_MS = 2_000L
        const val MAX_INTERVAL_MS = 60_000L
        const val IDLE_MS = 60_000L
        /** How often a destination still in use is written again with its growing span and bytes. */
        const val PROGRESS_MS = 15_000L
        const val MAX_SPAN_MS = 10 * 60_000L
        /** Longer than a trace session, so a socket's last bytes arrive while its port is remembered. */
        const val PORT_MEMORY_MS = 120_000L
        /** Process states in which the app is not in front of the user; the report reads them the same way. */
        val UNATTENDED_STATES = setOf("fgsvc", "bg", "cch", "gone")
    }
}
