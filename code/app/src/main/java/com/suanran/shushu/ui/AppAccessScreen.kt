// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suanran.shushu.MonitorController
import com.suanran.shushu.R
import com.suanran.shushu.data.AppKey
import com.suanran.shushu.ui.icons.JiumiIcons
import com.suanran.shushu.ui.theme.Ink
import com.suanran.shushu.ui.theme.StatusWarning
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class PermissionRow(
    val name: String, val label: String, val group: String, val icon: ImageVector,
    val allowed: Boolean?, val editable: Boolean, val mode: String? = null, val forceable: Boolean = false
) {
    /** Cut off at the AppOps layer, whatever the permission grant says. */
    val forcedOff: Boolean get() = mode == "ignore" || mode == "deny"
    /** What the app can actually do right now. */
    val effectiveAllowed: Boolean get() = allowed == true && !forcedOff
}

@Composable
fun AppAccessScreen(controller: MonitorController, labels: AppLabels, key: AppKey, network: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by controller.state.collectAsStateWithLifecycle()
    val expert = uiState.expertMode
    val readAction = if (network) "network" else "permissions"
    var state by remember(key, network) { mutableStateOf<JSONObject?>(null) }
    var pending by remember(key, network) { mutableStateOf(true) }
    var lastAction by remember(key, network) { mutableStateOf(readAction to "") }
    fun refresh(action: String, permission: String = "") {
        if (pending) return
        pending = true
        lastAction = action to permission
        scope.launch {
            state = controller.appAccess(key, action, permission)
            pending = false
        }
    }
    LaunchedEffect(key, network) {
        state = controller.appAccess(key, readAction)
        pending = false
    }
    val lock = state?.optString("lock").orEmpty()
    val groups = remember(context) {
        val rules = JSONObject(context.assets.open("rules/permission_groups.json").bufferedReader().use { it.readText() })
        buildMap {
            for (group in rules.keys()) {
                val names = rules.getJSONArray(group)
                repeat(names.length()) { put(names.getString(it), group) }
            }
        }
    }
    val rows = remember(state, context, groups) {
        val pm = context.packageManager
        val array = state?.optJSONArray("rows") ?: JSONArray()
        List(array.length()) { i ->
            val item = array.getJSONObject(i)
            val name = item.getString("name")
            val info = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull()
            val label = info?.loadLabel(pm)?.toString() ?: name
            val editable = item.optBoolean("editable")
            val forceable = item.optBoolean("forceable")
            val mode = item.optString("mode").takeIf { it.isNotEmpty() }
            val category = groups[name]
            val controllable = editable || forceable
            val fallback = context.getString(if (controllable) R.string.access_group_other else R.string.access_group_locked)
            val labelId = category?.let {
                @Suppress("DiscouragedApi")
                context.resources.getIdentifier("access_group_$it", "string", context.packageName)
            } ?: 0
            val group = if (controllable && labelId != 0) context.getString(labelId) else fallback
            val icon = when (category) {
                "location" -> JiumiIcons.MapPin
                "camera" -> JiumiIcons.Camera
                "contacts" -> JiumiIcons.Person
                "media" -> JiumiIcons.Image
                "notifications" -> JiumiIcons.Notifications
                "phone" -> JiumiIcons.Phone
                "messages" -> JiumiIcons.MessageCheck
                "calendar" -> JiumiIcons.Calendar
                "nearby" -> JiumiIcons.Link
                "health", "microphone" -> JiumiIcons.Activity
                else -> JiumiIcons.Lock
            }
            PermissionRow(name, label, group, icon, item.takeUnless { it.isNull("allowed") }?.optBoolean("allowed"),
                editable, mode, forceable)
        }
    }
    val enabled = rows.filter { it.editable && it.allowed == true }
    val editable = rows.filter { it.editable }
    val canRestore = state?.optBoolean("restore") == true
    val permissionFailures = state?.optJSONArray("failed")
    val failedNames = permissionFailures?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty()
    val networkFailed = network && state?.optBoolean("failed") == true

    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()).padding(bottom = 32.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(JiumiIcons.ArrowBack, stringResource(R.string.action_back), tint = Ink.primary)
        }
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(labels, key.pkg, 44)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(if (network) R.string.access_network else R.string.access_permissions),
                    style = MaterialTheme.typography.headlineSmall, color = Ink.primary)
                Text(labels.label(key), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Ink.primary)
                Text(key.pkg.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Ink.secondary)
            }
        }
        Spacer(Modifier.height(20.dp))
        val readable = state != null && (state?.has(if (network) "allowed" else "rows") == true)
        if (lock.isEmpty() && readable) GroupTitle(stringResource(R.string.access_scope))
        if (network && (readable || lock.isNotEmpty() && lock != "unknown")) {
            val allowed = state?.optBoolean("allowed") == true
            Card {
                ListRow(
                    stringResource(R.string.access_network_allow),
                    subtitle = if (lock.isEmpty()) stringResource(if (allowed) R.string.access_not_blocked else R.string.access_blocked) else null,
                    leading = { IconTile(JiumiIcons.Network) },
                    onClick = if (!pending && lock.isEmpty()) ({ refresh(if (allowed) "networkDeny" else "networkAllow") }) else null
                ) {
                    if (lock.isEmpty()) JiumiSwitch(allowed, enabled = !pending) {
                        refresh(if (it) "networkAllow" else "networkDeny")
                    } else TrailingValue(stringResource(R.string.access_read_only))
                }
            }
        } else if (!network && readable) {
            if (editable.isNotEmpty()) {
                Card {
                    ListRow(
                        stringResource(R.string.access_master),
                        stringResource(when {
                            enabled.isEmpty() -> R.string.access_all_off
                            enabled.size == editable.size -> R.string.access_all_on
                            else -> R.string.access_partial
                        }),
                        leading = { IconTile(JiumiIcons.Lock) },
                        onClick = if (!pending && (enabled.isNotEmpty() || canRestore))
                            ({ refresh(if (enabled.isNotEmpty()) "denyAll" else "restoreAll") }) else null
                    ) {
                        JiumiSwitch(enabled.isNotEmpty(), enabled = !pending && (enabled.isNotEmpty() || canRestore)) {
                            refresh(if (it) "restoreAll" else "denyAll")
                        }
                    }
                }
                if (failedNames.isNotEmpty() && lastAction.first in setOf("denyAll", "restoreAll")) {
                    GroupFooter(stringResource(R.string.access_partial_result, state?.optInt("changed") ?: 0, failedNames.size), warning = true)
                }
            }
            if (rows.isEmpty()) GroupFooter(stringResource(R.string.access_no_permissions))
            rows.sortedWith(compareBy({ !(it.editable || it.forceable) }, { it.group }, { it.label }))
                .groupBy { (it.editable || it.forceable) to it.group }
                .forEach { (group, items) ->
                    GroupTitle(group.second, Modifier.padding(top = 16.dp))
                    Card {
                        items.forEachIndexed { index, row ->
                            if (index > 0) InsetDivider()
                            // A row cut off at the AppOps layer comes back through AppOps too: a plain
                            // grant would leave the operation ignored. Undoing never needs expert mode.
                            val targetAction = if (row.effectiveAllowed) {
                                if (row.editable) "deny" else "forceDeny"
                            } else if (row.forcedOff) "forceAllow" else "allow"
                            val switchable = (row.editable || row.forcedOff || (expert && row.forceable)) && row.allowed != null
                            ListRow(row.label,
                                subtitle = if (row.name in failedNames) stringResource(
                                    if (row.effectiveAllowed) R.string.access_deny_failed else R.string.access_allow_failed
                                ) else null,
                                subtitleColor = StatusWarning,
                                leading = { IconTile(row.icon) },
                                onClick = if (switchable && !pending) ({ refresh(targetAction, row.name) }) else null
                            ) {
                                if (switchable) {
                                    JiumiSwitch(row.effectiveAllowed, enabled = !pending) { on ->
                                        refresh(if (on) {
                                            if (row.forcedOff) "forceAllow" else "allow"
                                        } else if (row.editable) "deny" else "forceDeny", row.name)
                                    }
                                } else TrailingValue(stringResource(if (row.allowed == null) R.string.access_read_only else
                                    if (row.forcedOff) R.string.access_forced_denied else
                                    if (row.allowed == true) R.string.access_fixed_allowed else R.string.access_fixed_denied))
                            }
                        }
                    }
                }
        }

        when {
            pending -> {
                // Reading or applying: a spinner in the middle of the page, not one small line.
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GroupFooter(stringResource(if (lastAction.first == readAction) R.string.access_reading else R.string.access_applying))
            }
            !readable && (lock.isEmpty() || lock == "unknown") -> {
                Feedback(stringResource(R.string.access_unknown)) { refresh(readAction) }
            }
            lock.isNotEmpty() -> GroupFooter(stringResource(when (lock) {
                "shared" -> if (!network && expert) R.string.access_force_warning else R.string.access_lock_shared
                "protected" -> R.string.access_lock_protected
                "system" -> if (network) R.string.access_lock_protected
                    else if (expert) R.string.access_force_warning else R.string.access_lock_system
                "users" -> R.string.access_lock_users
                "external" -> R.string.access_lock_external
                "chain" -> R.string.access_lock_chain
                "unsupported" -> R.string.access_lock_unsupported
                else -> R.string.access_unknown
            }))
            networkFailed -> Feedback(stringResource(R.string.access_rule_failed)) { refresh(lastAction.first) }
        }
        if (!pending && lock == "shared") {
            val peers = state?.optJSONArray("peers")
            if (peers != null && peers.length() > 0) {
                GroupFooter(List(peers.length(), peers::getString).joinToString(stringResource(R.string.list_separator)))
            }
        }
    }
}

@Composable
private fun Feedback(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = StatusWarning)
        ActionText(stringResource(R.string.update_retry), onClick = retry)
    }
}
