// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager

/**
 * Per-uid traffic from the system's own network statistics, which count every byte regardless of
 * how short a connection was. Reading them needs usage access, granted to this app when the monitor
 * starts. The system keeps these statistics in buckets of about two hours, so a range boundary is
 * approximate.
 */
class NetworkUsage(context: Context) {
    data class Traffic(val rx: Long, val tx: Long, val background: Long) {
        val total: Long get() = rx + tx
        operator fun plus(other: Traffic) = Traffic(rx + other.rx, tx + other.tx, background + other.background)
    }

    private val manager = context.getSystemService(NetworkStatsManager::class.java)

    fun byUid(from: Long, to: Long): Map<Int, Traffic> {
        val result = HashMap<Int, Traffic>()
        for (type in NETWORK_TYPES) {
            @Suppress("DEPRECATION")
            val stats = runCatching { manager.querySummary(type, null, from, to) }.getOrNull() ?: continue
            stats.use {
                val bucket = NetworkStats.Bucket()
                while (it.getNextBucket(bucket)) {
                    val bytes = bucket.rxBytes + bucket.txBytes
                    if (bytes == 0L) continue
                    val background = if (bucket.state == NetworkStats.Bucket.STATE_FOREGROUND) 0L else bytes
                    result[bucket.uid] = (result[bucket.uid] ?: ZERO) + Traffic(bucket.rxBytes, bucket.txBytes, background)
                }
            }
        }
        return result
    }

    private companion object {
        @Suppress("DEPRECATION")
        val NETWORK_TYPES = listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_ETHERNET)
        val ZERO = Traffic(0, 0, 0)
    }
}
