// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

/**
 * The package a calling process belongs to. Processes are named after their package, optionally
 * with ":suffix"; system_server runs the "android" package. A process that matches no package of its
 * uid keeps its own name, which is still what the user should see.
 */
class PackageOwners(private val pm: PackageManager, private val clock: () -> Long = System::currentTimeMillis) {
    private val cache = ConcurrentHashMap<Pair<Int, String>, String>()

    fun ownerOf(uid: Int, process: String?): String? {
        if (process == null) return ownerOfUid(uid)
        cache[uid to process]?.let { return it }
        if (process == SYSTEM_SERVER) return PLATFORM_PACKAGE
        val packages = packagesFor(uid)
        val owner = packages.firstOrNull { process == it || process.startsWith("$it:") }
            ?: packages.singleOrNull()
            ?: process
        // A uid whose packages could not be read yet is asked again later rather than kept as its process name.
        if (packages.isNotEmpty()) cache[uid to process] = owner
        return owner
    }

    /**
     * Network records carry only the uid. The system uid is the platform; a uid shared by several
     * packages is shown as the one of them the user knows (see [representative]).
     */
    private fun ownerOfUid(uid: Int): String? {
        uidCache[uid]?.let { return it }
        if (uid % PER_USER_RANGE == SYSTEM_UID) return PLATFORM_PACKAGE.also { uidCache[uid] = it }
        val now = clock()
        missingSince[uid]?.let { if (now - it < RETRY_MS) return null }
        val owner = representative(packagesFor(uid))
        if (owner == null) missingSince[uid] = now else { uidCache[uid] = owner; missingSince.remove(uid) }
        return owner
    }

    /**
     * The packages of [uid]. Apps of another user (a work profile, a maker's "dual apps" space) are
     * not listed to this app, but are the same packages as in the main user, so their app id is
     * looked up there.
     */
    /** Every package of [uid]; the system uid also runs the platform itself. */
    fun packagesOf(uid: Int): List<String> = packagesFor(uid)

    private fun packagesFor(uid: Int): List<String> {
        runCatching { pm.getPackagesForUid(uid) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it.toList() }
        val appId = uid % PER_USER_RANGE
        if (appId == uid || appId < FIRST_APPLICATION_UID) return emptyList()
        return runCatching { pm.getPackagesForUid(appId) }.getOrNull().orEmpty().toList()
    }

    /**
     * One package to stand for a shared uid: the one with a launcher entry when exactly one has it,
     * otherwise the shortest name, which for maker and Google suites is the core package the others
     * extend (com.google.android.gms over com.google.android.gsf.login).
     */
    private fun representative(packages: List<String>): String? {
        if (packages.size <= 1) return packages.firstOrNull()
        val launchable = packages.filter { pkg ->
            runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }
        return (launchable.singleOrNull() ?: packages.minWithOrNull(compareBy<String> { it.length }.thenBy { it }))
    }

    private val uidCache = ConcurrentHashMap<Int, String>()
    private val missingSince = ConcurrentHashMap<Int, Long>()

    private companion object {
        const val SYSTEM_UID = 1000
        const val PER_USER_RANGE = 100_000
        const val FIRST_APPLICATION_UID = 10_000
        const val RETRY_MS = 60_000L
        const val SYSTEM_SERVER = "system_server"
        const val PLATFORM_PACKAGE = "android"
    }
}
