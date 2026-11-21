// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import com.suanran.shushu.MonitorController.Range
import com.suanran.shushu.data.AppKind
import com.suanran.shushu.data.LogEntry
import com.suanran.shushu.data.TimelineItem
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suanran.shushu.MonitorController
import com.suanran.shushu.R
import com.suanran.shushu.data.AppKey
import androidx.compose.foundation.layout.fillMaxWidth
import com.suanran.shushu.ui.theme.StatusWarning
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * One kind of data over the chosen range, as a timeline of its records across apps. The network
 * ([NETWORK_GROUP]) opens by app instead, the ones that sent most in the background first, each
 * opening the app's own destinations; its timeline is the other view.
 */
@Composable
fun GroupScreen(
    controller: MonitorController,
    labels: AppLabels,
    groupId: String,
    onBack: () -> Unit,
    onOpenLog: (LogEntry) -> Unit,
    onOpenApp: (AppKey) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val network = groupId == NETWORK_GROUP
    // The runtime permissions that gate one kind of data: the switch in the by-app view
    // closes them. Kinds with no runtime permission (clipboard, app list, notification
    // listener, accessibility/VPN) get no switch; background location is left out on
    // purpose, it only refines the foreground grants the switch already closes.
    val permissions = GROUP_PERMISSIONS[groupId].orEmpty()
    var byApp by rememberSaveable(groupId) { mutableStateOf(true) }
    val state by controller.state.collectAsStateWithLifecycle()
    val expert = state.expertMode
    val ledger = state.ledger
    val title = if (groupId == NETWORK_GROUP) stringResource(R.string.section_network) else groupName(groupId)
    var shown by rememberSaveable(groupId) { mutableStateOf(LOG_PAGE) }
    val entries = remember(ledger, groupId) {
        ledger?.timeline.orEmpty().filter { e ->
            if (groupId == NETWORK_GROUP) headOf(e.item) is TimelineItem.NetItem
            else categoryOf(e.item)?.let { ledger?.groupOf?.get(it) } == groupId
        }
    }
    val routineKinds = ledger?.routineKinds.orEmpty()
    val routineKeys = remember(ledger) { ledger?.apps.orEmpty().filter { it.kind.id in routineKinds }.map { it.key }.toSet() }
    val list = rememberLazyListState()
    val toggled = rememberToggledDays(groupId, state.range)
    val layout = remember(entries, shown, toggled.value) { timelineRows(entries, shown, toggled.value) { it.item.t } }
    LoadMoreOnScroll(list, more = layout.more) { shown += LOG_PAGE }

    // One app's permission state for the by-app switch, read on demand and kept while this
    // kind is open; a toggle re-reads the app afterwards. Keyed by package: the same
    // package can arrive under two uids (a second user, a dual-apps copy, a system process
    // under two uids), and the list must show it once.
    val accessCache = remember(groupId) { mutableStateMapOf<String, JSONObject?>() }
    val accessKey = remember(groupId) { mutableStateMapOf<String, AppKey>() }
    val accessLoading = remember(groupId) { mutableStateMapOf<String, Boolean>() }
    val accessPending = remember(groupId) { mutableStateMapOf<String, Boolean>() }
    val appStats = remember(entries) {
        entries.groupBy { it.key.pkg ?: "uid:${it.key.uid}" }
            .map { (groupKey, rows) ->
                val keys = rows.map { it.key }.distinct()
                    .sortedByDescending { k -> rows.count { it.key == k } }
                GroupAppStat(groupKey, keys, rows.size, rows.maxOf { it.item.t })
            }
            .sortedWith(compareByDescending<GroupAppStat> { it.count }.thenByDescending { it.last })
    }
    fun toggleGroupAccess(stat: GroupAppStat, turnOn: Boolean) {
        val key = accessKey[stat.groupKey] ?: return
        if (accessPending[stat.groupKey] == true) return
        val rows = groupPermissionRows(accessCache[stat.groupKey], permissions)
        if (rows.none { it.switchable(expert) }) return
        accessPending[stat.groupKey] = true
        scope.launch {
            if (turnOn) {
                // Restore only what this switch itself closed; when nothing was recorded,
                // fall back to every permission of this kind that is off now.
                val restore = deniedSet(context, stat.key, groupId)
                val targets = if (restore.isNotEmpty()) rows.filter { it.name in restore }
                    else rows.filter { !it.effectiveAllowed }
                for (row in targets) {
                    if (!row.switchable(expert)) continue
                    controller.appAccess(key, if (row.forcedOff) "forceAllow" else "allow", row.name)
                }
                clearDeniedSet(context, stat.key, groupId)
            } else {
                val onRows = rows.filter { it.effectiveAllowed && it.switchable(expert) }
                storeDeniedSet(context, stat.key, groupId, onRows.map { it.name })
                for (row in onRows) {
                    controller.appAccess(key, if (row.editable) "deny" else "forceDeny", row.name)
                }
            }
            accessCache[stat.groupKey] = controller.appAccess(key, "permissions")
            accessPending[stat.groupKey] = false
        }
    }

    SubScreen(title, onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp)
        ) {
            item {
                Segmented(
                    Range.entries.map { rangeLabel(it, state.session) },
                    selected = state.range.ordinal,
                    onSelect = { controller.setRange(Range.entries[it]); shown = LOG_PAGE },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                ViewTabs(listOf(stringResource(R.string.view_by_app), stringResource(R.string.view_timeline)),
                    selected = if (byApp) 0 else 1, onSelect = { byApp = it == 0 }, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            }
            if (byApp) {
                if (network) {
                    val apps = ledger?.networkApps.orEmpty()
                    if (apps.isEmpty()) item { Column(Modifier.padding(top = 12.dp)) { EmptyCard(stringResource(R.string.record_empty_running)) } }
                    else item {
                        Column(Modifier.padding(top = 12.dp)) { Card {
                            apps.forEachIndexed { index, app ->
                                if (index > 0) InsetDivider(start = TIME_COLUMN_WIDTH + 60.dp)
                                NetworkAppRow(app, labels, app.key in routineKeys) { onOpenApp(app.key) }
                            }
                        } }
                    }
                    return@LazyColumn
                }
                if (appStats.isEmpty()) item { Column(Modifier.padding(top = 12.dp)) { EmptyCard(stringResource(R.string.record_empty_running)) } }
                else item {
                    Column(Modifier.padding(top = 12.dp)) { Card {
                        appStats.forEachIndexed { index, stat ->
                            if (index > 0) InsetDivider(start = TIME_COLUMN_WIDTH + 60.dp)
                            GroupAppRow(stat, labels, controller, permissions, expert,
                                accessCache, accessKey, accessLoading, accessPending,
                                onToggle = { on -> toggleGroupAccess(stat, on) },
                                onOpenApp = onOpenApp)
                        }
                    } }
                }
                if (permissions.isNotEmpty()) item { GroupFooter(stringResource(R.string.group_switch_hint)) }
                return@LazyColumn
            }
            // Each row names the app and what it did; the app's own page holds its summary.
            if (entries.isEmpty()) item { Column(Modifier.padding(top = 24.dp)) { EmptyCard(stringResource(R.string.record_empty_running)) } }
            timelineItems(layout.rows, "group", { it.item }, toggled::toggle) { entry, first, last ->
                LogRow(entry.item, entry.key in routineKeys, recordIcon(entry.item, ledger?.groupOf.orEmpty(), ledger?.groupIcons.orEmpty()),
                    app = entry.key to labels, first = first, last = last, onClick = { onOpenLog(entry) })
            }
        }
        if (!byApp) TimelineScrubber(list, layout.rows, Modifier.align(Alignment.CenterEnd).padding(vertical = 16.dp))
        }
    }
}

/** An app's network activity in one line: where to, how much, and what it sent in the background. */
@Composable
private fun NetworkAppRow(app: com.suanran.shushu.data.AppSummary, labels: AppLabels, routine: Boolean, onClick: () -> Unit) {
    val parts = buildList {
        if (app.destinations > 0) add(androidx.compose.ui.res.pluralStringResource(R.plurals.day_addresses, app.destinations,
            formatCount(app.destinations.toLong())))
        app.traffic?.takeIf { it.total > 0 }?.let { add(stringResource(R.string.net_totals, formatBytes(it.tx), formatBytes(it.rx))) }
            ?: app.uploaded.takeIf { it > 0 }?.let { add(stringResource(R.string.uploaded, formatBytes(it))) }
    }
    ListRow(
        title = labels.label(app.key),
        subtitle = parts.joinToString(" · "),
        leading = { TimeColumn(app.last); AppIcon(labels, app.key.pkg, LEADING_SIZE) },
        onClick = onClick,
        extra = if (app.sentInBackground > 0 && !routine) {
            { Badges { Badge(stringResource(R.string.badge_sent_in_background, formatCount(app.sentInBackground.toLong())), StatusWarning) } }
        } else null
    ) { Chevron() }
}

/** The runtime permissions that gate one kind of data, by group id. */
private val GROUP_PERMISSIONS = mapOf(
    "location" to listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
    "identity" to listOf("android.permission.READ_PHONE_STATE", "android.permission.READ_PHONE_NUMBERS"),
    "contacts" to listOf("android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS", "android.permission.GET_ACCOUNTS"),
    "communication" to listOf("android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.SEND_SMS",
        "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG", "android.permission.CALL_PHONE"),
    "calendar" to listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
    "capture" to listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
    "storage" to listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.ACCESS_MEDIA_LOCATION",
        "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"),
    "health" to listOf("android.permission.BODY_SENSORS", "android.permission.BODY_SENSORS_BACKGROUND", "android.permission.ACTIVITY_RECOGNITION")
)

private data class GroupAppStat(val groupKey: String, val keys: List<AppKey>, val count: Int, val last: Long) {
    /** The key to show: the one with the most records. */
    val key: AppKey get() = keys.first()
}

private data class GroupPermissionState(
    val name: String, val allowed: Boolean?, val editable: Boolean, val forceable: Boolean,
    val forced: Boolean, val fixed: Boolean, val uidMode: String?
) {
    /** Cut off at the AppOps layer, as the monitor reports it. */
    val forcedOff: Boolean get() = forced
    /** The system constrains this operation at the uid level, above anything the app can write. */
    val uidLimited: Boolean get() = uidMode != null && uidMode != "allow"
    /** What the app can actually do right now. */
    val effectiveAllowed: Boolean get() = allowed == true && !forcedOff
    /** Same rule as the permission page: a row the system fixed, or one it constrains above the
     * package level, has no switch, and force-off needs expert mode. */
    fun switchable(expert: Boolean): Boolean =
        (editable || forcedOff || (expert && forceable)) && allowed != null && !fixed && !uidLimited
}

/** This app's rows for the kind's permissions, from one probe read. */
private fun groupPermissionRows(state: JSONObject?, permissions: List<String>): List<GroupPermissionState> {
    val array = state?.optJSONArray("rows") ?: return emptyList()
    return List(array.length()) { i ->
        val item = array.getJSONObject(i)
        val name = item.getString("name")
        val mode = item.optString("mode").takeIf { it.isNotEmpty() }
        GroupPermissionState(name,
            item.takeUnless { it.isNull("allowed") }?.optBoolean("allowed"),
            item.optBoolean("editable"), item.optBoolean("forceable"),
            forced = if (item.has("forced")) item.optBoolean("forced") else (mode == "ignore" || mode == "deny"),
            fixed = item.optBoolean("fixed"),
            uidMode = item.takeUnless { it.isNull("uidMode") }?.optString("uidMode"))
    }.filter { it.name in permissions }
}

// What this switch itself closed, so switching it back on restores exactly that and nothing
// the app never had. The probe's own ledger records the same denials; this is only the
// switch's memory of which of the kind's permissions were on when it closed them.
private const val GROUP_SWITCH_PREFS = "group_switches"

private fun deniedSet(context: Context, key: AppKey, groupId: String): Set<String> =
    context.getSharedPreferences(GROUP_SWITCH_PREFS, Context.MODE_PRIVATE)
        .getStringSet("${key.pkg}|$groupId", emptySet()).orEmpty()

private fun storeDeniedSet(context: Context, key: AppKey, groupId: String, names: List<String>) {
    context.getSharedPreferences(GROUP_SWITCH_PREFS, Context.MODE_PRIVATE).edit()
        .putStringSet("${key.pkg}|$groupId", names.toSet()).apply()
}

private fun clearDeniedSet(context: Context, key: AppKey, groupId: String) {
    context.getSharedPreferences(GROUP_SWITCH_PREFS, Context.MODE_PRIVATE).edit()
        .remove("${key.pkg}|$groupId").apply()
}

/** One app in a kind's by-app list: how often it took this kind of data, and a switch to close it. */
@Composable
private fun GroupAppRow(
    stat: GroupAppStat, labels: AppLabels, controller: MonitorController,
    permissions: List<String>, expert: Boolean,
    accessCache: MutableMap<String, JSONObject?>, accessKey: MutableMap<String, AppKey>,
    accessLoading: MutableMap<String, Boolean>, accessPending: MutableMap<String, Boolean>,
    onToggle: (Boolean) -> Unit, onOpenApp: (AppKey) -> Unit
) {
    // The same package can arrive under two uids; the probe only answers for the uid that
    // really owns the package, so try the keys in record order until one yields this kind's
    // permissions, and switch through that one.
    LaunchedEffect(stat.groupKey) {
        if (permissions.isNotEmpty() && !accessCache.containsKey(stat.groupKey)) {
            accessLoading[stat.groupKey] = true
            try {
                for (key in stat.keys) {
                    val read = controller.appAccess(key, "permissions")
                    if (groupPermissionRows(read, permissions).isNotEmpty()) {
                        accessCache[stat.groupKey] = read
                        accessKey[stat.groupKey] = key
                        break
                    }
                }
                if (!accessCache.containsKey(stat.groupKey)) accessCache[stat.groupKey] = null
            } finally {
                accessLoading[stat.groupKey] = false
            }
        }
    }
    val state = accessCache[stat.groupKey]
    val rows = remember(state, permissions) { groupPermissionRows(state, permissions) }
    val on = rows.any { it.effectiveAllowed }
    val switchable = rows.any { it.switchable(expert) }
    ListRow(
        title = labels.label(stat.key),
        subtitle = pluralStringResource(R.plurals.records_count, stat.count, formatCount(stat.count.toLong())),
        leading = { TimeColumn(stat.last); AppIcon(labels, stat.key.pkg, LEADING_SIZE) },
        onClick = { onOpenApp(stat.key) }
    ) {
        when {
            accessLoading[stat.groupKey] == true -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.isEmpty() -> Chevron()
            switchable -> JiumiSwitch(on, enabled = accessPending[stat.groupKey] != true, onCheckedChange = onToggle)
            else -> JiumiSwitch(on, enabled = false, onCheckedChange = {})
        }
    }
}
