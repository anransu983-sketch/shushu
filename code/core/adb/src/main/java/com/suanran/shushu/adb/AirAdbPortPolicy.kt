// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.adb

object AirAdbPortPolicy {
    private const val LEGACY_ADB_TCPIP_PORT = 5555

    // lastPort is a quick candidate while the same wireless debugging TLS service is still alive. After Wi-Fi drops or
    // changes, Android may have closed that service, so it is only tried first; on failure the current port from Settings/mDNS is used.
    fun connectPorts(lastPort: Int?, settingsPort: Int?): List<Int> = buildList {
        addIfValid(lastPort)
        addIfValid(settingsPort)
    }.distinct()

    private fun MutableList<Int>.addIfValid(port: Int?) {
        if (port != null && port in 1..65535 && port != LEGACY_ADB_TCPIP_PORT) add(port)
    }
}
