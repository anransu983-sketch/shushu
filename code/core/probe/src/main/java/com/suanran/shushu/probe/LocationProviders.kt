// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import org.json.JSONArray
import org.json.JSONObject

/**
 * The apps the system has bound as its location providers, from `dumpsys location`. A provider scans
 * Wi-Fi, cells and Bluetooth to work out a fix for whichever app asked; the system notes that app's
 * own access as well.
 *
 * Layout (AOSP, Android 12 and later):
 *   Location Providers:
 *     network provider:
 *       ...
 *       identity=10266/com.oplus.location
 * A provider run by the platform itself names a uid below 10000 and an attribution in brackets.
 */
internal object LocationProviders {
    private val header = Regex("^(\\w+) provider:$")
    private val identity = Regex("^identity=(\\d+)/([A-Za-z0-9_.]+)(?:\\[[^]]*])?$")

    /** JSON array of {"name","uid","pkg"}, one per provider whose identity the dump names. */
    fun parse(dump: String): JSONArray {
        val section = dump.substringAfter("Location Providers:", missingDelimiterValue = "")
        val found = JSONArray()
        var name: String? = null
        for (raw in section.lineSequence()) {
            val line = DumpText.normalize(raw)
            // The providers are listed first; the history and other managers follow at the top level.
            if (raw.isNotBlank() && !raw.startsWith("    ")) break
            val opened = header.find(line)
            if (opened != null) { name = opened.groupValues[1]; continue }
            val match = identity.find(line) ?: continue
            val provider = name ?: continue
            found.put(JSONObject().put("name", provider).put("uid", match.groupValues[1].toInt()).put("pkg", match.groupValues[2]))
            name = null
        }
        return found
    }
}
