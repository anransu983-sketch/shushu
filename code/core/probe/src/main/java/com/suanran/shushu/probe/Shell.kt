// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import java.util.concurrent.TimeUnit

/** Runs a short platform command as the shell user, with a deadline and bounded output. */
internal object Shell {
    class Result(val exitCode: Int, val output: String)

    private const val MAX_OUTPUT_CHARS = 8_000_000

    /** [stdinFile] is streamed to the command, for input too large to hold in memory. */
    fun run(command: List<String>, stdin: String? = null, timeoutMs: Long = 10_000, stdinFile: java.io.File? = null): Result {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        // Fed from its own thread, so a command that writes while it reads cannot stall on a full pipe.
        val feeder = Thread {
            runCatching {
                process.outputStream.use { out ->
                    if (stdin != null) out.write(stdin.toByteArray())
                    stdinFile?.inputStream()?.use { it.copyTo(out) }
                }
            }
        }.apply { start() }
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { r ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val n = r.read(buffer)
                        if (n < 0) break
                        if (output.length < MAX_OUTPUT_CHARS) output.append(buffer, 0, n)
                    }
                }
            }
        }.apply { start() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            reader.join(1_000)
            throw IllegalStateException("${command.first()} timed out")
        }
        reader.join(2_000)
        feeder.join(1_000)
        return Result(process.exitValue(), output.toString())
    }
}
