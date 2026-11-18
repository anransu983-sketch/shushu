// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.adb

import android.util.Log
import java.io.File

/** Bounded local diagnostics for pairing; never leaves the device. */
class PairingLog(private val file: File) {
    @Synchronized
    fun event(message: String) {
        Log.i(TAG, message)
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() > MAX_BYTES) file.renameTo(File(file.path + ".1"))
            file.appendText("${System.currentTimeMillis()} $message\n")
        }
    }

    private companion object {
        const val TAG = "JiumiAdb"
        const val MAX_BYTES = 256 * 1024L
    }
}
