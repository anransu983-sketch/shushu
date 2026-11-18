// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.access

import android.os.Process
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Started by Shizuku in a process of its own, as the shell. It only runs the few commands that start
 * the monitor, which then lives on by itself; this process is let go right after.
 */
@Keep
class ShizukuShellService : IShizukuShellService.Stub() {
    override fun getUid(): Int = Process.myUid()

    override fun execute(command: String, timeoutMs: Long): String {
        if (Process.myUid() != SHELL_UID) return "[SHIZUKU_NOT_SHELL] uid=${Process.myUid()}"
        return try {
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val reader = process.inputStream.bufferedReader()
            val text = StringBuilder()
            val pump = Thread { runCatching { reader.forEachLine { if (text.length < MAX_OUTPUT_CHARS) text.appendLine(it) } } }
            pump.start()
            if (!process.waitFor(timeoutMs.coerceIn(1, MAX_TIMEOUT_MS), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                "[SHIZUKU_TIMEOUT]"
            } else {
                pump.join(2_000)
                text.toString().trimEnd()
            }
        } catch (error: Exception) {
            "[SHIZUKU_ERROR] ${error.javaClass.simpleName}"
        }
    }

    override fun destroy() = exitProcess(0)

    private companion object {
        const val SHELL_UID = 2000
        const val MAX_OUTPUT_CHARS = 128_000
        const val MAX_TIMEOUT_MS = 120_000L
    }
}
