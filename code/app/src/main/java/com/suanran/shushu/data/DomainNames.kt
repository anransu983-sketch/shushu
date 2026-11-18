// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

import java.net.InetAddress

/**
 * Names for addresses, from the lookups the domain recorder saw: the domain an app looked up that
 * resolved to an address it then connected to. The app's own lookup is preferred; another app's
 * lookup of the same address stands in when the app's answer came from the system's cache.
 */
class DomainNames(lookups: List<JiumiStore.Lookup>) {
    private val byAddress: Map<String, List<JiumiStore.Lookup>> = buildMap<String, MutableList<JiumiStore.Lookup>> {
        for (l in lookups) for (a in l.answers) canonical(a)?.let { getOrPut(it) { ArrayList() } += l }
    }

    /** The domain [uid] most recently reached [addr] by, or null when no lookup gave that address. */
    fun domainOf(uid: Int, addr: String): String? {
        val list = canonical(addr)?.let(byAddress::get) ?: return null
        return (list.lastOrNull { it.uid == uid } ?: list.lastOrNull())?.domain
    }

    val isEmpty: Boolean get() = byAddress.isEmpty()

    companion object {
        /**
         * One spelling per address, so the probe's compressed IPv6 and Java's full form compare
         * equal. Only literal addresses are parsed; nothing is looked up.
         */
        fun canonical(addr: String): String? {
            if (addr.isEmpty() || !(addr[0].isDigit() || addr.contains(':') || addr[0].lowercaseChar() in 'a'..'f')) return null
            if (!addr.all { it.isDigit() || it == '.' || it == ':' || it.lowercaseChar() in 'a'..'f' }) return null
            return runCatching { InetAddress.getByName(addr).hostAddress }.getOrNull()
        }

        /** Lookups this far before a window still name addresses inside it: answers are cached a while. */
        const val LOOKBACK_MS = 6 * 60 * 60 * 1000L
    }
}

/** A domain an app looked up over a window: how often, when last, and whether any lookup found it. */
data class DomainSummary(val domain: String, val lookups: Int, val last: Long, val found: Boolean)
