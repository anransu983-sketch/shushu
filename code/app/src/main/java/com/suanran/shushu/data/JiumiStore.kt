// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local record of observed accesses and process starts. Nothing leaves the device.
 *
 * source: "live" (watched note) or "active" (watched start/stop session), captured by the monitor; or
 * "system", read from the system's own per-access record, which also covers time the monitor was not
 * running. ("history" marks rows of an earlier import, which reports leave out.)
 */
class JiumiStore(context: Context) : SQLiteOpenHelper(context, "shushu.db", null, VERSION) {

    data class Access(
        val t: Long, val end: Long, val op: String, val uid: Int, val pkg: String?, val attr: String?,
        val count: Int, val state: String?, val screen: Boolean?, val result: Int, val proxied: Boolean, val source: String,
        /** Not stored: the report found the app lacked the permission AppOps deferred to (see ReportBuilder). */
        val withoutPermission: Boolean = false
    ) {
        /** AppOpsManager.MODE_IGNORED or MODE_ERRORED; MODE_DEFAULT (3) defers to the permission and is not a denial. */
        val denied: Boolean get() = result == 1 || result == 2
    }

    /**
     * A traced call to a system-service method or content-provider operation; [process] and [thread]
     * are the caller's.
     */
    data class Call(
        val t: Long, val end: Long, val uid: Int, val pid: Int, val process: String?, val iface: String, val method: String,
        val count: Int, val state: String?, val screen: Boolean?, val thread: String? = null
    )

    /**
     * A remote destination a uid was seen talking to between [t] and [end], over [count] sockets.
     * [sent] and [received] are the bytes counted from packet metadata, null where it is unavailable;
     * [sentUnattended] is the part sent while the app was in the background or the screen was off.
     * An empty [addr] holds traffic of connections too short to be seen in the socket table.
     */
    data class Connection(
        val t: Long, val end: Long, val uid: Int, val proto: String, val addr: String, val port: Int,
        val count: Int, val state: String?, val screen: Boolean?, val sent: Long? = null, val received: Long? = null,
        val sentUnattended: Long? = null
    )

    data class ProcessStart(val t: Long, val pid: Int, val uid: Int, val process: String, val reason: String, val component: String) {
        val appKey get() = processStartKey(uid, process, component)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE access (
                t INTEGER NOT NULL, end_t INTEGER NOT NULL, op TEXT NOT NULL, uid INTEGER NOT NULL,
                pkg TEXT, attr TEXT, count INTEGER NOT NULL, state TEXT, screen INTEGER,
                result INTEGER NOT NULL, proxied INTEGER NOT NULL, source TEXT NOT NULL)"""
        )
        // NULLs are distinct in a plain UNIQUE constraint; compare missing package or tag as equal.
        db.execSQL("CREATE UNIQUE INDEX access_identity ON access (t, op, uid, IFNULL(pkg, ''), IFNULL(attr, ''), source)")
        db.execSQL("CREATE INDEX access_time ON access (t)")
        db.execSQL("CREATE INDEX access_uid ON access (uid, t)")
        db.execSQL(
            """CREATE TABLE process_start (
                t INTEGER NOT NULL, pid INTEGER NOT NULL, uid INTEGER NOT NULL, process TEXT NOT NULL,
                reason TEXT NOT NULL, component TEXT NOT NULL, UNIQUE (t, pid))"""
        )
        db.execSQL("CREATE INDEX process_uid ON process_start (uid, t)")
        db.execSQL(
            """CREATE TABLE call (
                t INTEGER NOT NULL, end_t INTEGER NOT NULL, uid INTEGER NOT NULL, pid INTEGER NOT NULL, process TEXT,
                iface TEXT NOT NULL, method TEXT NOT NULL, count INTEGER NOT NULL, state TEXT, screen INTEGER)"""
        )
        db.execSQL("CREATE UNIQUE INDEX call_identity ON call (t, uid, IFNULL(process, ''), iface, method)")
        db.execSQL("CREATE INDEX call_uid ON call (uid, t)")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        // A new store is built as version 1 and taken through every step, as an old one is.
        onUpgrade(db, 1, VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createConnectionTable(db)
        if (oldVersion < 3) addConnectionBytes(db)
        if (oldVersion < 4) addUnattendedBytes(db)
        if (oldVersion < 5) addCallThread(db)
        if (oldVersion < 6) createSessionTables(db)
        if (oldVersion < 7) createUserActionTable(db)
        if (oldVersion < 8) createLookupTable(db)
        if (oldVersion < 9) createOpHistoryTable(db)
    }

    /** The system's long-term access counts, one row per app, operation and time slot (see [OpCount]). */
    private fun createOpHistoryTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE op_history (
                span_from INTEGER NOT NULL, span_to INTEGER NOT NULL, uid INTEGER NOT NULL, pkg TEXT NOT NULL, op TEXT NOT NULL,
                fg INTEGER NOT NULL, bg INTEGER NOT NULL, fg_rejected INTEGER NOT NULL, bg_rejected INTEGER NOT NULL,
                duration INTEGER NOT NULL, UNIQUE (span_from, uid, pkg, op))"""
        )
    }

    /**
     * How often [pkg] used [op] between [from] and [to] by the system's count: in the foreground, in
     * the background, and refused in each. [from] to [to] is one of the system's time slots: a quarter
     * of an hour lately, up to days or weeks for older ones.
     */
    data class OpCount(
        val from: Long, val to: Long, val uid: Int, val pkg: String, val op: String,
        val fg: Long, val bg: Long, val fgRejected: Long, val bgRejected: Long, val durationMs: Long
    )

    /** Replaces every stored count: the system merges its slots as they age, so a new read supersedes the last. */
    fun replaceOpCounts(counts: List<OpCount>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("op_history", null, null)
            for (c in counts) db.insertWithOnConflict("op_history", null, ContentValues().apply {
                put("span_from", c.from); put("span_to", c.to); put("uid", c.uid); put("pkg", c.pkg); put("op", c.op)
                put("fg", c.fg); put("bg", c.bg); put("fg_rejected", c.fgRejected); put("bg_rejected", c.bgRejected)
                put("duration", c.durationMs)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Counts of slots whose middle falls within [from, to), optionally of one uid. */
    fun opCounts(from: Long, to: Long, uid: Int? = null): List<OpCount> {
        // An expression has no type to convert the bound text to, so the bounds are cast to compare as numbers.
        val middle = "(span_from + span_to) / 2"
        val range = "$middle >= CAST(? AS INTEGER) AND $middle < CAST(? AS INTEGER)"
        val where = if (uid == null) range else "$range AND uid = ?"
        val args = listOfNotNull(from.toString(), to.toString(), uid?.toString()).toTypedArray()
        return readableDatabase.rawQuery(
            "SELECT span_from, span_to, uid, pkg, op, fg, bg, fg_rejected, bg_rejected, duration FROM op_history WHERE $where", args
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(OpCount(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3), c.getString(4),
                    c.getLong(5), c.getLong(6), c.getLong(7), c.getLong(8), c.getLong(9)))
            }
        }
    }

    private fun createLookupTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE dns (
                t INTEGER NOT NULL, end_t INTEGER NOT NULL, uid INTEGER NOT NULL, domain TEXT NOT NULL, qtype INTEGER NOT NULL,
                answers TEXT NOT NULL, rcode INTEGER NOT NULL, count INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX dns_uid ON dns (uid, t)")
        db.execSQL("CREATE INDEX dns_time ON dns (t)")
    }

    /**
     * Lookups of one name of one type by [uid] between [t] and [end], [count] of them, as the domain
     * recorder saw them pass; [answers] are the addresses given, [rcode] the DNS result (0 found,
     * 3 no such name), -1 when no answer came.
     */
    data class Lookup(
        val t: Long, val end: Long, val uid: Int, val domain: String, val type: Int, val answers: List<String>,
        val rcode: Int, val count: Int
    )

    fun insertLookups(lookups: List<Lookup>) {
        if (lookups.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (l in lookups) db.insert("dns", null, ContentValues().apply {
                put("t", l.t); put("end_t", l.end); put("uid", l.uid); put("domain", l.domain); put("qtype", l.type)
                put("answers", l.answers.joinToString(",")); put("rcode", l.rcode); put("count", l.count)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun lookups(from: Long, to: Long = Long.MAX_VALUE, uid: Int? = null): List<Lookup> = readableDatabase.rawQuery(
        "SELECT t, end_t, uid, domain, qtype, answers, rcode, count FROM dns WHERE t >= ? AND t <= ?" +
            (if (uid == null) "" else " AND uid = ?") + " ORDER BY t",
        listOfNotNull(from.toString(), to.toString(), uid?.toString()).toTypedArray()
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Lookup(
                c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3), c.getInt(4),
                c.getString(5).split(',').filter { it.isNotEmpty() }, c.getInt(6), c.getInt(7)
            ))
        }
    }

    private fun createUserActionTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE user_action (t INTEGER NOT NULL, uid INTEGER NOT NULL, pkg TEXT NOT NULL, action TEXT NOT NULL)")
        db.execSQL("CREATE INDEX user_action_pkg ON user_action (pkg, t)")
    }

    /** Something the user did to an app from here: disable, enable, uninstall, remove or restore it. */
    data class UserAction(val t: Long, val uid: Int, val pkg: String, val action: String)

    fun insertUserAction(a: UserAction) {
        writableDatabase.insert("user_action", null, ContentValues().apply {
            put("t", a.t); put("uid", a.uid); put("pkg", a.pkg); put("action", a.action)
        })
    }

    fun userActions(from: Long, to: Long, pkg: String? = null): List<UserAction> = readableDatabase.rawQuery(
        "SELECT t, uid, pkg, action FROM user_action WHERE t >= ? AND t <= ?" + (if (pkg != null) " AND pkg = ?" else "") + " ORDER BY t",
        listOfNotNull(from.toString(), to.toString(), pkg).toTypedArray()
    ).use { c -> buildList { while (c.moveToNext()) add(UserAction(c.getLong(0), c.getInt(1), c.getString(2), c.getString(3))) } }

    private fun createSessionTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE session (id INTEGER PRIMARY KEY AUTOINCREMENT, start_t INTEGER NOT NULL, end_t INTEGER)")
        db.execSQL("CREATE TABLE monitor_event (t INTEGER NOT NULL, state TEXT NOT NULL, reason TEXT, UNIQUE (t, state))")
    }

    /**
     * A monitoring session: from the user starting the monitor to the user stopping it; [end] is
     * null while it runs. Restarts and interruptions in between stay within the session.
     */
    data class Session(val id: Long, val start: Long, val end: Long?)

    /** start, alive or stop from the monitor; lost marks spooled events dropped before the app read them. */
    data class MonitorEvent(val t: Long, val state: String, val reason: String?)

    fun startSession(t: Long): Long = writableDatabase.insert("session", null, ContentValues().apply { put("start_t", t) })

    fun endSession(id: Long, t: Long) {
        writableDatabase.update("session", ContentValues().apply { put("end_t", t) }, "id = ?", arrayOf(id.toString()))
    }

    fun sessions(): List<Session> = readableDatabase.rawQuery("SELECT id, start_t, end_t FROM session ORDER BY start_t DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Session(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2))) }
    }

    fun insertMonitorEvent(db: SQLiteDatabase, e: MonitorEvent) {
        db.insertWithOnConflict("monitor_event", null, ContentValues().apply {
            put("t", e.t); put("state", e.state); put("reason", e.reason)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun monitorEvents(from: Long, to: Long): List<MonitorEvent> = readableDatabase.rawQuery(
        "SELECT t, state, reason FROM monitor_event WHERE t >= ? AND t <= ? ORDER BY t", arrayOf(from.toString(), to.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(MonitorEvent(c.getLong(0), c.getString(1), c.getString(2))) } }

    private fun addCallThread(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE call ADD COLUMN thread TEXT")
        db.execSQL("DROP INDEX call_identity")
        db.execSQL("CREATE UNIQUE INDEX call_identity ON call (t, uid, IFNULL(process, ''), IFNULL(thread, ''), iface, method)")
    }

    private fun createConnectionTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE connection (
                t INTEGER NOT NULL, end_t INTEGER NOT NULL, uid INTEGER NOT NULL, proto TEXT NOT NULL, addr TEXT NOT NULL,
                port INTEGER NOT NULL, count INTEGER NOT NULL, state TEXT, screen INTEGER, UNIQUE (t, uid, proto, addr, port))"""
        )
        db.execSQL("CREATE INDEX connection_uid ON connection (uid, t)")
    }

    private fun addUnattendedBytes(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE connection ADD COLUMN sent_unattended INTEGER")
    }

    private fun addConnectionBytes(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE connection ADD COLUMN sent INTEGER")
        db.execSQL("ALTER TABLE connection ADD COLUMN received INTEGER")
    }

    fun insertConnection(db: SQLiteDatabase, c: Connection) {
        db.insertWithOnConflict("connection", null, ContentValues().apply {
            put("t", c.t); put("end_t", c.end); put("uid", c.uid); put("proto", c.proto); put("addr", c.addr)
            put("port", c.port); put("count", c.count); put("state", c.state); put("screen", c.screen?.let { if (it) 1 else 0 })
            put("sent", c.sent); put("received", c.received); put("sent_unattended", c.sentUnattended)
            // The monitor writes a connection again as it goes on, with the same start: the latest stands.
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun connections(from: Long, to: Long = Long.MAX_VALUE, uid: Int? = null): List<Connection> {
        val where = if (uid == null) "t >= ? AND t <= ?" else "t >= ? AND t <= ? AND uid = ?"
        val args = listOfNotNull(from.toString(), to.toString(), uid?.toString()).toTypedArray()
        return readableDatabase.rawQuery(
            "SELECT t, end_t, uid, proto, addr, port, count, state, screen, sent, received, sent_unattended FROM connection WHERE $where ORDER BY t", args
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Connection(
                    c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3), c.getString(4), c.getInt(5), c.getInt(6),
                    c.getString(7), if (c.isNull(8)) null else c.getInt(8) == 1,
                    if (c.isNull(9)) null else c.getLong(9), if (c.isNull(10)) null else c.getLong(10),
                    if (c.isNull(11)) null else c.getLong(11)
                ))
            }
        }
    }

    fun meta(key: String): Long = readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }

    fun setMeta(db: SQLiteDatabase, key: String, value: Long) {
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun insertAccess(db: SQLiteDatabase, a: Access) {
        db.insertWithOnConflict("access", null, ContentValues().apply {
            put("t", a.t); put("end_t", a.end); put("op", a.op); put("uid", a.uid); put("pkg", a.pkg); put("attr", a.attr)
            put("count", a.count); put("state", a.state); put("screen", a.screen?.let { if (it) 1 else 0 })
            put("result", a.result); put("proxied", if (a.proxied) 1 else 0); put("source", a.source)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Closes the newest open session of the same operation and caller. */
    fun closeSession(db: SQLiteDatabase, op: String, uid: Int, pkg: String?, attr: String?, end: Long) {
        db.execSQL(
            """UPDATE access SET end_t = ? WHERE rowid = (
                SELECT rowid FROM access WHERE source = 'active' AND op = ? AND uid = ? AND pkg IS ? AND attr IS ?
                AND end_t = t AND t <= ? ORDER BY t DESC LIMIT 1)""",
            arrayOf<Any?>(end, op, uid, pkg, attr, end)
        )
    }

    fun insertCall(db: SQLiteDatabase, c: Call) {
        db.insertWithOnConflict("call", null, ContentValues().apply {
            put("t", c.t); put("end_t", c.end); put("uid", c.uid); put("pid", c.pid); put("process", c.process)
            put("iface", c.iface); put("method", c.method); put("count", c.count); put("state", c.state); put("thread", c.thread)
            put("screen", c.screen?.let { if (it) 1 else 0 })
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun calls(from: Long, to: Long = Long.MAX_VALUE, uid: Int? = null): List<Call> {
        val where = if (uid == null) "t >= ? AND t <= ?" else "t >= ? AND t <= ? AND uid = ?"
        val args = listOfNotNull(from.toString(), to.toString(), uid?.toString()).toTypedArray()
        return readableDatabase.rawQuery(
            "SELECT t, end_t, uid, pid, process, iface, method, count, state, screen, thread FROM call WHERE $where ORDER BY t", args
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Call(
                    c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getString(4), c.getString(5), c.getString(6),
                    c.getInt(7), c.getString(8), if (c.isNull(9)) null else c.getInt(9) == 1, c.getString(10)
                ))
            }
        }
    }

    fun insertProcess(db: SQLiteDatabase, p: ProcessStart) {
        db.insertWithOnConflict("process_start", null, ContentValues().apply {
            put("t", p.t); put("pid", p.pid); put("uid", p.uid); put("process", p.process)
            put("reason", p.reason); put("component", p.component)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * Accesses in [from, to], of [uid] or of every uid. The system's record repeats what the monitor
     * saw while it ran; [SystemRecord] keeps one row per access. The monitor's rows that may cover
     * a record row near either end are read as well, then left out of the result.
     */
    fun accesses(from: Long, to: Long = Long.MAX_VALUE, uid: Int? = null): List<Access> {
        val (lo, hi) = SystemRecord.monitorWindow(from, to)
        val where = "t >= ? AND t <= ?" + if (uid == null) "" else " AND uid = ?"
        val args = listOfNotNull((lo - LONGEST_MONITOR_ROW_MS).toString(), hi.toString(), uid?.toString()).toTypedArray()
        val read = readableDatabase.rawQuery(
            "SELECT t, end_t, op, uid, pkg, attr, count, state, screen, result, proxied, source FROM access WHERE $where" +
                // Rows of an earlier import are left out.
                " AND source != 'history' ORDER BY t",
            args
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Access(
                    c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5), c.getInt(6),
                    c.getString(7), if (c.isNull(8)) null else c.getInt(8) == 1, c.getInt(9), c.getInt(10) == 1, c.getString(11)
                ))
            }
        }
        val monitor = read.filter { it.source != "system" && maxOf(it.t, it.end) >= lo }
        return SystemRecord.withoutRepeats(read.filter { it.t in from..to }, monitor)
    }

    /** Process starts in [from, to], of [uid] or of every uid. */
    fun processStarts(from: Long, to: Long, uid: Int? = null): List<ProcessStart> = readableDatabase.rawQuery(
        "SELECT t, pid, uid, process, reason, component FROM process_start WHERE t >= ? AND t <= ?" +
            (if (uid == null) "" else " AND uid = ?") + " ORDER BY t",
        listOfNotNull(from.toString(), to.toString(), uid?.toString()).toTypedArray()
    ).use { c ->
        buildList { while (c.moveToNext()) add(ProcessStart(c.getLong(0), c.getInt(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5))) }
    }

    fun processStartCounts(from: Long, to: Long): Map<AppKey, Int> = readableDatabase.rawQuery(
        "SELECT uid, process, component, COUNT(*) FROM process_start WHERE t >= ? AND t <= ? GROUP BY uid, process, component",
        arrayOf(from.toString(), to.toString())
    ).use { c ->
        buildMap {
            while (c.moveToNext()) {
                val uid = c.getInt(0)
                val app = processStartKey(uid, c.getString(1), c.getString(2))
                val uidOnly = AppKey(uid, null)
                val count = c.getInt(3)
                put(app, (get(app) ?: 0) + count)
                put(uidOnly, (get(uidOnly) ?: 0) + count)
            }
        }
    }

    private companion object {
        const val VERSION = 9
        /** How far before a range the monitor's rows are read for one that still covers its start. */
        const val LONGEST_MONITOR_ROW_MS = 24 * 60 * 60_000L
    }
}

private val componentPackageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")

/** A hosting component can identify a package even when it runs in a shared or custom process. */
private fun processStartKey(uid: Int, process: String, component: String): AppKey {
    val host = component.substringBefore('/', missingDelimiterValue = "").trimStart('{')
    val pkg = host.takeIf(componentPackageName::matches) ?: process.substringBefore(':')
    return AppKey(uid, pkg)
}
