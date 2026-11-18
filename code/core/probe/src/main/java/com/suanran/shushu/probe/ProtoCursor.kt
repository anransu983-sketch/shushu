// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

/** Forward-only reader over one protobuf message; enough for the few Perfetto fields we use. */
internal class ProtoCursor(private val data: ByteArray, from: Int, private val to: Int) {
    private var position = from
    var field = 0
        private set
    var isMessage = false
        private set
    /** Value of the current varint field. */
    var value = 0L
        private set
    private var start = 0
    private var end = 0

    fun next(): Boolean {
        if (position >= to) return false
        val key = varint()
        field = (key ushr 3).toInt()
        val type = (key and 7).toInt()
        isMessage = type == 2
        when (type) {
            0 -> value = varint()
            1 -> position += 8
            5 -> position += 4
            2 -> {
                val length = varint().toInt()
                start = position
                end = position + length
                require(length >= 0 && end <= to) { "truncated trace" }
                position = end
            }
            else -> throw IllegalArgumentException("unsupported wire type $type")
        }
        return true
    }

    fun child() = ProtoCursor(data, start, end)

    /** Sum of a packed repeated varint field. */
    fun sumPacked(): Long {
        val saved = position
        position = start
        var sum = 0L
        while (position < end) sum += varint()
        position = saved
        return sum
    }

    fun text(limit: Int = Int.MAX_VALUE) = String(data, start, minOf(end - start, limit), Charsets.UTF_8)

    private fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = data[position++].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b < 0x80) return result
            shift += 7
        }
    }

    companion object {
        /** Calls [onEvent] with a cursor over every FtraceEvent in a Trace. */
        inline fun forEachFtraceEvent(trace: ByteArray, onEvent: (ProtoCursor) -> Unit) {
            val packets = ProtoCursor(trace, 0, trace.size)
            while (packets.next()) {
                if (packets.field != 1 || !packets.isMessage) continue
                val packet = packets.child()
                while (packet.next()) {
                    if (packet.field != 1 || !packet.isMessage) continue
                    val bundle = packet.child()
                    while (bundle.next()) {
                        if (bundle.field == 2 && bundle.isMessage) onEvent(bundle.child())
                    }
                }
            }
        }
    }
}
