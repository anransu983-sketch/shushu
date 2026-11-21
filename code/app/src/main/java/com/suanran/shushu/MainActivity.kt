// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.suanran.shushu.data.AppKey
import com.suanran.shushu.data.LogEntry
import com.suanran.shushu.ui.LogDetailScreen
import com.suanran.shushu.ui.OpenedLogs
import com.suanran.shushu.ui.AppDetailScreen
import com.suanran.shushu.ui.AppLabels
import com.suanran.shushu.ui.AttentionScreen
import com.suanran.shushu.ui.ExportScreen
import com.suanran.shushu.ui.GroupScreen
import com.suanran.shushu.ui.HomeScreen
import com.suanran.shushu.ui.LanguageScreen
import com.suanran.shushu.ui.SessionsScreen
import com.suanran.shushu.ui.SettingsScreen
import com.suanran.shushu.ui.SpoolScreen
import com.suanran.shushu.ui.ThemeScreen
import com.suanran.shushu.ui.AppsScreen
import com.suanran.shushu.ui.AboutScreen
import com.suanran.shushu.ui.LicensesScreen
import com.suanran.shushu.ui.PairingScreen
import com.suanran.shushu.ui.PermissionManagerScreen
import com.suanran.shushu.ui.SetupScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.suanran.shushu.ui.NoticeScreen
import com.suanran.shushu.ui.Tab
import com.suanran.shushu.ui.TabScaffold
import com.suanran.shushu.ui.theme.JiumiTheme

class MainActivity : AppCompatActivity() {
    private val controller get() = (application as ShushuApp).controller
    private val preferences get() = (application as ShushuApp).preferences
    private val labels by lazy { AppLabels(this) }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        beginPairing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // An install paired before setup existed has nothing left to set up.
        if (controller.isPaired) preferences.finishSetup()
        // While the app is on screen, every page follows a running session as it records.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(LIVE_REFRESH_MS)
                    controller.refreshLive()
                }
            }
        }
        setContent {
            JiumiTheme {
                // Pages above the tabs: "app:uid|package", "group:id", "attention:id", "log:id", "theme",
                // "language", "spool", "sessions", "export", "about", "licenses", "notice:index", "pairing",
                // "permManager".
                var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
                var tab by rememberSaveable { mutableStateOf(Tab.MONITOR) }
                // Each page keeps its own state (scroll, chosen view, filters) while pages above it are open.
                val pages = rememberSaveableStateHolder()
                val push = { route: String -> stack = stack + route }
                // A page's state is keyed by its depth too: the same page can be open twice in the stack.
                fun pageKey(depth: Int) = "$depth|${stack.getOrNull(depth - 1) ?: "home"}"
                val pop = { pages.removeState(pageKey(stack.size)); stack = stack.dropLast(1) }
                val openApp = { key: AppKey -> push("app:${key.uid}|${key.pkg.orEmpty()}") }
                val openLog = { entry: LogEntry -> push("log:${OpenedLogs.put(entry)}") }
                val top = stack.lastOrNull()
                @androidx.compose.runtime.Composable
                fun settings(onBack: (() -> Unit)?) = SettingsScreen(controller, preferences, onBack = onBack,
                    onOpenTheme = { push("theme") }, onOpenLanguage = { push("language") },
                    onOpenSpool = { push("spool") }, onOpenSessions = { push("sessions") }, onOpenExport = { push("export") },
                    onOpenAbout = { push("about") }, onOpenPairing = { push("pairing") },
                    onOpenPermissionManager = { push("permManager") },
                    onOpenMonitor = { stack = listOf(); tab = Tab.MONITOR })
                val setupDone by preferences.setupDone.collectAsStateWithLifecycle()
                // On opening, a check for a newer release when one is due; a release found is offered once
                // in a sheet, and stays marked on the Settings tab until installed.
                val updates = androidx.compose.runtime.remember { com.suanran.shushu.update.Updates(applicationContext) }
                var offered by androidx.compose.runtime.remember { mutableStateOf<com.suanran.shushu.update.ReleaseInfo?>(null) }
                LaunchedEffect(Unit) {
                    com.suanran.shushu.update.UpdateNotice.available.value = updates.cached()
                    val release = updates.checkIfDue() ?: updates.cached()
                    com.suanran.shushu.update.UpdateNotice.available.value = release
                    if (release != null && !updates.announced(release.versionName)) {
                        updates.markAnnounced(release.versionName)
                        offered = release
                    }
                }
                offered?.let { if (setupDone) com.suanran.shushu.ui.UpdateDialog(updates, it, onClose = { offered = null }) }
                if (top != null) BackHandler(onBack = pop)
                pages.SaveableStateProvider(pageKey(stack.size)) { when {
                    top == null && !setupDone -> SetupScreen(controller, onPair = ::pair,
                        onOpenWirelessDebugging = ::openWirelessDebugging, onDone = preferences::finishSetup)
                    top == null -> TabScaffold(tab, onSelect = { tab = it }) { selected ->
                        // Each tab keeps its own scroll and choices while another is shown.
                        pages.SaveableStateProvider("tab|${selected.name}") { when (selected) {
                            Tab.MONITOR -> HomeScreen(
                                controller = controller,
                                labels = labels,
                                onOpenGroup = { push("group:$it") },
                                onOpenAttention = { push("attention:$it") },
                                onOpenLog = openLog,
                                onOpenPairing = { push("pairing") }
                            )
                            Tab.APPS -> AppsScreen(controller, labels, onOpenApp = openApp)
                            Tab.SETTINGS -> settings(onBack = null)
                        } }
                    }
                    top.startsWith("app:") -> {
                        val (uid, pkg) = top.removePrefix("app:").split('|', limit = 2)
                        AppDetailScreen(controller, labels, AppKey(uid.toInt(), pkg.ifEmpty { null }), onBack = pop, onOpenLog = openLog,
                            onOpenAccess = { network -> push("access:${if (network) "network" else "permissions"}|${uid}|${pkg}") })
                    }
                    top.startsWith("access:") -> {
                        val (mode, uid, pkg) = top.removePrefix("access:").split('|', limit = 3)
                        com.suanran.shushu.ui.AppAccessScreen(
                            controller, labels, AppKey(uid.toInt(), pkg), network = mode == "network", onBack = pop
                        )
                    }
                    top.startsWith("log:") -> {
                        // A record kept only in memory is gone after the process was recreated.
                        val entry = OpenedLogs.get(top.removePrefix("log:").toInt())
                        if (entry == null) LaunchedEffect(top) { pop() }
                        else LogDetailScreen(controller, labels, entry, onBack = pop, onOpenApp = openApp, onOpenLog = openLog)
                    }
                    top.startsWith("group:") -> GroupScreen(controller, labels, top.removePrefix("group:"), onBack = pop,
                        onOpenLog = openLog, onOpenApp = openApp)
                    top.startsWith("attention:") -> AttentionScreen(controller, labels, top.removePrefix("attention:"), onBack = pop,
                        onOpenApp = { push("app:${it.uid}|${it.pkg.orEmpty()}") })
                    top == "pairing" -> PairingScreen(controller, onBack = pop, onPair = ::pair,
                        onOpenWirelessDebugging = ::openWirelessDebugging)
                    top == "permManager" -> PermissionManagerScreen(controller, labels,
                        onOpenAccess = { key -> push("access:permissions|${key.uid}|${key.pkg.orEmpty()}") }, onBack = pop)
                    top == "spool" -> SpoolScreen(controller, onBack = pop)
                    top == "about" -> AboutScreen(labels, onBack = pop, onOpenLicenses = { push("licenses") })
                    top == "licenses" -> LicensesScreen(onBack = pop, onOpenNotice = { push("notice:$it") })
                    top.startsWith("notice:") -> NoticeScreen(top.removePrefix("notice:").toInt(), onBack = pop)
                    top == "export" -> ExportScreen(controller, labels, onBack = pop)
                    top == "sessions" -> SessionsScreen(controller, onBack = pop, onChosen = {
                        (1..stack.size).forEach { pages.removeState(pageKey(it)) }; stack = listOf()
                    })
                    top == "theme" -> ThemeScreen(preferences, onBack = pop)
                    top == "language" -> LanguageScreen(preferences, onBack = pop)
                } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controller.onAppResumed()
        controller.refresh()
    }

    override fun onPause() {
        controller.onAppPaused()
        super.onPause()
    }

    private fun pair() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            beginPairing()
        }
    }

    private fun beginPairing() {
        if (controller.startPairing()) openWirelessDebugging()
    }

    private fun openWirelessDebugging() {
        com.suanran.shushu.ui.openDeveloperOptions(this)
    }
}

/** How often the pages catch up with a running monitor while the app is on screen. */
private const val LIVE_REFRESH_MS = 3_000L
