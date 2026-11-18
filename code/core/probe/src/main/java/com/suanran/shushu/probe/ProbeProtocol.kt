// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

/** Contract shared by the app and the shell-identity monitor started from the same APK. */
object ProbeProtocol {
    const val VERSION = 16
    const val SHELL_UID = 2000
    const val MAIN_CLASS = "com.suanran.shushu.probe.ProbeMain"
    const val PROCESS_NAME = "shushu_probe"
    const val ACTION_READY = "com.suanran.shushu.action.PROBE_READY"
    /** Only holders of DUMP (the shell) may deliver the monitor binder. */
    const val SENDER_PERMISSION = "android.permission.DUMP"
    const val EXTRA_BINDER = "probe_binder"
    const val EXTRA_UID = "probe_uid"
    const val EXTRA_PROTOCOL = "probe_protocol"
    const val EXTRA_SOURCE = "probe_source"
    const val EXTRA_UPDATED_AT = "probe_updated_at"

    fun dataDirectory(packageName: String): String = "/data/local/tmp/$packageName"
}

/** Machine-readable reasons returned by package management commands. */
enum class PackageChangeFailure {
    PERMISSION_DENIED, NOT_APPLIED, UNVERIFIED;

    companion object {
        private val permissionDenial = Regex("""^(?:Error:\s*)?(?:(?:java\.lang\.)?SecurityException|Permission Denial)(?::|$)""")

        internal fun fromCommandOutput(output: String): PackageChangeFailure? = PERMISSION_DENIED.takeIf {
            output.lineSequence().any { line -> permissionDenial.containsMatchIn(line.trimStart()) }
        }
    }
}
