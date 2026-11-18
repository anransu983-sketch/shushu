// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.suanran.shushu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * A newer release, as its release.json describes it: the APK to fetch, its exact size and SHA-256,
 * and its notes in English and Simplified Chinese.
 */
data class ReleaseInfo(
    val tag: String,
    val versionName: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    val sha256: String,
    val notes: Map<String, String>
) {
    /** The notes in the app's language: Chinese for any zh locale, English otherwise. */
    fun notesFor(languageTag: String): String =
        notes[if (languageTag.substringBefore('-').equals("zh", ignoreCase = true)) "zh" else "en"].orEmpty().trim()
}

/** What checking found. */
sealed interface UpdateCheck {
    /** No repository is configured in this build, so nothing is ever asked. */
    data object Disabled : UpdateCheck
    data class UpToDate(val version: String) : UpdateCheck
    data class Available(val release: ReleaseInfo) : UpdateCheck
    /** The repository has no release yet. */
    data object NoRelease : UpdateCheck
    data class Failed(val reason: String) : UpdateCheck
}

/**
 * The release manifest, checked before anything in it is trusted: the tag is v + the version, the
 * package is this app, the APK is an asset of this repository's release, and size, digest and both
 * languages' notes are present. Anything else is rejected as a whole.
 */
object ReleaseManifest {
    private val SHA256 = Regex("[a-fA-F0-9]{64}")
    private val ASSET = Regex("[A-Za-z0-9._-]+\\.apk")

    fun parse(json: String, repository: String, packageName: String, currentVersion: String): ReleaseInfo {
        val o = JSONObject(json)
        val tag = o.getString("tag")
        val version = o.getString("version_name")
        val apk = o.getString("apk")
        val size = o.getLong("size_bytes")
        val sha = o.getString("sha256")
        val notesObject = o.getJSONObject("release_notes")
        val notes = listOf("en", "zh").associateWith { notesObject.optString(it) }
        require(VersionComparator.isValid(currentVersion) && VersionComparator.isValid(version)) { "invalid version" }
        require(version == version.trim().removePrefix("v").removePrefix("V") && tag == "v$version") { "tag does not match version" }
        require(o.getString("package") == packageName) { "release is for another package" }
        require(ASSET.matches(apk) && size > 0 && SHA256.matches(sha)) { "invalid APK asset, size or digest" }
        require(notes.values.none { it.isBlank() }) { "release notes missing a language" }
        return ReleaseInfo(tag, version, "https://github.com/$repository/releases/download/$tag/$apk", apk, size, sha.lowercase(), notes)
    }
}

/**
 * Checks for, downloads and installs a newer release. A check reads one file, release.json, published
 * with each GitHub release (never GitHub's API): when the user asks, and on opening the app at most
 * every six hours unless turned off. The APK comes from the same release, is downloaded only when the
 * user chooses to, and is installed only if its size, digest, package and signing key match.
 */
class Updates(private val context: Context, private val repository: String = BuildConfig.REPOSITORY) {
    val enabled: Boolean get() = repository.isNotBlank()
    val currentVersion: String get() = BuildConfig.VERSION_NAME
    private val updateDir get() = File(context.cacheDir, "updates")
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Checking on its own, at most every few hours, when the app is opened; on unless turned off. */
    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO, value).apply() }

    /** The newer release the last check found, still newer than this app; null when there is none. */
    fun cached(): ReleaseInfo? = prefs.getString(KEY_MANIFEST, null)?.let { body ->
        runCatching { ReleaseManifest.parse(body, repository, context.packageName, currentVersion) }.getOrNull()
            ?.takeIf { VersionComparator.isNewer(it.tag, currentVersion) }
    }

    /**
     * The check made when the app is opened: none when turned off or when the last one was recent,
     * in which case its finding stands. One small file, so GitHub's limits are never near.
     */
    suspend fun checkIfDue(): ReleaseInfo? {
        if (!enabled || !autoCheck) return null
        if (System.currentTimeMillis() - prefs.getLong(KEY_CHECKED_AT, 0L) in 0 until AUTO_INTERVAL_MS) return cached()
        return (check() as? UpdateCheck.Available)?.release
    }

    /** Whether the release [version] was already shown on opening the app; it is offered there once. */
    fun announced(version: String) = prefs.getString(KEY_ANNOUNCED, null) == version
    fun markAnnounced(version: String) { prefs.edit().putString(KEY_ANNOUNCED, version).apply() }

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext UpdateCheck.Disabled
        try {
            val body = get("https://github.com/$repository/releases/latest/download/release.json").use { c ->
                when (c.responseCode) {
                    HttpURLConnection.HTTP_NOT_FOUND -> {
                        prefs.edit().remove(KEY_MANIFEST).putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
                        return@withContext UpdateCheck.NoRelease
                    }
                    in 200..299 -> c.inputStream.bufferedReader().use { it.readText() }
                    else -> throw IOException("HTTP ${c.responseCode}")
                }
            }
            val release = ReleaseManifest.parse(body, repository, context.packageName, currentVersion)
            prefs.edit().putString(KEY_MANIFEST, body).putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
            val newer = VersionComparator.isNewer(release.tag, currentVersion)
            UpdateNotice.available.value = release.takeIf { newer }
            if (newer) UpdateCheck.Available(release) else UpdateCheck.UpToDate(currentVersion)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "update check failed: ${e.javaClass.simpleName}: ${e.message}")
            UpdateCheck.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Fetches [release]'s APK, reporting bytes received of the expected total, and returns it once its
     * size, SHA-256, package and signing key are those expected; null and nothing kept otherwise.
     */
    suspend fun download(release: ReleaseInfo, onProgress: (Long, Long) -> Unit): File? = withContext(Dispatchers.IO) {
        if (!updateDir.isDirectory && !updateDir.mkdirs()) return@withContext null
        updateDir.listFiles()?.forEach { it.delete() }
        val target = File(updateDir, release.apkName)
        repeat(3) { attempt ->
            val ok = runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                get(release.apkUrl).use { c ->
                    if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                    c.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                received += n
                                if (received > release.sizeBytes) throw IOException("larger than the release says")
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                                onProgress(received, release.sizeBytes)
                            }
                        }
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                received == release.sizeBytes && hex == release.sha256 && isUpgradeOfThisApp(target)
            }.getOrDefault(false)
            if (ok) return@withContext target
            target.delete()
            if (attempt < 2) kotlinx.coroutines.delay(1_000L * (attempt + 1))
        }
        null
    }

    /** The system lets this app open the installer only once the user has allowed it. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun allowInstallIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands a verified [apk] to the system installer, which asks the user and checks it again. */
    fun install(apk: File): Boolean = runCatching {
        if (!isUpgradeOfThisApp(apk)) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** Same package, a higher version, installable here, and signed by exactly the installed app's keys. */
    private fun isUpgradeOfThisApp(apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        if (candidate.packageName != context.packageName) return false
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val minSdk = candidate.applicationInfo?.minSdkVersion ?: return false
        if (candidate.longVersionCode <= installed.longVersionCode || minSdk > android.os.Build.VERSION.SDK_INT ||
            !VersionComparator.isNewer(candidate.versionName.orEmpty(), installed.versionName.orEmpty())) return false
        sameSigners(installed.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty(),
            candidate.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty())
    }.getOrDefault(false)

    /** HTTPS only, following GitHub's redirect to its download host; identifies only as the app. */
    private fun get(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "updates are fetched over HTTPS only" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "Jiumi-AppUpdate/${BuildConfig.VERSION_NAME}")
        }
    }

    private companion object {
        const val TAG = "Updates"
        const val KEY_AUTO = "auto_check"
        const val KEY_MANIFEST = "manifest"
        const val KEY_CHECKED_AT = "checked_at"
        const val KEY_ANNOUNCED = "announced"
        const val AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T = try { block(this) } finally { disconnect() }
}

/** The candidate is signed by exactly the installed app's signers, no more and no fewer. */
internal fun sameSigners(installed: List<ByteArray>, candidate: List<ByteArray>): Boolean =
    installed.isNotEmpty() && installed.size == candidate.size &&
        installed.all { expected -> candidate.any { it.contentEquals(expected) } } &&
        candidate.all { actual -> installed.any { it.contentEquals(actual) } }

/** The newer release found, for the whole app: the Settings tab and its About row mark it. */
object UpdateNotice {
    val available = MutableStateFlow<ReleaseInfo?>(null)
}
