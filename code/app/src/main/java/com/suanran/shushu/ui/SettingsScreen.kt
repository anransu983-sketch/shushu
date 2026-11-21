// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.material3.Icon
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suanran.shushu.AppLanguage
import com.suanran.shushu.AppPreferences
import com.suanran.shushu.MonitorController
import com.suanran.shushu.MonitorController.Busy
import com.suanran.shushu.R
import com.suanran.shushu.ThemeMode
import com.suanran.shushu.adb.AirModePairingManager.PairingState
import com.suanran.shushu.adb.AirModePairingManager.PairingTrustState
import com.suanran.shushu.adb.messageRes
import com.suanran.shushu.ui.icons.JiumiIcons
import com.suanran.shushu.ui.theme.StatusWarning
import com.suanran.shushu.ui.theme.StatusSuccess

@Composable
fun SettingsScreen(
    controller: MonitorController,
    preferences: AppPreferences,
    onBack: (() -> Unit)?,
    onOpenTheme: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenSpool: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenPermissionManager: () -> Unit = {},
    onOpenMonitor: () -> Unit = {}
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val pairingFlow = state.pairingState in setOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE, PairingState.PAIRING)
    val paired = state.trust == PairingTrustState.PAIRED
    val themeMode by preferences.themeMode.collectAsStateWithLifecycle()
    val persistence by controller.adbPersistence.collectAsStateWithLifecycle()

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by remember { androidx.compose.runtime.derivedStateOf { list.canScrollBackward } }
    // As a tab, under the top bar that slides away; opened from elsewhere, as a page with a back arrow.
    val page: @Composable (@Composable (PaddingValues, androidx.compose.ui.unit.Dp) -> Unit) -> Unit = { body ->
        if (onBack == null) HidingTopBar(stringResource(R.string.settings_title), scrolled) { top -> body(PaddingValues(), top + PageTopGap) }
        else SubScreen(stringResource(R.string.settings_title), onBack) { padding -> body(padding, 16.dp) }
    }
    page { padding, top ->
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = 32.dp + LocalTabBarInset.current)
        ) {
            // Every row is a title alone, in one size; what a setting means is said beneath its card.
            // Access comes first: the monitor can only be started once it is paired.
            item { GroupTitle(stringResource(R.string.section_access), hamster = HamsterKind.NETWORK) }
            item {
                // The three ways to start the monitor; what each needs follows beneath.
                Segmented(
                    com.suanran.shushu.access.AccessMethod.entries.map { accessName(it) },
                    selected = state.method.ordinal,
                    onSelect = { controller.selectAccess(com.suanran.shushu.access.AccessMethod.entries[it]) },
                    modifier = Modifier.fillMaxWidth(1f)
                )
                GroupFooter(stringResource(R.string.access_pick_one))
                Spacer(Modifier.height(12.dp))
            }
            if (state.method == com.suanran.shushu.access.AccessMethod.ROOT) item {
                Card {
                    ListRow(stringResource(R.string.access_root), leading = { IconTile(JiumiIcons.Shield) },
                        onClick = controller::authorizeRoot) {
                        TrailingValue(stringResource(rootStatus(state.root)),
                            color = if (rootError(state.root) == null) MaterialTheme.colorScheme.onSurfaceVariant else StatusWarning)
                        Chevron()
                    }
                }
                // Why the last try failed comes first; what this way is, after.
                rootError(state.root)?.let { GroupFooter(stringResource(it), warning = true) }
                GroupFooter(accessDescription(state.method))
            }
            if (state.method == com.suanran.shushu.access.AccessMethod.SHIZUKU) item {
                // Shizuku may have been started or stopped while away; read it again when shown.
                androidx.compose.runtime.LaunchedEffect(Unit) { controller.access.shizuku.refresh() }
                Card {
                    ListRow(stringResource(R.string.access_shizuku), leading = { IconTile(JiumiIcons.Shield) },
                        onClick = { runShizukuNextAction(controller, state.shizuku) }) {
                        TrailingValue(shizukuStatus(state.shizuku),
                            color = if (state.ready) MaterialTheme.colorScheme.onSurfaceVariant else StatusWarning)
                        Chevron()
                    }
                }
                if (state.problem == MonitorController.Problem.SHIZUKU) GroupFooter(stringResource(R.string.error_shizuku), warning = true)
                GroupFooter(accessDescription(state.method))
            }
            if (state.method == com.suanran.shushu.access.AccessMethod.WIRELESS) item {
                val usbOn = remember(persistence, state.pairingState) { controller.usbDebuggingEnabled() }
                val needsUsb = persistence && usbOn == false
                val context = androidx.compose.ui.platform.LocalContext.current
                Card {
                    ListRow(
                        stringResource(R.string.row_pairing),
                        leading = { IconTile(JiumiIcons.Wifi) },
                        onClick = onOpenPairing
                    ) {
                        TrailingValue(stringResource(when {
                            pairingFlow -> R.string.pairing_in_progress
                            state.pairingState == PairingState.CONNECTING -> R.string.pairing_connecting
                            paired -> R.string.pairing_paired
                            state.trust == PairingTrustState.PAIRING_REQUIRED -> R.string.pairing_required
                            else -> R.string.pairing_unpaired
                        }), color = if (paired || pairingFlow) MaterialTheme.colorScheme.onSurfaceVariant else StatusWarning)
                        Chevron()
                    }
                }
                GroupFooter(stringResource(R.string.settings_pairing_desc))
                Spacer(Modifier.height(16.dp))
                // ADB persistence: start the monitor without Wi-Fi, through a local port, until a restart.
                Card {
                    ListRow(
                        stringResource(R.string.settings_adb_persistence),
                        leading = { IconTile(JiumiIcons.Link) },
                        onClick = {
                            if (needsUsb) openDeveloperOptions(context, KEY_USB_DEBUGGING)
                            else controller.setAdbPersistence(!persistence)
                        }
                    ) {
                        JiumiSwitch(persistence, onCheckedChange = controller::setAdbPersistence)
                    }
                }
                GroupFooter(stringResource(if (needsUsb) R.string.settings_adb_persistence_usb_required else R.string.settings_adb_persistence_desc),
                    warning = needsUsb)
            }
            item { GroupTitle(stringResource(R.string.section_monitoring), Modifier.padding(top = 24.dp), hamster = HamsterKind.MONITOR) }
            item {
                Card {
                    // Where the monitor stands, as the Monitor tab shows it at the same moment; tapped,
                    // that tab, where it is started and stopped.
                    val failed = state.problem != null && !state.running && state.busy == null
                    ListRow(stringResource(R.string.row_service), leading = { IconTile(JiumiIcons.Shield) }, onClick = onOpenMonitor) {
                        TrailingValue(when {
                            state.busy == MonitorController.Busy.STOPPING -> stringResource(R.string.service_stopping)
                            state.busy == MonitorController.Busy.STARTING && state.pairingState == PairingState.CONNECTING ->
                                stringResource(R.string.pairing_connecting)
                            state.busy == MonitorController.Busy.STARTING -> stringResource(R.string.service_starting)
                            state.running -> listOfNotNull(stringResource(R.string.service_running),
                                state.footprint?.let { "${(it.pssKb / 1024).toInt()} MB" }).joinToString(" · ")
                            failed -> stringResource(R.string.service_failed)
                            else -> stringResource(R.string.hero_idle)
                        }, color = if (failed) StatusWarning else MaterialTheme.colorScheme.onSurfaceVariant)
                        Chevron()
                    }
                    InsetDivider()
                    ListRow(stringResource(R.string.settings_spool), leading = { IconTile(JiumiIcons.Storage) }, onClick = onOpenSpool) {
                        TrailingValue(spoolLabel(state.spoolLimitMb))
                        Chevron()
                    }
                    InsetDivider()
                    ListRow(stringResource(R.string.settings_sessions), leading = { IconTile(JiumiIcons.History) }, onClick = onOpenSessions) {
                        Chevron()
                    }
                }
                if (state.problem != null && !state.running && state.busy == null) statusMessage(state)?.let { GroupFooter(it, warning = true) }
            }
            item { GroupTitle(stringResource(R.string.section_data), Modifier.padding(top = 24.dp), hamster = HamsterKind.EXPORT) }
            item {
                Card {
                    ListRow(stringResource(R.string.settings_export), leading = { IconTile(JiumiIcons.Download) }, onClick = onOpenExport) {
                        Chevron()
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
            item { GroupTitle(stringResource(R.string.section_manage), hamster = HamsterKind.PERMISSION) }
            item {
                // Turning it on is a deliberate step: it asks, with what it means, before it takes effect.
                var askAllowRemove by remember { mutableStateOf(false) }
                Card {
                    ListRow(
                        stringResource(R.string.settings_allow_remove),
                        leading = { IconTile(JiumiIcons.Trash) },
                        // The whole row turns it, as on iOS; the switch alone is a small target.
                        onClick = {
                            if (!state.allowRemovePreinstalled) askAllowRemove = true else controller.setAllowRemovePreinstalled(false)
                        }
                    ) {
                        JiumiSwitch(state.allowRemovePreinstalled) { on ->
                            if (on) askAllowRemove = true else controller.setAllowRemovePreinstalled(false)
                        }
                    }
                    InsetDivider()
                    ListRow(stringResource(R.string.access_permissions), leading = { IconTile(JiumiIcons.Lock) },
                        onClick = onOpenPermissionManager) { Chevron() }
                }
                GroupFooter(stringResource(R.string.settings_allow_remove_desc))
                Spacer(Modifier.height(16.dp))
                Card { ExpertModeRow(state.expertMode, controller::setExpertMode) }
                GroupFooter(stringResource(R.string.settings_expert_mode_desc))
                if (askAllowRemove) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { askAllowRemove = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        icon = { Icon(JiumiIcons.Warning, null, tint = com.suanran.shushu.ui.theme.StatusWarning) },
                        title = { Text(stringResource(R.string.allow_remove_title), style = MaterialTheme.typography.titleMedium) },
                        text = {
                            Text(emphasizeAppName(stringResource(R.string.allow_remove_body)), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                askAllowRemove = false
                                controller.setAllowRemovePreinstalled(true)
                            }) {
                                Text(stringResource(R.string.allow_remove_confirm), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { askAllowRemove = false }) {
                                Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
            item { GroupTitle(stringResource(R.string.section_appearance_about), hamster = HamsterKind.SETTINGS) }
            item {
                Card {
                    ListRow(stringResource(R.string.settings_theme), leading = { IconTile(JiumiIcons.Palette) }, onClick = onOpenTheme) {
                        TrailingValue(themeLabel(themeMode))
                        Chevron()
                    }
                    InsetDivider()
                    ListRow(stringResource(R.string.settings_language), leading = { IconTile(JiumiIcons.Languages) }, onClick = onOpenLanguage) {
                        TrailingValue(languageLabel(preferences.language))
                        Chevron()
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
            item {
                Card {
                    val update by com.suanran.shushu.update.UpdateNotice.available.collectAsStateWithLifecycle()
                    ListRow(stringResource(R.string.settings_about), leading = { IconTile(JiumiIcons.Info) }, onClick = onOpenAbout) {
                        if (update != null) { NewDot(); TrailingValue(stringResource(R.string.update_new_badge)) }
                        Chevron()
                    }
                }
            }
        }
    }
}

@Composable
fun ThemeScreen(preferences: AppPreferences, onBack: () -> Unit) {
    val mode by preferences.themeMode.collectAsStateWithLifecycle()
    SubScreen(stringResource(R.string.settings_theme), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).selectableGroup(), contentPadding = PaddingValues(16.dp)) {
            item {
                Card {
                    ThemeMode.entries.forEachIndexed { index, value ->
                        if (index > 0) InsetDivider(start = 16.dp)
                        SelectionRow(themeLabel(value), selected = mode == value) { preferences.setThemeMode(value) }
                    }
                }
            }
        }
    }
}

@Composable
fun LanguageScreen(preferences: AppPreferences, onBack: () -> Unit) {
    // The activity is recreated after a change, so the current value is read on each composition.
    val current = preferences.language
    SubScreen(stringResource(R.string.settings_language), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).selectableGroup(), contentPadding = PaddingValues(16.dp)) {
            item {
                Card {
                    AppLanguage.entries.forEachIndexed { index, value ->
                        if (index > 0) InsetDivider(start = 16.dp)
                        SelectionRow(languageLabel(value), selected = current == value) {
                            if (current != value) preferences.setLanguage(value)
                        }
                    }
                }
            }
        }
    }
}

/** Spool limits offered in Settings, in MB; 0 keeps everything until storage runs low. */
val SPOOL_CHOICES = listOf(256, 1024, 2048, 0)

@Composable
fun spoolLabel(mb: Int): String = when {
    mb == 0 -> stringResource(R.string.spool_unlimited)
    mb >= 1024 -> "${mb / 1024} GB"
    else -> "$mb MB"
}

@Composable
fun SpoolScreen(controller: MonitorController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    SubScreen(stringResource(R.string.settings_spool), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).selectableGroup(), contentPadding = PaddingValues(16.dp)) {
            item {
                Card {
                    SPOOL_CHOICES.forEachIndexed { index, mb ->
                        if (index > 0) InsetDivider(start = 16.dp)
                        SelectionRow(spoolLabel(mb), selected = state.spoolLimitMb == mb) { controller.setSpoolLimit(mb) }
                    }
                }
            }
            item {
                Text(stringResource(R.string.spool_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

/** Every monitoring session; choosing one shows its report. */
@Composable
fun SessionsScreen(controller: MonitorController, onBack: () -> Unit, onChosen: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val sessions by androidx.compose.runtime.produceState<List<MonitorController.SessionView>?>(null, state.ledger) {
        value = controller.sessions()
    }
    SubScreen(stringResource(R.string.settings_sessions), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            val list = sessions
            if (list != null && list.isEmpty()) item { EmptyCard(stringResource(R.string.sessions_empty)) }
            if (!list.isNullOrEmpty()) item {
                Card {
                    list.forEachIndexed { index, s ->
                        if (index > 0) InsetDivider()
                        ListRow(
                            title = sessionSpan(s.session.start, s.session.end),
                            subtitle = listOfNotNull(
                                stringResource(R.string.session_coverage, formatDuration(s.coverage.coveredMs)),
                                s.coverage.interruptions.takeIf { it > 0 }?.let {
                                    pluralStringResource(R.plurals.interruptions, it, formatCount(it.toLong()))
                                },
                                s.coverage.lostEvents.takeIf { it > 0 }?.let {
                                    pluralStringResource(R.plurals.lost_records, it.toInt(), formatCount(it))
                                }
                            ).joinToString(" · "),
                            leading = { IconTile(JiumiIcons.History) },
                            onClick = { controller.selectSession(s.session.id); onChosen() }
                        ) { Chevron() }
                    }
                }
            }
        }
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
)

@Composable
private fun languageLabel(language: AppLanguage): String = stringResource(
    when (language) {
        AppLanguage.SYSTEM -> R.string.language_system
        AppLanguage.SIMPLIFIED_CHINESE -> R.string.language_zh
        AppLanguage.ENGLISH -> R.string.language_en
    }
)

/**
 * Expert mode: most core system components may be changed too. Like removing preinstalled apps,
 * turning it on asks first and says what is at stake; turning it off takes effect at once.
 */
@Composable
private fun ExpertModeRow(on: Boolean, onChange: (Boolean) -> Unit) {
    var asking by remember { mutableStateOf(false) }
    ListRow(
        stringResource(R.string.settings_expert_mode),
        leading = { IconTile(JiumiIcons.Cpu) },
        onClick = { if (on) onChange(false) else asking = true }
    ) {
        JiumiSwitch(on) { if (it) asking = true else onChange(false) }
    }
    if (asking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = false },
            containerColor = MaterialTheme.colorScheme.surface,
            icon = { Icon(JiumiIcons.Warning, null, tint = com.suanran.shushu.ui.theme.StatusWarning) },
            title = { Text(stringResource(R.string.expert_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(emphasizeAppName(stringResource(R.string.expert_body)), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { asking = false; onChange(true) }) {
                    Text(stringResource(R.string.expert_confirm), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { asking = false }) {
                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }
}

/** What the setting in the card above means, in small grey type beneath it, as iOS groups explain themselves. */
@Composable
internal fun GroupFooter(text: String, warning: Boolean = false) {
    Text(if (warning) androidx.compose.ui.text.AnnotatedString(text) else emphasizeAppName(text), style = MaterialTheme.typography.bodySmall,
        color = if (warning) StatusWarning else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
}
