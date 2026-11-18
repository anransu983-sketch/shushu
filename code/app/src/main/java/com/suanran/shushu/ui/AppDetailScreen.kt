// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suanran.shushu.MonitorController
import com.suanran.shushu.MonitorController.ManageResult
import com.suanran.shushu.MonitorController.ManageStatus
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.launch
import com.suanran.shushu.data.Refusal
import com.suanran.shushu.data.Management
import com.suanran.shushu.data.AppSource
import com.suanran.shushu.data.Risk
import com.suanran.shushu.data.ManageAction
import com.suanran.shushu.data.Report
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.KeyboardOptions
import com.suanran.shushu.MonitorController.Range
import com.suanran.shushu.R
import com.suanran.shushu.data.AppDetail
import com.suanran.shushu.data.AppKey
import com.suanran.shushu.data.AppKind
import com.suanran.shushu.data.Destination
import com.suanran.shushu.data.LogEntry
import com.suanran.shushu.data.Segments
import com.suanran.shushu.data.TimelineItem
import com.suanran.shushu.ui.icons.JiumiIcons
import com.suanran.shushu.ui.theme.AppIconSize
import com.suanran.shushu.ui.theme.StatusWarning
import java.util.TimeZone

/**
 * One app's report over the chosen range, after the App Privacy Report: a chart of how often, what it
 * did with each kind of data, the addresses it talked to, then its behaviors on a time axis.
 */
@Composable
fun AppDetailScreen(
    controller: MonitorController,
    labels: AppLabels,
    key: AppKey,
    onBack: () -> Unit,
    onOpenLog: (LogEntry) -> Unit,
    onOpenAccess: (Boolean) -> Unit
) {
    val state by controller.state.collectAsStateWithLifecycle()
    // Reload when the range changes or a sync rebuilt the report.
    val detail by produceState<AppDetail?>(null, key, state.range, state.ledger) {
        value = controller.detail(key)
    }
    val summary = state.ledger?.apps?.firstOrNull { it.key == key }
    var manageRevision by remember(key.pkg) { mutableStateOf(0) }
    val managed by produceState<MonitorController.Managed?>(null, key.pkg, state.ledger, manageRevision) {
        value = key.pkg?.let { controller.managed(it) }
    }
    // Read from the system while the monitor runs; nothing to show without it.
    val wakeups by produceState<com.suanran.shushu.data.Wakeups?>(null, key.pkg, state.running) {
        value = key.pkg?.takeIf { state.running }?.let { controller.wakeups(it) }
    }
    val name = managed?.backup?.takeIf { managed?.facts == null }?.label ?: labels.label(key)
    val routine = detail?.routine ?: false
    var category by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var shown by rememberSaveable(key) { mutableStateOf(LOG_PAGE) }
    var allDestinations by rememberSaveable(key) { mutableStateOf(false) }
    val list = rememberLazyListState()

    val all = detail?.items.orEmpty()
    val shownItems = all.filter { category == null || categoryOf(it) == category }
    val toggled = rememberToggledDays(key, state.range, category)
    val layout = remember(shownItems, shown, toggled.value) { timelineRows(shownItems, shown, toggled.value) { it.t } }
    LoadMoreOnScroll(list, more = layout.more) { shown += LOG_PAGE }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = list,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)
    ) {
        item {
            IconButton(onClick = onBack) {
                Icon(JiumiIcons.ArrowBack, contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                val facts = managed?.facts
                val fromBackup = facts == null && managed?.backup != null
                val deleted = facts?.installed == false || fromBackup
                val frozen = facts?.let { it.installed && it.disabledByUser && !it.suspendedByShell } == true
                val badge = when {
                    deleted -> JiumiIcons.Trash
                    frozen -> JiumiIcons.Snowflake
                    else -> null
                }
                if (badge != null && key.pkg != null) {
                    AppIconWithBadge(controller, labels, key.pkg, 44, badge, fromBackup = fromBackup)
                } else AppIcon(labels, key.pkg, 44)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        color = if (badge != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(summary?.let { kindName(it.kind) }, key.pkg ?: "UID ${key.uid}").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Where it came from: the store by name, installed directly, or part of the phone.
                    facts?.let { f ->
                        val source = when (Management.source(f)) {
                            AppSource.STORE -> stringResource(R.string.source_from,
                                labels.label(com.suanran.shushu.data.AppKey(0, f.installer)))
                            AppSource.SIDELOADED -> stringResource(R.string.source_detail_sideloaded)
                            AppSource.PREINSTALLED -> stringResource(R.string.source_detail_preinstalled)
                        }
                        Text(source, style = MaterialTheme.typography.labelMedium, color = com.suanran.shushu.ui.theme.Ink.tertiary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        // What may be done to the app comes first, right under its name.
        managed?.let { m ->
            item(key = "manage") {
                ManageBar(controller, m, name, monitorRunning = state.running,
                    onChanged = { manageRevision++ }, onDiscarded = onBack)
            }
        }
        if (key.pkg != null && (managed == null || managed?.facts?.installed == true)) {
            item(key = "access") {
                Spacer(Modifier.height(16.dp))
                Card {
                    ListRow(stringResource(R.string.access_permissions), leading = { IconTile(JiumiIcons.Lock) },
                        onClick = { onOpenAccess(false) }) { Chevron() }
                    InsetDivider()
                    ListRow(stringResource(R.string.access_network), leading = { IconTile(JiumiIcons.Network) },
                        onClick = { onOpenAccess(true) }) { Chevron() }
                }
            }
        }
        item {
            Segmented(
                Range.entries.map { rangeLabel(it, state.session) },
                selected = state.range.ordinal,
                onSelect = { controller.setRange(Range.entries[it]); shown = LOG_PAGE },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
            )
        }

        // Behaviors: a chart of how often (of the chosen kind, when one is chosen), then one row per kind.
        val summaries = detail?.summaries.orEmpty()
        val chart = summaries.firstOrNull { it.category == category }?.buckets ?: detail?.chart
        if (summaries.isNotEmpty()) {
            item { GroupTitle(stringResource(R.string.section_did), Modifier.padding(top = 24.dp)) }
            item {
                Card {
                    if (chart != null) {
                        BehaviorChart(chart, routine)
                        InsetDivider(start = 16.dp)
                    }
                    summaries.forEachIndexed { index, s ->
                        if (index > 0) InsetDivider(start = 16.dp)
                        SummaryRow(s, groupIcon(detail?.groupOf?.get(s.category)?.let { detail?.groupIcons?.get(it) }.orEmpty()),
                            routine, selected = category == s.category) {
                            category = if (category == s.category) null else s.category
                            shown = LOG_PAGE
                        }
                    }
                }
            }
        }

        // What Android itself counted, per kind of data: also days before monitoring and parts it can't watch.
        val systemCounts = detail?.systemCounts.orEmpty()
        if (systemCounts.isNotEmpty()) {
            item { GroupTitle(stringResource(R.string.section_system_counts), Modifier.padding(top = 24.dp)) }
            item {
                Card {
                    systemCounts.forEachIndexed { index, c ->
                        if (index > 0) InsetDivider(start = 16.dp)
                        val counts = if (c.rejected > 0) stringResource(R.string.system_counts_row_refused,
                            formatCount(c.fg), formatCount(c.bg), formatCount(c.rejected))
                        else stringResource(R.string.system_counts_row, formatCount(c.fg), formatCount(c.bg))
                        val title = if (c.vendor) stringResource(R.string.vendor_op, c.category.removePrefix("vendor:"))
                            else categoryName(c.category)
                        ListRow(title, subtitle = counts,
                            subtitleColor = if (c.bg > 0 && !routine) StatusWarning else MaterialTheme.colorScheme.onSurfaceVariant,
                            leading = { IconTile(if (c.vendor) JiumiIcons.Info
                                else groupIcon(detail?.groupOf?.get(c.category)?.let { detail?.groupIcons?.get(it) }.orEmpty())) })
                    }
                }
            }
            item {
                Text(stringResource(R.string.system_counts_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            }
        }

        wakeups?.takeIf { it.total > 0 }?.let { wakeupItems(it) }

        // Network activity, one list: where it connected, latest first, each with how often its domain was
        // looked up; then the domains it only looked up. Apart from what it read.
        val destinations = detail?.destinations.orEmpty()
        val lookedUpOnly = detail?.lookedUpOnly.orEmpty()
        val traffic = detail?.traffic
        val hasTraffic = traffic != null && traffic.total > 0
        if (destinations.isNotEmpty() || lookedUpOnly.isNotEmpty() || hasTraffic) {
            item { GroupTitle(stringResource(R.string.section_network_activity), Modifier.padding(top = 24.dp)) }
            item {
                Card {
                    if (hasTraffic) {
                        ListRow(stringResource(R.string.net_totals, formatBytes(traffic!!.tx), formatBytes(traffic.rx)),
                            leading = { IconTile(JiumiIcons.Network) })
                    }
                    val total = destinations.size + lookedUpOnly.size
                    val budget = if (allDestinations) total else DESTINATIONS_SHOWN
                    val shownDestinations = destinations.take(budget)
                    val shownLookups = lookedUpOnly.take((budget - shownDestinations.size).coerceAtLeast(0))
                    shownDestinations.forEachIndexed { index, d ->
                        if (index > 0 || hasTraffic) InsetDivider(start = 16.dp)
                        DestinationRow(d) { onOpenLog(LogEntry(key, d.latest)) }
                    }
                    shownLookups.forEachIndexed { index, d ->
                        if (index > 0 || shownDestinations.isNotEmpty() || hasTraffic) InsetDivider(start = 16.dp)
                        DomainRow(d)
                    }
                    if (!allDestinations && total > DESTINATIONS_SHOWN) {
                        InsetDivider(start = 16.dp)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                            ActionText(stringResource(R.string.show_all_destinations, formatCount(total.toLong()))) {
                                allDestinations = true
                            }
                        }
                    }
                }
            }
            // Network records carry only the uid; say so when several components share it.
            val shared = detail?.sharedUid.orEmpty()
            if (shared.isNotEmpty()) item {
                Text(stringResource(R.string.shared_uid_note, formatCount(shared.size.toLong()), key.uid.toString(),
                    shared.joinToString(stringResource(R.string.list_separator))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            }
        }

        item {
            GroupTitle(stringResource(R.string.section_timeline), Modifier.padding(top = 24.dp)) {
                category?.let { c ->
                    ActionText(categoryName(c) + " ✕", color = MaterialTheme.colorScheme.onSurface) { category = null }
                }
            }
        }
        if (detail != null && shownItems.isEmpty()) {
            item { EmptyCard(stringResource(R.string.timeline_empty)) }
        }
        val groupOf = detail?.groupOf.orEmpty()
        val groupIcons = detail?.groupIcons.orEmpty()
        timelineItems(layout.rows, "app-$category", { it }, toggled::toggle) { item, first, last ->
            LogRow(item, routine, recordIcon(item, groupOf, groupIcons), app = null, first = first, last = last,
                onClick = { onOpenLog(LogEntry(key, item)) })
        }
    }
    TimelineScrubber(list, layout.rows, Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(vertical = 24.dp))
    }
}

/** How often the app's alarms woke the phone since it started, in all and per alarm. */
private fun androidx.compose.foundation.lazy.LazyListScope.wakeupItems(w: com.suanran.shushu.data.Wakeups) {
    item { GroupTitle(stringResource(R.string.section_wakeups), Modifier.padding(top = 24.dp)) }
    item {
        Card {
            ListRow(stringResource(R.string.wakeups_total, formatCount(w.total)),
                subtitle = stringResource(R.string.wakeups_since_boot, formatDuration(w.sinceBootMs)),
                leading = { IconTile(JiumiIcons.Alarm) })
            w.receivers.forEach { r ->
                InsetDivider(start = 16.dp)
                ListRow(r.name, subtitle = stringResource(R.string.wakeup_receiver_row, formatCount(r.wakes),
                    stringResource(R.string.recent_at, shortTime(r.lastAt))))
            }
        }
    }
    item {
        Text(stringResource(R.string.wakeups_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    }
}

/** An address: time first, then where, how often and how much when the platform counts it. */
@Composable
private fun DestinationRow(d: Destination, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        TimeColumn(d.last)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(destinationLabel(d.label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val parts = buildList {
                // Counts first; the address and ports are on the record's page.
                add(pluralStringResource(R.plurals.dest_conversations, d.conversations, formatCount(d.conversations.toLong())))
                if (d.lookups > 0) add(pluralStringResource(R.plurals.domain_lookups, d.lookups, formatCount(d.lookups.toLong())))
                d.sent?.takeIf { it > 0 }?.let { add(stringResource(R.string.uploaded, formatBytes(it))) }
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
        }
        Chevron()
    }
}

/** A domain the app only looked up, no connection to it seen: when last, how often, whether it was found. */
@Composable
private fun DomainRow(d: com.suanran.shushu.data.DomainSummary) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        TimeColumn(d.last)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(d.domain, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val parts = buildList {
                add(stringResource(R.string.domain_lookup_only))
                add(pluralStringResource(R.plurals.domain_lookups, d.lookups, formatCount(d.lookups.toLong())))
                if (!d.found) add(stringResource(R.string.domain_not_found))
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Addresses listed before the rest are asked for. */
private const val DESTINATIONS_SHOWN = 8

/**
 * The app's actions as a row of buttons: disable or enable, delete; restore or discard the backup of
 * a deleted app. Disabling, removing, deleting and discarding ask first; undoing asks nothing. An
 * app left alone says why instead.
 */
@Composable
private fun ManageBar(controller: MonitorController, m: MonitorController.Managed, name: String, monitorRunning: Boolean,
                      onChanged: () -> Unit, onDiscarded: () -> Unit) {
    val scope = rememberCoroutineScope()
    var asking by rememberSaveable(m.pkg) { mutableStateOf<String?>(null) }
    var outcome by rememberSaveable(m.pkg, stateSaver = manageResultSaver) { mutableStateOf<ManageResult?>(null) }
    var working by remember(m.pkg) { mutableStateOf(false) }
    // The buttons always answer; a monitor that is not running is started on the way.
    val run = { action: ManageAction ->
        asking = null
        working = true
        outcome = null
        scope.launch {
            try {
                outcome = controller.manage(m.pkg, action)
                onChanged()
            } finally {
                working = false
            }
        }
        Unit
    }
    // A deleted app's backup can also be thrown away.
    val discard = m.backup != null && m.facts == null
    Column(Modifier.padding(top = 20.dp)) {
        if (m.actions.isNotEmpty() || discard) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.actions.forEach { action ->
                    ManageButton(stringResource(actionLabel(action)), actionIcon(action), actionTone(action), enabled = !working,
                        Modifier.weight(1f)) {
                        if (Management.isUndo(action)) run(action) else asking = action.id
                    }
                }
                if (discard) ManageButton(stringResource(R.string.manage_action_discard), JiumiIcons.Trash, Tone.DANGER,
                    enabled = !working, Modifier.weight(1f)) {
                    asking = DISCARD
                }
            }
        }
        val status = outcome?.status
        val explanation = when (status) {
            ManageStatus.NO_MONITOR -> R.string.manage_monitor_failed
            ManageStatus.PERMISSION_DENIED -> R.string.manage_permission_denied
            ManageStatus.NOT_APPLIED -> R.string.manage_not_applied
            ManageStatus.UNVERIFIED -> R.string.manage_unverified_explanation
            ManageStatus.FAILED -> R.string.manage_failed_explanation
            else -> null
        }
        val failureTitle = when {
            status == ManageStatus.UNVERIFIED -> R.string.manage_unverified
            explanation != null -> R.string.manage_failed
            else -> null
        }
        val facts = m.facts
        val note = when {
            working -> stringResource(if (monitorRunning) R.string.manage_working else R.string.manage_starting_monitor)
            failureTitle != null -> null
            m.refusal != null && m.expertUnlocks -> stringResource(R.string.refusal_platform_expert)
            m.refusal != null -> refusalText(m.refusal)
            outcome == null && m.removable -> stringResource(R.string.remove_hint)
            outcome == null && m.risk != null && m.actions.any { !Management.isUndo(it) } -> stringResource(R.string.risk_note)
            else -> null
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = if (m.actions.isNotEmpty() || discard) 8.dp else 0.dp))
        }
        failureTitle?.let { ManageNotice(it, explanation, warning = true) }
        val process = when (status) {
            ManageStatus.DISABLED, ManageStatus.REMOVED -> when (outcome?.running) {
                false -> R.string.manage_process_stopped
                true -> R.string.manage_process_running
                null -> R.string.manage_process_unknown
            }
            else -> null
        }
        when {
            facts?.installed == false && facts.preinstalled ->
                ManageNotice(R.string.state_deleted, R.string.manage_preinstalled_deleted_explanation, process, warning = outcome?.running == true)
            discard -> ManageNotice(R.string.state_deleted, R.string.manage_backup_deleted_explanation)
            facts?.installed == false -> ManageNotice(R.string.state_deleted, null)
            facts?.installed == true && facts.suspendedByShell ->
                ManageNotice(R.string.state_suspended, R.string.manage_suspended_explanation)
            facts?.installed == true && facts.disabledByUser ->
                ManageNotice(R.string.state_disabled, R.string.manage_disabled_explanation, process,
                    warning = outcome?.running == true)
        }
    }
    when (val a = asking) {
        null -> Unit
        DISCARD -> ConfirmDiscard(name, onConfirm = {
            asking = null
            // The page goes back once the backup is gone, so the removal shows.
            scope.launch { controller.discardBackup(m.pkg); onDiscarded() }
        }, onDismiss = { asking = null })
        else -> ManageAction.of(a)?.let { action ->
            ConfirmManage(action, name, m.pkg, m.risk, onConfirm = { run(action) }, onDismiss = { asking = null })
        }
    }
}

@Composable
private fun ManageNotice(title: Int, explanation: Int?, process: Int? = null, warning: Boolean = false) {
    Spacer(Modifier.height(8.dp))
    Card {
        ListRow(stringResource(title), leading = { IconTile(if (warning) JiumiIcons.Warning else JiumiIcons.Info) }, extra = {
            explanation?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            process?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                    color = if (warning) StatusWarning else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
        })
    }
}

private val manageResultSaver = listSaver<ManageResult?, String>(
    save = { result ->
        if (result == null) emptyList() else listOf(result.status.name, result.running?.toString().orEmpty())
    },
    restore = { parts ->
        ManageResult(ManageStatus.valueOf(parts.first()), parts.last().toBooleanStrictOrNull())
    }
)

private const val DISCARD = "discard"

/**
 * Buttons stay neutral, grey tiles alike, so the page stays calm; only what deletes carries a muted
 * red, in its symbol and word. The difference is said again, plainly, by the confirmation.
 */
private enum class Tone { NEUTRAL, DANGER }

private fun actionTone(action: ManageAction) = when (action) {
    ManageAction.UNINSTALL, ManageAction.REMOVE -> Tone.DANGER
    else -> Tone.NEUTRAL
}

private fun actionIcon(action: ManageAction) = when (action) {
    ManageAction.ENABLE, ManageAction.UNSUSPEND -> JiumiIcons.Play
    ManageAction.RESTORE, ManageAction.REINSTALL -> JiumiIcons.Undo
    ManageAction.DISABLE -> JiumiIcons.Ban
    ManageAction.UNINSTALL, ManageAction.REMOVE -> JiumiIcons.Trash
}

/** A tile sharing the row evenly: its symbol over its word, as iOS lays out a contact's actions. */
@Composable
private fun ManageButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tone: Tone, enabled: Boolean,
                         modifier: Modifier, onClick: () -> Unit) {
    val content = if (tone == Tone.DANGER) MaterialTheme.colorScheme.error.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurface
    Surface(onClick = onClick, enabled = enabled, shape = com.suanran.shushu.ui.theme.AppShapes.Control,
        color = MaterialTheme.colorScheme.surface, contentColor = content,
        modifier = modifier.height(56.dp).alpha(if (enabled) 1f else 0.5f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp, start = 6.dp, end = 6.dp))
        }
    }
}

/** Throwing a backup away cannot be undone, so it asks. */
@Composable
private fun ConfirmDiscard(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.confirm_discard_title, name), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold) },
        text = { Text(stringResource(R.string.confirm_discard_body), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.manage_action_discard), fontWeight = FontWeight.SemiBold, color = StatusWarning)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurface) }
        }
    )
}

/**
 * Asks before a change that is not simply undone; deleting needs the package name typed out. An app
 * the phone leans on ([risk]) says so first, and nothing goes ahead until the risk is acknowledged.
 */
@Composable
private fun ConfirmManage(action: ManageAction, name: String, pkg: String, risk: Risk?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by rememberSaveable(pkg, action) { mutableStateOf("") }
    var acknowledged by rememberSaveable(pkg, action) { mutableStateOf(false) }
    val (title, body) = when (action) {
        ManageAction.UNINSTALL -> R.string.confirm_uninstall_title to R.string.confirm_uninstall_body
        ManageAction.REMOVE -> R.string.confirm_remove_title to R.string.confirm_remove_body
        else -> R.string.confirm_disable_title to R.string.confirm_disable_body
    }
    val typedOut = (action != ManageAction.UNINSTALL || typed.trim() == pkg) && (risk == null || acknowledged)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(title, name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                if (risk != null) RiskWarning(risk, Modifier.padding(bottom = 12.dp))
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (risk != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp).clip(com.suanran.shushu.ui.theme.AppShapes.Control)
                            .clickable { acknowledged = !acknowledged }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = acknowledged, onCheckedChange = { acknowledged = it },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.onSurface,
                                checkmarkColor = MaterialTheme.colorScheme.surface,
                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Text(stringResource(R.string.risk_acknowledge), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (action == ManageAction.UNINSTALL) {
                    Text(pkg, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 10.dp))
                    OutlinedTextField(
                        value = typed, onValueChange = { typed = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typedOut) {
                Text(stringResource(actionLabel(action)), fontWeight = FontWeight.SemiBold,
                    color = when {
                        !typedOut -> MaterialTheme.colorScheme.onSurfaceVariant
                        action == ManageAction.DISABLE -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.error
                    })
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    )
}

/** An amber panel: what the phone may lose if this app is turned off or removed. */
@Composable
private fun RiskWarning(risk: Risk, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(com.suanran.shushu.ui.theme.AppShapes.Control)
            .background(StatusWarning.copy(alpha = 0.10f)).padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(JiumiIcons.Warning, null, tint = StatusWarning, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(stringResource(if (risk == Risk.CORE) R.string.risk_title_core else R.string.risk_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(emphasizeAppName(stringResource(when (risk) {
                Risk.CORE -> R.string.risk_core
                Risk.LISTED -> R.string.risk_listed
                Risk.BACKGROUND -> R.string.risk_background
            })),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun refusalText(r: Refusal): String = when (r.reason) {
    Refusal.Reason.SELF -> stringResource(R.string.refusal_self)
    Refusal.Reason.PLATFORM -> stringResource(R.string.refusal_platform)
    Refusal.Reason.PROTECTED -> stringResource(R.string.refusal_protected)
    Refusal.Reason.ROLE -> stringResource(R.string.refusal_role, stringResource(when (r.role) {
        "home" -> R.string.role_home
        "keyboard" -> R.string.role_keyboard
        "sms" -> R.string.role_sms
        else -> R.string.role_dialer
    }))
}
