// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

import com.suanran.shushu.probe.IProbeService
import org.json.JSONArray
import org.json.JSONObject

/** Copies the monitor's spool and the system access history into the local store. */
class JiumiSync(private val store: JiumiStore, private val rules: AccessRules) {

    /**
     * Returns how many new events it stored. Now and then it also reads the system's own records,
     * which reach back before monitoring began: the per-access record (about a week, a few kinds of
     * data, with times) and the long-term counts (every kind of data, per day).
     */
    fun sync(service: IProbeService, now: Long = System.currentTimeMillis()): Int {
        val stored = pullEvents(service)
        if (now - store.meta(KEY_DISCRETE_READ_AT) >= DISCRETE_EVERY_MS) {
            runCatching { pullDiscrete(service) }.onFailure { android.util.Log.w(TAG, "system access record not read", it) }
            store.setMeta(store.writableDatabase, KEY_DISCRETE_READ_AT, now)
        }
        if (now - store.meta(KEY_COUNTS_READ_AT) >= COUNTS_EVERY_MS) {
            runCatching { pullCounts(service, now) }.onFailure { android.util.Log.w(TAG, "system access counts not read", it) }
            store.setMeta(store.writableDatabase, KEY_COUNTS_READ_AT, now)
        }
        return stored
    }

    /** New entries of the system's per-access record; equal times at a page boundary are ignored by the store. */
    private fun pullDiscrete(service: IProbeService) {
        var from = store.meta(KEY_DISCRETE_FROM)
        while (true) {
            val page = JSONArray(service.readDiscreteHistory(from, PAGE_HISTORY))
            if (page.length() == 0) break
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                for (i in 0 until page.length()) {
                    val e = page.getJSONObject(i)
                    if (rules.categoryOf(e.getString("op")) == null) continue
                    store.insertAccess(db, JiumiStore.Access(
                        t = e.getLong("t"), end = e.getLong("end"), op = e.getString("op"), uid = e.getInt("uid"),
                        pkg = e.str("pkg"), attr = e.str("attr"), count = 1, state = e.str("state"), screen = null,
                        // The record holds what went through; refusals are not in it.
                        result = 0, proxied = e.optString("via").endsWith("d"), source = "system"
                    ))
                }
                val last = page.getJSONObject(page.length() - 1).getLong("t")
                store.setMeta(db, KEY_DISCRETE_FROM, last)
                db.setTransactionSuccessful()
                if (page.length() < PAGE_HISTORY || last == from) break
                from = last
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * The system's long-term counts for the last [COUNT_DAYS] days. Its slots are merged into coarser
     * ones as they age, so the whole stretch is read again and replaces what was read before.
     */
    private fun pullCounts(service: IProbeService, now: Long) {
        val since = now - COUNT_DAYS * DAY_MS
        val counts = ArrayList<JiumiStore.OpCount>()
        var skip = 0
        while (true) {
            val page = JSONArray(service.readHistoricalOps(since, skip, PAGE_SLOTS))
            for (i in 0 until page.length()) {
                val slot = page.getJSONObject(i)
                val ops = slot.getJSONArray("ops")
                for (j in 0 until ops.length()) {
                    val o = ops.getJSONObject(j)
                    // A maker's own operations are kept too: their meaning is not published, but that a
                    // component used them, and when the user was not using it, is still worth showing.
                    val op = o.getString("op")
                    if (rules.categoryOf(op) == null && !SystemCount.isVendor(op)) continue
                    counts += JiumiStore.OpCount(slot.getLong("from"), slot.getLong("to"), o.getInt("uid"), o.getString("pkg"),
                        o.getString("op"), o.getLong("fg"), o.getLong("bg"), o.getLong("fgRejected"), o.getLong("bgRejected"),
                        o.optLong("durationMs"))
                }
            }
            if (page.length() < PAGE_SLOTS) break
            skip += page.length()
        }
        store.replaceOpCounts(counts)
    }

    private fun pullEvents(service: IProbeService): Int {
        var stored = 0
        var after = store.meta(KEY_EVENT_SEQ)
        while (true) {
            val page = JSONObject(service.readEvents(after, PAGE))
            // A monitor whose spool was cleared restarts its numbering; read it from the start.
            if (page.getLong("last") < after) { after = 0; continue }
            val events = page.getJSONArray("events")
            if (events.length() == 0) break
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                // Events the monitor dropped before they could be read (spool limit or low storage).
                val first = page.optLong("first", after + 1)
                if (after > 0 && first > after + 1) {
                    store.insertMonitorEvent(db, JiumiStore.MonitorEvent(events.getJSONObject(0).optLong("t"), "lost", (first - after - 1).toString()))
                }
                for (i in 0 until events.length()) apply(db, events.getJSONObject(i))
                after = events.getJSONObject(events.length() - 1).getLong("seq")
                store.setMeta(db, KEY_EVENT_SEQ, after)
                db.setTransactionSuccessful()
                stored += events.length()
            } finally {
                db.endTransaction()
            }
            if (!page.getBoolean("more")) break
        }
        return stored
    }

    private fun apply(db: android.database.sqlite.SQLiteDatabase, e: JSONObject) {
        when (e.getString("kind")) {
            "access" -> store.insertAccess(db, JiumiStore.Access(
                t = e.getLong("t"), end = e.optLong("end", e.getLong("t")), op = e.getString("op"),
                uid = e.getInt("uid"), pkg = e.str("pkg"), attr = e.str("attr"), count = e.optInt("count", 1),
                state = e.str("state"), screen = if (e.has("screen")) e.getBoolean("screen") else null,
                result = e.optInt("result"), proxied = e.optInt("flags") and PROXIED_FLAGS != 0, source = "live"
            ))
            "active" -> if (e.getBoolean("active")) {
                store.insertAccess(db, JiumiStore.Access(
                    t = e.getLong("t"), end = e.getLong("t"), op = e.getString("op"), uid = e.getInt("uid"),
                    pkg = e.str("pkg"), attr = e.str("attr"), count = 1, state = e.str("state"),
                    screen = if (e.has("screen")) e.getBoolean("screen") else null, result = 0, proxied = false,
                    source = "active"
                ))
            } else {
                store.closeSession(db, e.getString("op"), e.getInt("uid"), e.str("pkg"), e.str("attr"), e.getLong("t"))
            }
            "call" -> store.insertCall(db, JiumiStore.Call(
                t = e.getLong("t"), end = e.optLong("end", e.getLong("t")), uid = e.getInt("uid"), pid = e.optInt("pid", -1),
                process = e.str("process"), iface = e.getString("iface"), method = e.getString("method"),
                count = e.optInt("count", 1), state = e.str("state"), screen = if (e.has("screen")) e.getBoolean("screen") else null,
                thread = e.str("thread")
            ))
            "net" -> store.insertConnection(db, JiumiStore.Connection(
                t = e.getLong("t"), end = e.optLong("end", e.getLong("t")), uid = e.getInt("uid"), proto = e.getString("proto"),
                addr = e.getString("addr"), port = e.getInt("port"), count = e.optInt("count", 1), state = e.str("state"),
                screen = if (e.has("screen")) e.getBoolean("screen") else null,
                sent = if (e.has("sent")) e.getLong("sent") else null,
                received = if (e.has("received")) e.getLong("received") else null,
                sentUnattended = if (e.has("sentUnattended")) e.getLong("sentUnattended") else null
            ))
            "monitor" -> store.insertMonitorEvent(db, JiumiStore.MonitorEvent(e.getLong("t"), e.getString("state"), e.str("reason")))
            "proc_start" -> store.insertProcess(db, JiumiStore.ProcessStart(
                e.getLong("t"), e.getInt("pid"), e.getInt("uid"), e.getString("process"),
                e.optString("reason"), e.optString("component")
            ))
        }
    }

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

    private companion object {
        const val TAG = "JiumiSync"
        const val KEY_EVENT_SEQ = "event_seq"
        const val KEY_DISCRETE_FROM = "system_access_from"
        const val KEY_DISCRETE_READ_AT = "system_access_read_at"
        const val KEY_COUNTS_READ_AT = "system_counts_read_at"
        const val DISCRETE_EVERY_MS = 10 * 60_000L
        const val COUNTS_EVERY_MS = 30 * 60_000L
        const val COUNT_DAYS = 31
        const val DAY_MS = 24 * 3_600_000L
        const val PAGE_HISTORY = 2_000
        const val PAGE_SLOTS = 8
        const val PAGE = 500
        // AppOpsManager.OP_FLAG_TRUSTED_PROXIED | OP_FLAG_UNTRUSTED_PROXIED
        const val PROXIED_FLAGS = 8 or 16
    }
}
