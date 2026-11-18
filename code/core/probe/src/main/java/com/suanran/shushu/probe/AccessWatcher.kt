// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import android.app.AppOpsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Receives the access events AppOps already reports to watchers. Repeated identical accesses
 * within [WINDOW_MS] are folded into one record with a count, so a chatty caller costs one line
 * a minute. Nothing here changes how the system or the caller behaves.
 */
internal class AccessWatcher(context: Context, private val spool: EventSpool, private val states: UidStates) {
    private val thread = HandlerThread("access-watch").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private val buckets = LinkedHashMap<String, Bucket>()
    private var listeners: List<Any> = emptyList()

    private class Bucket(val event: JSONObject, val first: Long) {
        var last = first
        var count = 1
    }

    /** Replaces the watched operations; unknown names on this Android version are skipped. */
    fun watch(ops: List<String>) = handler.post {
        stop()
        val supported = ops.filter { op -> runCatching { opCode(op) >= 0 }.getOrDefault(false) }
        if (supported.isEmpty()) return@post
        listeners = listOfNotNull(
            runCatching { watchNoted(supported) }.onFailure { ProbeMain.log("noted watch failed: ${it.javaClass.simpleName}") }.getOrNull(),
            runCatching { watchActive(supported) }.onFailure { ProbeMain.log("active watch failed: ${it.javaClass.simpleName}") }.getOrNull()
        )
        ProbeMain.log("watching ${supported.size} operations")
    }

    private fun stop() {
        flush(force = true)
        listeners.forEach { listener ->
            runCatching {
                val iface = listener.javaClass.interfaces.first()
                val name = if (iface.simpleName == "OnOpNotedListener") "stopWatchingNoted" else "stopWatchingActive"
                AppOpsManager::class.java.getMethod(name, iface).invoke(appOps, listener)
            }
        }
        listeners = emptyList()
    }

    private fun watchNoted(ops: List<String>): Any {
        val type = Class.forName("android.app.AppOpsManager\$OnOpNotedListener")
        val listener = proxy(type) { args ->
            // Android 11: (code, uid, package, flags, result)
            // Android 12+: (op, uid, package, attributionTag, flags, result)
            // Android 15+: (op, uid, package, attributionTag, virtualDeviceId, flags, result)
            // flags and result are always the last two arguments.
            noted(
                op = args[0] as? String ?: opName(args[0] as Int),
                uid = args[1] as Int,
                pkg = args[2] as String?,
                attribution = if (args[0] is String) args[3] as String? else null,
                flags = args[args.size - 2] as Int,
                result = args[args.size - 1] as Int
            )
        }
        val start = AppOpsManager::class.java.methods.first {
            it.name == "startWatchingNoted" && it.parameterTypes.size == 2 && it.parameterTypes[1] == type
        }
        val opsArgument: Any = if (start.parameterTypes[0] == IntArray::class.java) ops.map(::opCode).toIntArray() else ops.toTypedArray()
        start.invoke(appOps, opsArgument, listener)
        return listener
    }

    private fun watchActive(ops: List<String>): Any {
        val type = AppOpsManager.OnOpActiveChangedListener::class.java
        val listener = proxy(type) { args ->
            val active = args.firstOrNull { it is Boolean } as Boolean? ?: return@proxy
            val attribution = args.getOrNull(3) as? String
            val t = System.currentTimeMillis()
            handler.post {
                val op = args[0] as String
                val uid = args[1] as Int
                val pkg = args[2] as String?
                record(JSONObject()
                    .put("kind", "active")
                    .put("t", t)
                    .put("op", op)
                    .put("uid", uid)
                    .put("pkg", pkg)
                    .put("attr", attribution)
                    .put("active", active)
                    // A start carries the state AppOps recorded; an end, the state at the time it ended.
                    .put("state", if (active) states.accessState(op, uid, pkg, t) else states.stateAt(uid, t))
                    .put("screen", states.screenAt(t)))
            }
        }
        AppOpsManager::class.java.getMethod("startWatchingActive", Array<String>::class.java, Executor::class.java, type)
            .invoke(appOps, ops.toTypedArray(), executor, listener)
        return listener
    }

    private fun noted(op: String, uid: Int, pkg: String?, attribution: String?, flags: Int, result: Int) = handler.post {
        val now = System.currentTimeMillis()
        val key = "$op|$uid|$pkg|$attribution|$flags|$result"
        val bucket = buckets[key]
        if (bucket != null) {
            bucket.last = now
            bucket.count++
            return@post
        }
        buckets[key] = Bucket(JSONObject()
            .put("kind", "access")
            .put("op", op)
            .put("uid", uid)
            .put("pkg", pkg)
            .put("attr", attribution)
            .put("flags", flags)
            .put("result", result)
            .put("state", states.accessState(op, uid, pkg, now))
            .put("screen", states.screenAt(now)), now)
        if (buckets.size == 1) handler.postDelayed({ flush(force = false) }, WINDOW_MS)
    }

    private fun flush(force: Boolean) {
        val now = System.currentTimeMillis()
        val iterator = buckets.values.iterator()
        while (iterator.hasNext()) {
            val bucket = iterator.next()
            if (!force && now - bucket.first < WINDOW_MS) continue
            record(bucket.event.put("t", bucket.first).put("end", bucket.last).put("count", bucket.count))
            iterator.remove()
        }
        if (buckets.isNotEmpty()) handler.postDelayed({ flush(force = false) }, WINDOW_MS / 4)
    }

    private fun record(event: JSONObject) = runCatching { spool.append(event) }
        .onFailure { ProbeMain.log("spool write failed: ${it.javaClass.simpleName}") }

    private fun opCode(op: String): Int =
        AppOpsManager::class.java.getMethod("strOpToOp", String::class.java).invoke(null, op) as Int

    private fun opName(code: Int): String =
        AppOpsManager::class.java.getMethod("opToPublicName", Int::class.javaPrimitiveType).invoke(null, code) as String

    private fun proxy(type: Class<*>, onCall: (Array<Any?>) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            when (method.name) {
                "equals" -> self === args?.get(0)
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "JiumiWatcher"
                else -> {
                    if (method.returnType == Void.TYPE && args != null) {
                        runCatching { onCall(args) }.onFailure { ProbeMain.log("watch callback failed: ${it.javaClass.simpleName}") }
                    }
                    null
                }
            }
        }

    private companion object {
        const val WINDOW_MS = 60_000L
    }
}
