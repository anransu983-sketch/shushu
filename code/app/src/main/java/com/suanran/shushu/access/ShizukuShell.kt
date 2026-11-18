// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.access

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * What Shizuku offers this app: whether it is installed and running, runs as the shell, and has let
 * this app use it. Adapted from Droidance (ybblab, Apache-2.0).
 */
data class ShizukuState(
    val installed: Boolean = false,
    val running: Boolean = false,
    val supportedVersion: Boolean = true,
    /** Shizuku started over ADB runs as the shell; started with root it runs as root. */
    val shellIdentity: Boolean = false,
    val authorized: Boolean = false,
    /** The user chose "deny and don't ask again"; only Shizuku's own app can undo it. */
    val permissionBlocked: Boolean = false
) {
    val ready: Boolean get() = running && supportedVersion && shellIdentity && authorized
    val canRequestPermission: Boolean get() = running && supportedVersion && shellIdentity && !authorized && !permissionBlocked
}

class ShizukuShell(private val context: Context) {
    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()
    private val bindLock = Any()
    private val generation = AtomicLong()
    @Volatile private var service: IShizukuShellService? = null
    @Volatile private var connection: ServiceConnection? = null
    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context, ShizukuShellService::class.java))
            .tag("jiumi-shell").processNameSuffix("shizuku_shell")
            // A new APK gets a new helper process, never the old one's.
            .version(context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.hashCode())
            .daemon(false)
    }

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { refresh() }
            Shizuku.addBinderDeadListener {
                generation.incrementAndGet()
                service = null
                connection = null
                refresh()
            }
        }
    }

    fun refresh(): ShizukuState {
        val installed = runCatching { context.packageManager.getPackageInfo(PACKAGE_NAME, 0) }.isSuccess
        val next = runCatching {
            if (!Shizuku.pingBinder()) ShizukuState(installed = installed)
            else {
                val supported = !Shizuku.isPreV11() && Shizuku.getVersion() >= MIN_VERSION
                ShizukuState(installed = installed, running = true, supportedVersion = supported,
                    shellIdentity = Shizuku.getUid() == SHELL_UID,
                    authorized = supported && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED,
                    permissionBlocked = supported && Shizuku.shouldShowRequestPermissionRationale())
            }
        }.getOrElse { ShizukuState(installed = installed) }
        _state.value = next
        return next
    }

    /** Shizuku's own dialog asking the user to let this app use it; only on an explicit action. */
    suspend fun requestAuthorization(): Boolean {
        val current = refresh()
        if (current.ready) return true
        if (!current.canRequestPermission) return false
        return suspendCancellableCoroutine { continuation ->
            lateinit var listener: Shizuku.OnRequestPermissionResultListener
            listener = Shizuku.OnRequestPermissionResultListener { code, result ->
                if (code == REQUEST_CODE) {
                    Shizuku.removeRequestPermissionResultListener(listener)
                    refresh()
                    if (continuation.isActive) continuation.resume(result == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            try { Shizuku.requestPermission(REQUEST_CODE) } catch (_: Exception) {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    /** Runs [command] as the shell through Shizuku; output, or a line starting "[SHIZUKU_" when it could not. */
    fun shell(command: String, timeoutMs: Long): String {
        if (!refresh().ready) return "[SHIZUKU_UNAVAILABLE]"
        return try {
            val remote = bind() ?: return "[SHIZUKU_SERVICE_UNAVAILABLE]"
            remote.execute(command, timeoutMs)
        } catch (error: Exception) {
            release()
            if (error is InterruptedException) Thread.currentThread().interrupt()
            "[SHIZUKU_ERROR] ${error.javaClass.simpleName}"
        }
    }

    private fun bind(): IShizukuShellService? = synchronized(bindLock) {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return@synchronized it }
        val attempt = generation.incrementAndGet()
        val latch = CountDownLatch(1)
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (generation.get() == attempt && binder != null) service = IShizukuShellService.Stub.asInterface(binder)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (generation.get() == attempt) service = null
                latch.countDown()
            }
        }
        connection = callback
        Shizuku.bindUserService(serviceArgs, callback)
        if (!latch.await(BIND_TIMEOUT_S, TimeUnit.SECONDS)) { release(); return@synchronized null }
        service?.takeIf { it.asBinder().isBinderAlive && it.uid == SHELL_UID }.also { if (it == null) release() }
    }

    /** Ends the helper process; the monitor it started runs on by itself. */
    fun release() {
        generation.incrementAndGet()
        service = null
        val callback = connection
        connection = null
        if (callback != null) {
            // Detach the callback first, so the old process's late death cannot clear a new binding.
            runCatching { Shizuku.unbindUserService(serviceArgs, callback, false) }
            runCatching { Shizuku.unbindUserService(serviceArgs, null, true) }
        }
    }

    fun openManager(): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun openDownload(): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    companion object {
        const val PACKAGE_NAME = "moe.shizuku.privileged.api"
        const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
        private const val SHELL_UID = 2000
        private const val MIN_VERSION = 12
        private const val REQUEST_CODE = 4096
        private const val BIND_TIMEOUT_S = 10L
    }
}
