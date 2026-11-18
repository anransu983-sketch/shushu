// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

/**
 * Parses the kernel socket tables in /proc/net/{tcp,tcp6,udp,udp6}. Each row carries the owning
 * uid, so reading them attributes connections without touching any process.
 */
object ProcNet {
    data class Socket(val proto: String, val inode: Long, val uid: Int, val addr: String, val port: Int, val localPort: Int)

    /** Sockets with a remote peer that is not on this device; listening and closed sockets are skipped. */
    fun parse(table: String, proto: String): List<Socket> {
        val tcp = proto == "tcp"
        return table.lineSequence().drop(1).mapNotNull { line ->
            val f = line.trim().split(WHITESPACE)
            if (f.size < 10) return@mapNotNull null
            val state = f[3].toIntOrNull(16) ?: return@mapNotNull null
            // TCP: 01 established, 02 syn-sent, 04..05/08/09/0B closing but still owned. 06 time-wait has no owner,
            // 0A is listening. UDP: 01 means connected to one peer.
            if (tcp && state !in OWNED_TCP_STATES || !tcp && state != 1) return@mapNotNull null
            val localPort = f[1].substringAfterLast(':').toIntOrNull(16) ?: return@mapNotNull null
            val remote = f[2]
            val colon = remote.indexOf(':')
            if (colon < 0) return@mapNotNull null
            val port = remote.substring(colon + 1).toIntOrNull(16) ?: return@mapNotNull null
            val addr = address(remote.substring(0, colon)) ?: return@mapNotNull null
            if (port == 0 || isLocal(addr)) return@mapNotNull null
            val uid = f[7].toIntOrNull() ?: return@mapNotNull null
            val inode = f[9].toLongOrNull() ?: return@mapNotNull null
            // A socket its app already closed, still finishing in the kernel, has no inode and shows
            // uid 0; it is the tail of a connection already seen under its app, not one root made.
            if (inode == 0L) return@mapNotNull null
            Socket(proto, inode, uid, addr, port, localPort)
        }.toList()
    }

    /**
     * The kernel prints each 32-bit word of the address in host byte order (little-endian on
     * Android devices). IPv4-mapped IPv6 addresses are reported as IPv4.
     */
    internal fun address(hex: String): String? {
        if (hex.length != 8 && hex.length != 32) return null
        val bytes = ByteArray(hex.length / 2)
        for (word in 0 until hex.length / 8) {
            for (i in 0 until 4) {
                val pos = word * 8 + (3 - i) * 2
                bytes[word * 4 + i] = hex.substring(pos, pos + 2).toIntOrNull(16)?.toByte() ?: return null
            }
        }
        if (bytes.size == 4) return v4(bytes, 0)
        val mapped = (0 until 10).all { bytes[it] == 0.toByte() } && bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        if (mapped) return v4(bytes, 12)
        return (0 until 8).joinToString(":") { i ->
            Integer.toHexString(((bytes[i * 2].toInt() and 0xFF) shl 8) or (bytes[i * 2 + 1].toInt() and 0xFF))
        }.let(::compressV6)
    }

    private fun v4(b: ByteArray, at: Int) = (at until at + 4).joinToString(".") { (b[it].toInt() and 0xFF).toString() }

    /** Shortens the longest run of zero groups to "::", as addresses are usually written. */
    private fun compressV6(full: String): String {
        val groups = full.split(':')
        var bestStart = -1; var bestLen = 0; var start = -1
        for (i in 0..groups.size) {
            if (i < groups.size && groups[i] == "0") { if (start < 0) start = i; continue }
            if (start >= 0 && i - start > bestLen && i - start > 1) { bestStart = start; bestLen = i - start }
            start = -1
        }
        if (bestStart < 0) return full
        return groups.take(bestStart).joinToString(":") + "::" + groups.drop(bestStart + bestLen).joinToString(":")
    }

    private fun isLocal(addr: String): Boolean =
        addr == "0.0.0.0" || addr.startsWith("127.") || addr == "::" || addr == "::1" || addr.startsWith("fe80:")

    private val WHITESPACE = Regex("\\s+")
    private val OWNED_TCP_STATES = setOf(0x01, 0x02, 0x04, 0x05, 0x08, 0x09, 0x0B)
}
