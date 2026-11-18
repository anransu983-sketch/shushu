// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Append-only record of what the monitor observed, kept until the app has read it.
 *
 * Events go into segment files named after their first sequence number. Segments the app has read
 * are deleted on its next read, so the spool only grows while the app is not opened. Beyond
 * [limitBytes] (0: no limit), or when free space falls below [MIN_FREE_BYTES], the oldest segment
 * is dropped; the app sees the gap in sequence numbers and reports the loss.
 */
internal class EventSpool(private val directory: File, private val segmentBytes: Long = SEGMENT_BYTES) {
    @Volatile var limitBytes = DEFAULT_LIMIT_BYTES

    private var seq: Long
    private var current: File

    init {
        directory.mkdirs()
        val existing = segments()
        seq = existing.lastOrNull()?.let(::lastSequenceIn) ?: 0L
        current = existing.lastOrNull() ?: segmentFile(seq + 1)
    }

    @Synchronized
    fun append(event: JSONObject) {
        event.put("seq", ++seq)
        if (current.length() >= segmentBytes) current = segmentFile(seq)
        current.appendText(event.toString() + "\n")
        trim()
    }

    /**
     * Events after [after], oldest first, at most [max]. [after] is what the app has stored, so
     * segments that end at or before it are deleted. "first" is the oldest sequence still kept.
     */
    @Synchronized
    fun read(after: Long, max: Int): JSONObject {
        val all = segments()
        all.zipWithNext().forEach { (segment, next) -> if (firstSequence(next) - 1 <= after) segment.delete() }
        val events = JSONArray()
        var more = false
        for (file in segments()) {
            file.useLines { lines ->
                for (line in lines) {
                    val event = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (event.optLong("seq") <= after) continue
                    if (events.length() >= max) { more = true; break }
                    events.put(event)
                }
            }
            if (more) break
        }
        val first = segments().firstOrNull()?.let(::firstSequence) ?: (seq + 1)
        return JSONObject().put("events", events).put("last", seq).put("first", first).put("more", more)
    }

    @Synchronized
    fun sizeBytes(): Long = segments().sumOf { it.length() }

    /** Drops the oldest segments while over the limit or short of space; the current one stays. */
    private fun trim() {
        var all = segments()
        while (all.size > 1) {
            val over = limitBytes > 0 && all.sumOf { it.length() } > limitBytes
            val short = directory.usableSpace < MIN_FREE_BYTES
            if (!over && !short) break
            all.first().delete()
            all = all.drop(1)
        }
    }

    private fun segments(): List<File> =
        directory.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }.orEmpty()
            .sortedBy(::firstSequence)

    private fun segmentFile(firstSeq: Long) = File(directory, "$PREFIX$firstSeq$SUFFIX")

    private fun firstSequence(file: File): Long = file.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull() ?: 0L

    private fun lastSequenceIn(file: File): Long? =
        file.useLines { lines -> lines.lastOrNull { it.isNotBlank() } }?.let { runCatching { JSONObject(it).getLong("seq") }.getOrNull() }
            ?: (firstSequence(file) - 1)

    companion object {
        private const val PREFIX = "events-"
        private const val SUFFIX = ".jsonl"
        const val SEGMENT_BYTES = 8L * 1024 * 1024
        const val DEFAULT_LIMIT_BYTES = 256L * 1024 * 1024
        /** Whatever the limit, the monitor never fills the phone. */
        const val MIN_FREE_BYTES = 512L * 1024 * 1024
    }
}
