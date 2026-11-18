// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import java.util.Locale

/**
 * What the AppOps dumps of every maker have in common, read leniently: the layout is AOSP's, but
 * makers add operations of their own (Xiaomi prints "MIUIOP(10036)"), releases add levels
 * ("Device:"), and a phone set to a language with other digits prints its times in them.
 */
internal object DumpText {
    private val platformOp = Regex("^[A-Z][A-Z0-9_]*$")
    private val makerOp = Regex("^([A-Za-z][A-Za-z0-9_]*)\\((\\d+)\\)$")

    /** [line] trimmed, with every decimal digit written as 0-9. */
    fun normalize(line: String): String {
        val trimmed = line.trim()
        if (trimmed.all { it.code < 128 }) return trimmed
        return buildString(trimmed.length) {
            for (c in trimmed) append(if (Character.isDigit(c) && c !in '0'..'9') ('0' + Character.digit(c, 10)) else c)
        }
    }

    /**
     * The operation a header line names: "android:fine_location" for the platform's, "vendor:MIUIOP(10036)"
     * for a maker's own (whose meaning is not published), null for anything that is not an operation
     * header. A header may end in ":" and carry a mode, "COARSE_LOCATION (allow):".
     */
    fun operation(header: String): String? {
        val name = header.removeSuffix(":").substringBefore(" (").trim()
        if (platformOp.matches(name)) return "android:" + name.lowercase(Locale.US)
        if (makerOp.matches(name)) return VENDOR_PREFIX + name
        return null
    }

    /**
     * A process state as the report names them. AppOps prints pers, top, fgsvcl (older releases:
     * a foreground service with location), fgsvc, fg, bg and cch.
     */
    fun state(name: String): String = when (name) {
        "fgsvcl" -> "fgsvc"
        else -> name
    }

    /** "1000", "10084" or "u0a84" (user 0, app 84: uid 10084); isolated processes ("u0i5") and anything else are -1. */
    fun uid(text: String): Int {
        val t = text.removeSuffix(":").substringBefore(' ').trim()
        t.toIntOrNull()?.let { return it }
        val m = Regex("^u(\\d+)a(\\d+)$").find(t) ?: return -1
        return m.groupValues[1].toInt() * 100_000 + 10_000 + m.groupValues[2].toInt()
    }

    const val VENDOR_PREFIX = "vendor:"
}
