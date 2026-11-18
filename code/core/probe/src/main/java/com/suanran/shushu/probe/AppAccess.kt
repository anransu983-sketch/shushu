// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.probe

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Uses Android's own permission and UID firewall rules. All writes share one lock. */
internal class AppAccess(context: Context, directory: File, private val self: String) {
    private val pm = context.packageManager
    private val ledger = AtomicFile(File(directory, "app-access.json"))

    private data class Target(
        val pkg: String, val uid: Int, val user: Int, val lock: String?, val identity: String, val peers: List<String>
    )
    private data class Grant(val name: String, val allowed: Boolean?, val editable: Boolean)
    private data class Firewall(val enabled: Boolean, val denied: Boolean, val boot: String)

    @Synchronized
    fun run(pkg: String, uid: Int, user: Int, action: String, permission: String): JSONObject = runCatching {
        require(action in actions)
        require(permission.isEmpty() || permissionName.matches(permission))
        val target = target(pkg, uid, user) ?: return JSONObject().put("lock", "unknown")
        val saved = if (ledger.baseFile.exists()) JSONObject(ledger.readFully().decodeToString()) else JSONObject()
        val result = if (action.startsWith("network")) network(target, saved, action) else
            permissions(target, saved, action, permission)
        result.put("peers", JSONArray(target.peers))
    }.getOrElse { JSONObject().put("lock", "unknown") }

    private fun command(vararg args: String): String? =
        runCatching { Shell.run(args.toList()) }.getOrNull()?.takeIf { it.exitCode == 0 }?.output?.trimEnd()

    private fun target(pkg: String, uid: Int, user: Int): Target? {
        if (uid < 0 || uid / USER_RANGE != user) return null
        val listed = command("cmd", "package", "list", "packages", "--user", "$user", "-U", "--uid", "$uid") ?: return null
        val rows = listed.lines().filter(String::isNotBlank).map { packageRow.matchEntire(it.trim()) ?: return null }
        if (rows.none { it.groupValues[1] == pkg && it.groupValues[2] == "$uid" }) return null
        val info = if (user == 0) runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() else null
        val lock = when {
            pkg == self || uid % USER_RANGE < 10_000 || (info?.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_PERSISTENT != 0 -> "protected"
            rows.size != 1 -> "shared"
            info == null || info.applicationInfo?.uid != uid -> "unknown"
            else -> null
        }
        val peers = rows.map { it.groupValues[1] }.filter { it != pkg }.distinct().sorted()
        return Target(pkg, uid, user, lock, "$uid:${info?.firstInstallTime ?: 0}:$pkg", peers)
    }

    private fun grants(t: Target): List<Grant>? {
        val dump = command("dumpsys", "package", t.pkg) ?: return null
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("Package [${t.pkg}] (") }
            .takeIf { it >= 0 }
        // Package entries are followed by per-user blocks. Use only the installed target user's block.
        if (start == null) return null
        val userStart = (start + 1 until lines.size).firstOrNull {
            lines[it].trimStart().startsWith("User ${t.user}: ") && "installed=true" in lines[it]
        } ?: return null
        val userLines = lines.drop(userStart + 1).takeWhile { !userRow.containsMatchIn(it) && (it.isBlank() || it.startsWith("      ")) }
        val runtime = userLines.dropWhile { it.trim() != "runtime permissions:" }.drop(1)
            .takeWhile { it.startsWith("        ") }.mapNotNull { grantRow.matchEntire(it.trim()) }
        val installed = lines.subList(start, userStart).dropWhile { it.trim() != "install permissions:" }.drop(1)
            .takeWhile { it.startsWith("      ") }.mapNotNull { grantRow.matchEntire(it.trim()) }
        val states = (installed + runtime).associateBy { it.groupValues[1] }
        val runtimeNames = runtime.map { it.groupValues[1] }.toSet()
        val requested = lines.subList(start, userStart).dropWhile { it.trim() != "requested permissions:" }.drop(1)
            .takeWhile { it.startsWith("      ") }.map(String::trim).filter(permissionName::matches)
        return requested.distinct().map { name ->
            val state = states[name]
            val flags = state?.groupValues?.get(3).orEmpty().split('|').map(String::trim)
            val info = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull()
            val editable = t.lock == null && name in runtimeNames &&
                info?.protection == PermissionInfo.PROTECTION_DANGEROUS &&
                flags.none { it == "SYSTEM_FIXED" || it == "POLICY_FIXED" || it == "GRANTED_BY_ROLE" }
            Grant(name, state?.groupValues?.get(2)?.toBooleanStrictOrNull(), editable)
        }
    }

    private fun permissions(t: Target, saved: JSONObject, action: String, name: String): JSONObject {
        var rows = grants(t) ?: return JSONObject().put("lock", "unknown")
        val key = "permissions:${t.identity}"
        val owned = saved.optJSONObject(key) ?: JSONObject().also { saved.put(key, it) }
        val changes = when (action) {
            "deny", "allow" -> rows.filter { it.name == name }
            "denyAll" -> rows.filter { it.editable && it.allowed == true }
            "restoreAll" -> rows.filter { it.editable && owned.optBoolean(it.name) && it.allowed == false }
            else -> emptyList()
        }
        val failed = JSONArray()
        var changed = 0
        val allow = action == "allow" || action == "restoreAll"
        for (row in changes) {
            if (!row.editable || target(t.pkg, t.uid, t.user) != t) {
                failed.put(row.name)
                continue
            }
            if (row.allowed == allow) continue
            if (!allow) {
                owned.put(row.name, true)
                write(saved)
            }
            val verb = if (allow) "grant" else "revoke"
            command("pm", verb, "--user", "${t.user}", t.pkg, row.name)
            val current = grants(t)
            if (current == null) {
                failed.put(row.name)
                return JSONObject().put("lock", "unknown").put("failed", failed).put("changed", changed)
            }
            rows = current
            if (current.firstOrNull { it.name == row.name }?.allowed == allow) {
                changed++
                if (allow) owned.remove(row.name)
            } else {
                if (!allow && current.any { it.name == row.name && it.allowed == true }) owned.remove(row.name)
                failed.put(row.name)
            }
            write(saved)
        }
        if (action in setOf("deny", "allow") && changes.isEmpty()) failed.put(name)
        return JSONObject().put("lock", t.lock)
            .put("rows", JSONArray(rows.map {
                JSONObject().put("name", it.name).put("allowed", it.allowed).put("editable", it.editable)
            }))
            .put("restore", rows.any { it.editable && it.allowed == false && owned.optBoolean(it.name) })
            .put("changed", changed).put("failed", failed)
    }

    private fun connectivity(vararg args: String) = command("cmd", "connectivity", *args)

    private fun firewall(pkg: String): Firewall? {
        val enabled = when (connectivity("get-chain3-enabled")?.trim()) {
            "chain:enabled" -> true
            "chain:disabled" -> false
            else -> return null
        }
        val denied = when (connectivity("get-package-networking-enabled", pkg)?.trim()) {
            "$pkg:deny" -> true
            "$pkg:allow" -> false
            else -> return null
        }
        val boot = command("settings", "get", "global", "boot_count")?.trim()?.toIntOrNull()?.toString() ?: return null
        return Firewall(enabled, denied, boot)
    }

    private fun chainRules(): Set<Int>? {
        val dump = command("dumpsys", "connectivity", "trafficcontroller") ?: return null
        if ("current ownerMatch configuration:" !in dump) return null
        val lines = dump.lines().map(String::trim)
        val start = lines.indexOf("sUidOwnerMap:")
        val end = lines.indexOf("sUidPermissionMap:")
        if (start < 0 || end <= start) return null
        val rows = lines.subList(start + 1, end).filter(String::isNotEmpty)
        return rows.mapNotNull { line ->
            val match = firewallRow.matchEntire(line) ?: return null
            match.groupValues[1].toInt().takeIf { "OEM_DENY_3_MATCH" in match.groupValues[2].split(' ') }
        }.toSet()
    }

    private fun networkLock(t: Target): String? {
        if (t.lock != null) return t.lock
        val users = command("cmd", "package", "list", "users") ?: return "unknown"
        val lines = users.lines().filter(String::isNotBlank)
        if (lines.firstOrNull()?.trim() != "Users:") return "unknown"
        val ids = lines.drop(1).map { userList.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return "unknown" }
        return if (ids == listOf(0) && t.user == 0) null else "users"
    }

    private fun network(t: Target, saved: JSONObject, action: String): JSONObject {
        val before = firewall(t.pkg) ?: return JSONObject().put("lock", t.lock ?: "unsupported")
        val owned = "network:${t.identity}"
        val ownsRule = saved.optString(owned) == before.boot
        val lock = networkLock(t) ?: when {
            before.denied && !ownsRule -> "external"
            !before.enabled && chainRules()?.all { it == t.uid && ownsRule } != true -> "chain"
            else -> null
        }
        var error = false
        if (action != "network" && lock == null) {
            if (target(t.pkg, t.uid, t.user) != t || networkLock(t) != null || firewall(t.pkg) != before) {
                return JSONObject().put("lock", "unknown")
            }
            val allow = action == "networkAllow"
            if (!allow) {
                saved.put(owned, before.boot)
                if (!before.enabled) saved.put("chain", before.boot)
                write(saved)
            }
            connectivity("set-package-networking-enabled", "$allow", t.pkg)
            if (!allow && !before.enabled && firewall(t.pkg)?.denied == true) {
                if (chainRules() == setOf(t.uid)) connectivity("set-chain3-enabled", "true")
                if (firewall(t.pkg)?.enabled == false) connectivity("set-package-networking-enabled", "true", t.pkg)
            }
            val after = firewall(t.pkg) ?: return JSONObject().put("lock", "unknown")
            error = if (allow) after.denied else !after.denied || !after.enabled
            if (!after.denied) {
                saved.remove(owned)
                if (saved.optString("chain") == before.boot && chainRules()?.isEmpty() == true) {
                    if (after.enabled) connectivity("set-chain3-enabled", "false")
                    if (firewall(t.pkg)?.enabled == false) saved.remove("chain")
                }
                write(saved)
            }
        }
        val current = firewall(t.pkg) ?: return JSONObject().put("lock", "unknown")
        return JSONObject().put("lock", lock).put("allowed", !(current.enabled && current.denied))
            .put("failed", error || action != "network" && lock != null)
    }

    private fun write(saved: JSONObject) {
        val output = ledger.startWrite()
        try {
            output.write(saved.toString().encodeToByteArray())
            ledger.finishWrite(output)
        } catch (error: Exception) {
            ledger.failWrite(output)
            throw error
        }
    }

    private companion object {
        const val USER_RANGE = 100_000
        val actions = setOf("permissions", "allow", "deny", "denyAll", "restoreAll", "network", "networkAllow", "networkDeny")
        val packageRow = Regex("""package:([A-Za-z0-9_.]+)\s+uid:(\d+)""")
        val userRow = Regex("""^\s*User \d+:""")
        val userList = Regex("""UserInfo\{(\d+):[^}]*\}(?:\s+running)?""")
        val permissionName = Regex("""[A-Za-z0-9_.]+""")
        val grantRow = Regex("""([A-Za-z0-9_.]+): granted=(true|false)(?:, flags=\[(.*)])?""")
        val firewallRow = Regex("""(\d+) ((?:[A-Z0-9_]+_MATCH)(?: [A-Z0-9_]+_MATCH)*)""")
    }
}
