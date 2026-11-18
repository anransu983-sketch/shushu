// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/**
 * Installation files of apps deleted from here, kept so they can be installed again. Only the APKs
 * are kept: an app's own data lives where nothing but the app (or root) can read it.
 *
 * Backups sit in this app's external files, backups/<package>/, where the monitor (as the shell)
 * can read them to install; nothing else can. Each holds the APKs, meta.json and icon.png.
 */
class AppBackups(context: Context) {
    data class Backup(val pkg: String, val label: String, val uid: Int, val t: Long, val bytes: Long, val dir: File)

    private val root: File? = context.getExternalFilesDir(null)?.let { File(it, "backups") }

    fun list(): List<Backup> = root?.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull(::read).sortedByDescending { it.t }

    fun get(pkg: String): Backup? = if (Management.isPackageName(pkg)) root?.let { read(File(it, pkg)) } else null

    fun icon(pkg: String): Bitmap? = get(pkg)?.let { b -> File(b.dir, ICON).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) } }

    /** Copies [app]'s APKs, base and splits, with its name and icon. Null when they cannot all be copied. */
    fun create(app: ApplicationInfo, label: String, icon: Bitmap?): Backup? {
        val base = root ?: return null
        if (!Management.isPackageName(app.packageName)) return null
        val dir = File(base, app.packageName)
        dir.deleteRecursively()
        val sources = listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty()
        val ok = dir.mkdirs() && sources.withIndex().all { (i, path) ->
            // base.apk first, then the splits under their own names, which the installer needs.
            val name = if (i == 0) "base.apk" else File(path).name
            runCatching { File(path).copyTo(File(dir, name), overwrite = true) }.isSuccess
        }
        if (!ok) { dir.deleteRecursively(); return null }
        icon?.let { b -> runCatching { File(dir, ICON).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
        File(dir, META).writeText(JSONObject().put("label", label).put("uid", app.uid).put("t", System.currentTimeMillis()).toString())
        return read(dir)
    }

    fun delete(pkg: String) {
        get(pkg)?.dir?.deleteRecursively()
    }

    private fun read(dir: File): Backup? {
        val meta = runCatching { JSONObject(File(dir, META).readText()) }.getOrNull() ?: return null
        val apks = dir.listFiles { f -> f.name.endsWith(".apk") }.orEmpty()
        if (apks.isEmpty()) return null
        return Backup(dir.name, meta.optString("label", dir.name), meta.optInt("uid", -1), meta.optLong("t"), apks.sumOf { it.length() }, dir)
    }

    private companion object {
        const val META = "meta.json"
        const val ICON = "icon.png"
    }
}
