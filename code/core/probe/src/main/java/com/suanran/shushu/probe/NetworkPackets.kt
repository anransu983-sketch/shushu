// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

/**
 * Sums the packet metadata of the "android.network_packets" Perfetto data source (Android 14+):
 * per packet the owning uid, direction, length and ports, never its content or addresses.
 * Bytes are summed per uid and local port, which the socket table then ties to a remote address.
 *
 * Field numbers follow perfetto/protos: TracePacket.timestamp = 8, network_packet = 88,
 * network_packet_bundle = 92; NetworkPacketEvent: direction 1 (1 in, 2 out), length 3, uid 4,
 * ip_proto 6, local_port 8, remote_port 9; NetworkPacketBundle: ctx 2, packet_lengths 4 (packed),
 * total_length 7.
 */
object NetworkPackets {
    data class Flow(val uid: Int, val proto: String, val localPort: Int, val remotePort: Int)

    /** [sentAt]: (trace time in ns, bytes) of each outgoing packet or bundle, to place uploads in time. */
    class Bytes {
        var sent = 0L
        var received = 0L
        val sentAt = ArrayList<Pair<Long, Long>>()
    }

    data class Result(val flows: Map<Flow, Bytes>, val watermarkNs: Long)

    /** Packets later than [watermarkNs]; the returned watermark is the latest packet time seen. */
    fun parse(trace: ByteArray, watermarkNs: Long): Result {
        val flows = HashMap<Flow, Bytes>()
        var latest = watermarkNs
        val packets = ProtoCursor(trace, 0, trace.size)
        while (packets.next()) {
            if (packets.field != 1 || !packets.isMessage) continue
            val packet = packets.child()
            var ts = 0L
            var event: ProtoCursor? = null
            var bundle: ProtoCursor? = null
            while (packet.next()) when (packet.field) {
                8 -> ts = packet.value
                88 -> if (packet.isMessage) event = packet.child()
                92 -> if (packet.isMessage) bundle = packet.child()
            }
            if (ts <= watermarkNs || (event == null && bundle == null)) continue
            if (ts > latest) latest = ts
            if (event != null) {
                add(flows, readEvent(event) ?: continue, length = null, ts = ts)
            } else if (bundle != null) {
                var context: Context? = null
                var total = -1L
                var lengths = 0L
                while (bundle.next()) when (bundle.field) {
                    2 -> if (bundle.isMessage) context = readEvent(bundle.child())
                    4 -> if (bundle.isMessage) lengths = bundle.sumPacked() else lengths += bundle.value
                    7 -> total = bundle.value
                }
                // Interned contexts (field 1) are disabled in the trace config, so none are expected.
                add(flows, context ?: continue, length = if (total >= 0) total else lengths, ts = ts)
            }
        }
        return Result(flows, latest)
    }

    private class Context(val flow: Flow, val outgoing: Boolean, val length: Long)

    private fun readEvent(c: ProtoCursor): Context? {
        var direction = 0L; var length = 0L; var uid = -1L; var proto = 0L; var local = 0L; var remote = 0L
        while (c.next()) when (c.field) {
            1 -> direction = c.value
            3 -> length = c.value
            4 -> uid = c.value
            6 -> proto = c.value
            8 -> local = c.value
            9 -> remote = c.value
        }
        val name = when (proto) { 6L -> "tcp"; 17L -> "udp"; else -> return null }
        if (uid < 0 || direction !in 1L..2L) return null
        return Context(Flow(uid.toInt(), name, local.toInt(), remote.toInt()), direction == 2L, length)
    }

    private fun add(flows: HashMap<Flow, Bytes>, c: Context, length: Long?, ts: Long) {
        val bytes = flows.getOrPut(c.flow) { Bytes() }
        val n = length ?: c.length
        if (c.outgoing) {
            bytes.sent += n
            bytes.sentAt += ts to n
        } else {
            bytes.received += n
        }
    }
}
