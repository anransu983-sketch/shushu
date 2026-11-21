// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.suanran.shushu.R
import com.suanran.shushu.ui.icons.JiumiIcons
import com.suanran.shushu.ui.theme.Ink
import org.json.JSONObject

/** The open-source work the app is built on, from assets/about/acknowledgements.json. */
class Acknowledgements(val entries: List<Entry>) {
    data class Entry(val name: String, val license: String, val url: String, val files: List<String>)

    companion object {
        fun load(context: Context): Acknowledgements {
            val root = JSONObject(context.assets.open("about/acknowledgements.json").bufferedReader().use { it.readText() })
            val list = root.getJSONArray("entries")
            return Acknowledgements((0 until list.length()).map { i ->
                val e = list.getJSONObject(i)
                val files = e.optJSONArray("files")
                Entry(e.getString("name"), e.getString("license"), e.optString("url"),
                    files?.let { f -> (0 until f.length()).map { f.getString(it) } }.orEmpty())
            })
        }
    }
}

/** The app's name and version, updates, where its code and license are, and the licenses of what it uses. */
@Composable
fun AboutScreen(labels: AppLabels, onBack: () -> Unit, onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    val updates = remember { com.suanran.shushu.update.Updates(context.applicationContext) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    SubScreen(stringResource(R.string.settings_about), onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AppIcon(labels, context.packageName, 72)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = Ink.primary)
                    Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodyMedium, color = Ink.secondary,
                        modifier = Modifier.padding(top = 4.dp))
                    Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodySmall, color = Ink.secondary,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            item {
                // The repository is set when the project is published; until then the links have nowhere to go.
                val repo = com.suanran.shushu.BuildConfig.REPOSITORY
                Card {
                    UpdateRows(updates)
                    InsetDivider()
                    ListRow(stringResource(R.string.about_source), leading = { IconTile(JiumiIcons.Code) },
                        onClick = if (repo.isNotBlank()) ({ open(context, "https://github.com/$repo") }) else null) {
                        if (repo.isBlank()) TrailingValue(stringResource(R.string.about_not_public)) else Chevron()
                    }
                    InsetDivider()
                    ListRow(stringResource(R.string.about_license), leading = { IconTile(JiumiIcons.FileText) },
                        onClick = if (repo.isNotBlank()) ({ open(context, "https://github.com/$repo/blob/main/LICENSE") }) else null) {
                        TrailingValue("Apache-2.0")
                        if (repo.isNotBlank()) Chevron()
                    }
                    InsetDivider()
                    ListRow(stringResource(R.string.about_licenses), leading = { IconTile(JiumiIcons.Layers) }, onClick = onOpenLicenses) {
                        Chevron()
                    }
                }
            }
        }
    }
}

/**
 * The open-source work the app is built on, each with its license; kept a level below About, since
 * the licenses ask that their texts travel with the app but nobody needs them in passing.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit, onOpenNotice: (Int) -> Unit) {
    val context = LocalContext.current
    val about = remember { Acknowledgements.load(context) }
    SubScreen(stringResource(R.string.about_licenses), onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp)
        ) {
            item {
                Card {
                    about.entries.forEachIndexed { index, e ->
                        if (index > 0) InsetDivider(start = 16.dp)
                        ListRow(e.name, subtitle = e.license, onClick = {
                            if (e.files.isNotEmpty()) onOpenNotice(index) else if (e.url.isNotEmpty()) open(context, e.url)
                        }) { Chevron() }
                    }
                }
            }
        }
    }
}

/** One acknowledgement's license texts in full, as bundled with the app. */
@Composable
fun NoticeScreen(index: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val entry = remember(index) { Acknowledgements.load(context).entries.getOrNull(index) }
    val text = remember(entry) {
        entry?.files.orEmpty().joinToString("\n\n") { path ->
            runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("")
        }.trim()
    }
    SubScreen(entry?.name.orEmpty(), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Card {
                Text(text, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = Ink.secondary, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Checking for updates. The row says what was found; tapped, it checks again, or, with a newer release
 * known, opens its sheet. Beneath it, whether the app checks on its own when opened.
 */
@Composable
private fun UpdateRows(updates: com.suanran.shushu.update.Updates) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val available by com.suanran.shushu.update.UpdateNotice.available.collectAsStateWithLifecycle()
    var checking by remember { androidx.compose.runtime.mutableStateOf(false) }
    var found by remember { androidx.compose.runtime.mutableStateOf<com.suanran.shushu.update.UpdateCheck?>(null) }
    var showing by remember { androidx.compose.runtime.mutableStateOf(false) }
    var auto by remember { androidx.compose.runtime.mutableStateOf(updates.autoCheck) }
    ListRow(
        stringResource(R.string.update_check),
        leading = { IconTile(JiumiIcons.Refresh) },
        onClick = if (updates.enabled && !checking) ({
            if (available != null) showing = true
            else scope.launch {
                checking = true
                found = updates.check()
                checking = false
                if (found is com.suanran.shushu.update.UpdateCheck.Available) showing = true
            }
            Unit
        }) else null
    ) {
        val release = available
        when {
            !updates.enabled -> TrailingValue(stringResource(R.string.about_not_public))
            checking -> TrailingValue(stringResource(R.string.update_checking))
            release != null -> { NewDot(); TrailingValue(stringResource(R.string.update_available, release.versionName), color = Ink.primary) }
            else -> {
                val f = found
                TrailingValue(when (f) {
                    is com.suanran.shushu.update.UpdateCheck.UpToDate -> stringResource(R.string.update_up_to_date)
                    com.suanran.shushu.update.UpdateCheck.NoRelease -> stringResource(R.string.update_no_release)
                    is com.suanran.shushu.update.UpdateCheck.Failed -> stringResource(R.string.update_failed) + " (${f.reason})"
                    else -> stringResource(R.string.about_version, updates.currentVersion)
                })
            }
        }
    }
    if (updates.enabled) {
        InsetDivider()
        ListRow(stringResource(R.string.update_auto), leading = { IconTile(JiumiIcons.Bolt) },
            onClick = { auto = !auto; updates.autoCheck = auto }) {
            JiumiSwitch(auto) { auto = it; updates.autoCheck = it }
        }
    }
    val release = available
    if (showing && release != null) UpdateDialog(updates, release, onClose = { showing = false })
}

/** The small red mark of something new, as iOS marks an update waiting. */
@Composable
fun NewDot(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier.padding(end = 2.dp).size(8.dp)
        .background(NEW_RED, androidx.compose.foundation.shape.CircleShape))
}

val NEW_RED = androidx.compose.ui.graphics.Color(0xFFFF3B30)

/** Where updating stands inside its sheet. */
private sealed interface Fetch {
    data object Idle : Fetch
    data class Downloading(val received: Long, val total: Long) : Fetch
    data object Failed : Fetch
}

/**
 * A newer release: its version, size and notes, and one button that downloads it, checks it and
 * hands it to the system installer, with the download's progress shown in place.
 */
@Composable
fun UpdateDialog(updates: com.suanran.shushu.update.Updates, release: com.suanran.shushu.update.ReleaseInfo,
                 onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var fetch by remember { androidx.compose.runtime.mutableStateOf<Fetch>(Fetch.Idle) }
    val busy = fetch is Fetch.Downloading
    val lang = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].toLanguageTag()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.update_title, release.versionName), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(android.text.format.Formatter.formatShortFileSize(context, release.sizeBytes),
                    style = MaterialTheme.typography.labelMedium, color = Ink.secondary)
                ReleaseNotesText(release.notesFor(lang), Modifier.padding(top = 10.dp))
                when (val f = fetch) {
                    is Fetch.Downloading -> androidx.compose.material3.LinearProgressIndicator(
                        progress = { f.received.toFloat() / f.total.coerceAtLeast(1) },
                        color = Ink.primary, trackColor = Ink.fill,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(4.dp))
                    Fetch.Failed -> Text(stringResource(R.string.update_download_failed), style = MaterialTheme.typography.bodySmall,
                        color = com.suanran.shushu.ui.theme.StatusWarning, modifier = Modifier.padding(top = 12.dp))
                    Fetch.Idle -> Unit
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = !busy, onClick = {
                if (!updates.canInstall()) { runCatching { context.startActivity(updates.allowInstallIntent()) }; return@TextButton }
                scope.launch {
                    fetch = Fetch.Downloading(0, release.sizeBytes)
                    val apk = updates.download(release) { got, total -> fetch = Fetch.Downloading(got, total) }
                    if (apk != null && updates.install(apk)) onClose() else fetch = Fetch.Failed
                }
            }) {
                Text(stringResource(when (val f = fetch) {
                    is Fetch.Downloading -> R.string.update_downloading_short
                    Fetch.Failed -> R.string.update_retry
                    Fetch.Idle -> R.string.update_install
                }).let { if (fetch is Fetch.Downloading) "$it ${(100 * (fetch as Fetch.Downloading).received / (fetch as Fetch.Downloading).total.coerceAtLeast(1))}%" else it },
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
        },
        dismissButton = {
            if (!busy) androidx.compose.material3.TextButton(onClick = onClose) {
                Text(stringResource(R.string.update_later), color = Ink.secondary)
            }
        }
    )
}

/** Release notes as written in the changelog: "### " headings and "- " items, plain otherwise. */
@Composable
private fun ReleaseNotesText(notes: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        notes.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.forEach { line ->
            when {
                line.startsWith("### ") -> Text(line.removePrefix("### "), style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Ink.primary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                line.startsWith("- ") -> androidx.compose.foundation.layout.Row(Modifier.padding(top = 3.dp)) {
                    Text("•", style = MaterialTheme.typography.bodySmall, color = Ink.secondary, modifier = Modifier.padding(end = 8.dp))
                    Text(line.removePrefix("- ").replace("**", ""), style = MaterialTheme.typography.bodySmall, color = Ink.secondary)
                }
                else -> Text(line.replace("**", ""), style = MaterialTheme.typography.bodySmall, color = Ink.secondary,
                    modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** The project's website: what Jiumi is, the guide and the latest release. */
