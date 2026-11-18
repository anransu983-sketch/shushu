// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** RFC 4180 CSV: fields with a comma, quote or line break are quoted, quotes doubled; null is empty. */
object Csv {
    fun row(values: List<Any?>): String = values.joinToString(",") { field(it) } + "\r\n"

    fun field(value: Any?): String {
        val text = value?.toString() ?: return ""
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }

    /** Local time with its offset, readable by people and spreadsheets alike ("2026-11-11T09:20:15+08:00"). */
    fun time(t: Long?, zone: ZoneId = ZoneId.systemDefault()): String? =
        t?.takeIf { it > 0 }?.let { DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(it).atZone(zone).withNano(0)) }
}
