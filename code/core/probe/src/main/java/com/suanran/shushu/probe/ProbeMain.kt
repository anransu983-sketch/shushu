// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/**
 * app_process entry point of the shell-identity monitor.
 *
 * Arguments: package name, protocol version, APK path. Exit code 0 tells the supervisor to stop
 * (intentional stop, duplicate instance, changed APK); any other exit lets it restart.
 */
object ProbeMain {
    /** One package lookup; short, so an updated app's new monitor does not wait long for the old one to go. */
    private const val PACKAGE_CHECK_INTERVAL_MS = 5_000L
    /** How long a new monitor waits for the previous one to release the lock, e.g. right after an update. */
    private const val LOCK_WAIT_MS = 10_000L
    /** How often the monitor notes that it is alive, so the app can tell covered time from gaps. */
    private const val HEARTBEAT_MS = 5 * 60_000L
    @Volatile private var spool: EventSpool? = null
    private val offerDelaysMs = longArrayOf(0, 700, 2_000, 5_000, 10_000)

    @Volatile private var lock: FileLock? = null
    /** Child processes are not ended by exitProcess; they are destroyed explicitly. */
    private val children = java.util.Collections.synchronizedSet(HashSet<java.lang.Process>())

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (Process.myUid() != ProbeProtocol.SHELL_UID) exit(0, "refusing to run as uid ${Process.myUid()}")
            if (args.size != 3 || args[1].toIntOrNull() != ProbeProtocol.VERSION) exit(0, "unexpected arguments")
            val packageName = args[0]
            val apkPath = args[2]
            if (!acquireLock(packageName)) exit(0, "another monitor is already running")
            // A signal ends app_process without cleanup and orphans its watcher; the supervisor's
            // restart (or the next start) removes it. Only this monitor's own command line matches.
            removeOrphanedWatchers()
            Looper.prepareMainLooper()
            val context = ShellContext.create()
            val identity = PackageIdentity.capture(context, packageName)
            if (identity.sourcePath != apkPath) exit(0, "APK no longer matches the installed package")
            val handler = Handler(Looper.getMainLooper())
            val dataDirectory = File(ProbeProtocol.dataDirectory(packageName))
            val spool = EventSpool(dataDirectory).also { this.spool = it }
            recordState("start", "launch")
            val states = UidStates(context).also { it.start() }
            val accessWatcher = AccessWatcher(context, spool, states)
            val connectionWatcher = ConnectionWatcher(spool, states).also { it.start() }
            val tracer = PerfettoTracer(packageName, spool, states, connectionWatcher,
                register = { children += it }, unregister = { children -= it })
            tracer.start()
            lateinit var service: ProbeService
            val offer = BinderOffer(context, packageName, identity, handler) { service }
            service = ProbeService(context, packageName, identity.uid, dataDirectory, spool, accessWatcher, tracer, connectionWatcher) {
                offer.offerUntilAttached()
            }
            service.restoreConfiguration()
            offer.offerUntilAttached()
            EventLogWatcher(spool, packageName, register = { children += it }, unregister = { children -= it }) {
                handler.post { if (!service.isAttached) offer.offerUntilAttached() }
            }.start()
            handler.postDelayed(object : Runnable {
                override fun run() {
                    recordState("alive", null)
                    handler.postDelayed(this, HEARTBEAT_MS)
                }
            }, HEARTBEAT_MS)
            handler.postDelayed(object : Runnable {
                override fun run() {
                    if (identity.state(context) != PackageIdentity.State.CURRENT) {
                        recordState("stop", "apk_changed")
                        exit(0, "APK changed or removed")
                    }
                    handler.postDelayed(this, PACKAGE_CHECK_INTERVAL_MS)
                }
            }, PACKAGE_CHECK_INTERVAL_MS)
            log("monitor started pid=${Process.myPid()}")
            Looper.loop()
        } catch (error: Throwable) {
            log("monitor failed: ${error.javaClass.simpleName}: ${error.message}")
            error.printStackTrace()
            exit(1, "crash")
        }
    }

    /** start, alive or stop; the app derives monitored time and interruptions from these. */
    internal fun recordState(state: String, reason: String?) {
        runCatching {
            spool?.append(org.json.JSONObject().put("kind", "monitor").put("state", state).put("reason", reason)
                .put("t", System.currentTimeMillis()))
        }
    }

    internal fun exit(code: Int, reason: String): Nothing {
        log("monitor exit code=$code reason=$reason")
        destroyChildren()
        runCatching { lock?.release() }
        exitProcess(code)
    }

    private fun destroyChildren() {
        synchronized(children) { children.forEach { runCatching { it.destroy() } } }
    }

    private fun removeOrphanedWatchers() {
        File("/proc").listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().forEach { dir ->
            runCatching {
                val args = File(dir, "cmdline").readText().split('\u0000').filter { it.isNotEmpty() }
                val parent = File(dir, "stat").readText().substringAfterLast(')').trim().split(' ')[1]
                if (parent == "1" && args == EventLogWatcher.COMMAND) Process.killProcess(dir.name.toInt())
            }
        }
    }

    internal fun log(message: String) {
        println("${System.currentTimeMillis() / 1000} $message")
    }

    private fun acquireLock(packageName: String): Boolean {
        val file = File(ProbeProtocol.dataDirectory(packageName), "probe.lock")
        file.parentFile?.mkdirs()
        val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val deadline = System.currentTimeMillis() + LOCK_WAIT_MS
        while (true) {
            lock = runCatching { channel.tryLock() }.getOrNull()
            if (lock != null || System.currentTimeMillis() >= deadline) break
            Thread.sleep(250)
        }
        if (lock == null) channel.close()
        return lock != null
    }

    /**
     * Delivers the monitor binder only to the app's dynamically registered receiver, so an offer
     * never starts the app process.
     */
    private class BinderOffer(
        private val context: Context,
        private val packageName: String,
        private val identity: PackageIdentity,
        private val handler: Handler,
        private val service: () -> ProbeService
    ) {
        private var round = 0

        fun offerUntilAttached() {
            val current = ++round
            offerDelaysMs.forEach { delay ->
                handler.postDelayed({ if (current == round && !service().isAttached) send() }, delay)
            }
        }

        private fun send() {
            val intent = Intent(ProbeProtocol.ACTION_READY)
                .setPackage(packageName)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                .putExtras(Bundle().apply {
                    putBinder(ProbeProtocol.EXTRA_BINDER, service())
                    putInt(ProbeProtocol.EXTRA_UID, Process.myUid())
                    putInt(ProbeProtocol.EXTRA_PROTOCOL, ProbeProtocol.VERSION)
                    putString(ProbeProtocol.EXTRA_SOURCE, identity.sourcePath)
                    putLong(ProbeProtocol.EXTRA_UPDATED_AT, identity.updatedAt)
                })
            runCatching { context.sendBroadcast(intent) }
                .onFailure { log("binder offer failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }
}

/** Identity of the APK this process was started from, including in-place updates. */
internal class PackageIdentity private constructor(
    val packageName: String,
    val sourcePath: String,
    val updatedAt: Long,
    val uid: Int
) {
    enum class State { CURRENT, CHANGED, UNAVAILABLE }

    fun state(context: Context): State = try {
        val installed = capture(context, packageName)
        if (installed.sourcePath == sourcePath && installed.updatedAt == updatedAt) State.CURRENT else State.CHANGED
    } catch (_: Exception) {
        State.UNAVAILABLE
    }

    companion object {
        fun capture(context: Context, packageName: String): PackageIdentity {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            val app = checkNotNull(info.applicationInfo) { "package source unavailable" }
            return PackageIdentity(packageName, app.sourceDir, info.lastUpdateTime, app.uid)
        }
    }
}
