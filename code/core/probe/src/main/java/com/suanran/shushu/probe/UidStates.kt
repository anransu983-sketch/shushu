// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import java.lang.reflect.Proxy

/**
 * Process importance and screen state of a uid at the moment of an event, in the vocabulary of the
 * AppOps history ("pers", "top", "fgsvc", "fg", "bg", "cch", "gone").
 *
 * Events are judged at their own time: [start] seeds a [StateHistory] and keeps it current from the
 * system's own uid importance changes and the screen state, so a call traced a while ago is not
 * judged by where the app is now. Permission accesses take the state AppOps itself recorded for
 * them ([accessState]), the same one the system's privacy record shows.
 *
 * getRunningAppProcesses is used rather than getUidImportance: the latter is itself recorded by
 * AppOps as a usage-stats access, so the monitor would show up in its own record.
 */
internal class UidStates(context: Context) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val history = StateHistory()
    private var importanceByUid: Map<Int, Int> = emptyMap()
    private var importanceAt = 0L
    /** The history is kept current; without it, events fall back to the state when they are handled. */
    @Volatile private var live = false
    private val listeners = ArrayList<Any>()

    val screenOn: Boolean get() = power.isInteractive

    /** Starts keeping the history; if the system refuses the listeners, [stateAt] falls back to [state]. */
    fun start() {
        val now = System.currentTimeMillis()
        val running = runCatching {
            activityManager.runningAppProcesses.orEmpty().groupBy { it.uid }.mapValues { (_, l) -> name(l.minOf { it.importance }) }
        }.getOrDefault(emptyMap())
        history.begin(now, running, screenOn)
        live = runCatching { CUTPOINTS.forEach { listeners += listen(it) } }
            .onFailure { ProbeMain.log("uid importance listener failed: ${it.javaClass.simpleName}") }.isSuccess
        val thread = HandlerThread("screen-state").apply { start() }
        val handler = Handler(thread.looper)
        handler.post(object : Runnable {
            override fun run() {
                history.recordScreen(System.currentTimeMillis(), screenOn)
                handler.postDelayed(this, SCREEN_POLL_MS)
            }
        })
    }

    /** The uid's state at [t], from the history when it covers [t]. */
    fun stateAt(uid: Int, t: Long): String = (if (live) history.stateAt(uid, t) else null) ?: state(uid)

    fun screenAt(t: Long): Boolean = history.screenAt(t) ?: screenOn

    /**
     * The state AppOps recorded with the latest access of [op] by [uid]/[pkg], if that access lies
     * within [MATCH_MS] of [t]; otherwise the state from the history.
     */
    fun accessState(op: String, uid: Int, pkg: String?, t: Long): String =
        pkg?.let { runCatching { recordedState(op, uid, it, t) }.getOrNull() } ?: stateAt(uid, t)

    private fun recordedState(op: String, uid: Int, pkg: String, t: Long): String? {
        val forPackage = AppOpsManager::class.java.getMethod("getOpsForPackage", Int::class.java, String::class.java, Array<String>::class.java)
        val packages = forPackage.invoke(appOps, uid, pkg, arrayOf(op)) as? List<*> ?: return null
        val entry = packages.firstNotNullOfOrNull { p -> (p!!.javaClass.getMethod("getOps").invoke(p) as List<*>).firstOrNull() } ?: return null
        val lastAccess = entry.javaClass.getMethod("getLastAccessTime", Int::class.java, Int::class.java, Int::class.java)
        val (uidState, time) = UID_STATES.map { (code, _) -> code to (lastAccess.invoke(entry, code, code, OP_FLAGS_ALL) as Long) }
            .maxBy { it.second }
        if (time <= 0 || kotlin.math.abs(time - t) > MATCH_MS) return null
        return UID_STATES.getValue(uidState)
    }

    private fun listen(cutpoint: Int): Any {
        val type = Class.forName("android.app.ActivityManager\$OnUidImportanceListener")
        val listener = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "onUidImportance" -> history.record(args[0] as Int, System.currentTimeMillis(), name(args[1] as Int))
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "UidImportanceListener($cutpoint)"
                else -> null
            }
        }
        ActivityManager::class.java.getMethod("addOnUidImportanceListener", type, Int::class.java)
            .invoke(activityManager, listener, cutpoint)
        return listener
    }

    /** The state now, from a process list kept for [CACHE_MS]. */
    @Synchronized
    fun state(uid: Int): String {
        val now = System.currentTimeMillis()
        // A process that started after the cached list was taken is looked up once more.
        if (now - importanceAt > CACHE_MS || uid !in importanceByUid) {
            importanceByUid = runCatching {
                activityManager.runningAppProcesses.orEmpty()
                    .groupBy { it.uid }.mapValues { (_, list) -> list.minOf { it.importance } }
            }.getOrDefault(emptyMap())
            importanceAt = now
        }
        return importanceByUid[uid]?.let(::name) ?: StateHistory.GONE
    }

    private fun name(importance: Int): String = when {
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "top"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "fgsvc"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "fg"
        importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "bg"
        importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "cch"
        else -> StateHistory.GONE
    }

    private companion object {
        const val CACHE_MS = 2_000L
        const val SCREEN_POLL_MS = 1_000L
        /** How far apart an access event and the access AppOps recorded may be to be the same one. */
        const val MATCH_MS = 10_000L
        /** AppOpsManager.OP_FLAGS_ALL: self, proxy and proxied accesses alike. */
        const val OP_FLAGS_ALL = 0x1F
        /** Importance boundaries between the states [name] tells apart; one listener each. */
        val CUTPOINTS = listOf(
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE,
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED - 1,
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE - 1
        )
        /** AppOpsManager.UID_STATE_* codes and their names in the AppOps history. */
        val UID_STATES = linkedMapOf(
            100 to "pers", 200 to "top", 300 to "fgsvc", 400 to "fgsvc", 500 to "fg", 600 to "bg", 700 to "cch"
        )
    }
}
