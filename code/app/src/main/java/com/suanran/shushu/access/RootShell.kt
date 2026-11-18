// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.access

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Uses the root manager once per command and runs the command only after switching to shell. */
class RootShell(context: Context) {
    enum class State { UNCHECKED, CHECKING, UNAVAILABLE, SHELL_FAILED, READY }

    private val prefs = context.getSharedPreferences("shushu_settings", Context.MODE_PRIVATE)
    private val launcher = File(context.applicationInfo.nativeLibraryDir, "libjiumi_shell.so")
    private val readers = Executors.newCachedThreadPool { Thread(it, "root-shell").apply { isDaemon = true } }
    private val _state = MutableStateFlow(State.UNCHECKED)
    val state: StateFlow<State> = _state.asStateFlow()
    val ready: Boolean get() = state.value == State.READY
    /** A prior successful check allows an interrupted session to try to resume after an update. */
    val wasReady: Boolean get() = prefs.getBoolean(KEY_GRANTED, false)

    @Synchronized fun authorize() {
        _state.value = State.CHECKING
        runAsShell(":", AUTH_TIMEOUT_MS)
    }

    @Synchronized fun shell(command: String, timeoutMs: Long): Boolean = runAsShell(command, timeoutMs)

    private fun runAsShell(command: String, timeoutMs: Long): Boolean {
        val shellScript = "printf '%s\\n' $SHELL_READY\n$command"
        val script = "case \"\$(/system/bin/id -u)\" in " +
            "0|2000) printf '%s\\n' $ROOT_GRANTED ;; *) exit 1 ;; esac\n" +
            "exec ${quote(launcher.absolutePath)} ${quote(shellScript)}"
        val process = try {
            ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        } catch (error: IOException) {
            Log.w(TAG, "Could not launch su", error)
            publish(State.UNAVAILABLE)
            return false
        }

        // Manager warnings and command output may share the stream. Only our stage markers count.
        val observed = AtomicReference(State.UNAVAILABLE)
        val output = readers.submit {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    when (line.trim()) {
                        ROOT_GRANTED -> observed.compareAndSet(State.UNAVAILABLE, State.SHELL_FAILED)
                        SHELL_READY -> observed.compareAndSet(State.SHELL_FAILED, State.READY)
                    }
                }
            }
        }
        return try {
            process.outputStream.close()
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                output.get(2, TimeUnit.SECONDS)
                observed.get() == State.READY && process.exitValue() == 0
            } else {
                Log.w(TAG, "Root command timed out at ${observed.get()}")
                false
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "Root command failed at ${observed.get()}", error)
            false
        } finally {
            process.destroyForcibly()
            output.cancel(true)
            publish(observed.get())
        }
    }

    private fun publish(next: State) {
        if (next != State.READY) Log.w(TAG, "Root access check: $next")
        if (wasReady != (next == State.READY)) prefs.edit().putBoolean(KEY_GRANTED, next == State.READY).apply()
        _state.value = next
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val TAG = "JiumiRoot"
        const val KEY_GRANTED = "root_granted"
        const val ROOT_GRANTED = "__JIUMI_ROOT_GRANTED__"
        const val SHELL_READY = "__JIUMI_SHELL_READY__"
        const val AUTH_TIMEOUT_MS = 30_000L
    }
}
