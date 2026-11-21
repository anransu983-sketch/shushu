// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suanran.shushu.MonitorController
import com.suanran.shushu.R
import com.suanran.shushu.data.AppKey
import com.suanran.shushu.ui.icons.JiumiIcons
import com.suanran.shushu.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The permission manager: pick any app, open its permission list. Core system components only
 * appear in expert mode, through the same gate the app list already uses.
 */
@Composable
fun PermissionManagerScreen(controller: MonitorController, labels: AppLabels,
                            onOpenAccess: (AppKey) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val uiState by controller.state.collectAsStateWithLifecycle()
    val expert = uiState.expertMode
    var query by rememberSaveable { mutableStateOf("") }
    val apps by produceState<List<Pair<MonitorController.ListedApp, String>>?>(null) {
        value = withContext(Dispatchers.IO) {
            controller.listedApps(includeSystem = true)
                .map { it to labels.label(AppKey(it.uid, it.pkg)) }
                .sortedBy { it.second.lowercase() }
        }
    }
    val shown = apps?.filter {
        query.isBlank() || it.second.contains(query, ignoreCase = true) || it.first.pkg.contains(query, ignoreCase = true)
    }.orEmpty()
    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(JiumiIcons.ArrowBack, stringResource(R.string.action_back), tint = Ink.primary)
        }
        Text(stringResource(R.string.access_permissions), Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.headlineSmall, color = Ink.primary)
        Spacer(Modifier.height(16.dp))
        Card {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(JiumiIcons.Search, null, tint = Ink.secondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                BasicTextField(query, { query = it }, Modifier.weight(1f),
                    singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink.primary),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(stringResource(R.string.apps_search),
                            style = MaterialTheme.typography.bodyMedium, color = Ink.tertiary)
                        inner()
                    })
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }, Modifier.size(28.dp)) {
                    Icon(JiumiIcons.Close, stringResource(R.string.action_cancel), tint = Ink.secondary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (apps == null) {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.access_reading), style = MaterialTheme.typography.bodyMedium, color = Ink.secondary)
            }
        } else if (shown.isEmpty()) {
            GroupFooter(stringResource(R.string.apps_empty))
        } else {
            // User apps and system components in their own groups, each under its own title.
            val userApps = shown.filter { !it.first.preinstalled }
            val systemApps = shown.filter { it.first.preinstalled }
            LazyColumn(Modifier.fillMaxWidth()) {
                if (userApps.isNotEmpty()) {
                    item { GroupTitle(stringResource(R.string.perm_manager_user), Modifier.padding(top = 4.dp)) }
                    items(userApps, key = { it.first.pkg }) { (app, name) ->
                        AppRow(labels, app, name, onOpenAccess)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (systemApps.isNotEmpty()) {
                    item { GroupTitle(stringResource(R.string.perm_manager_system), Modifier.padding(top = 16.dp)) }
                    items(systemApps, key = { it.first.pkg }) { (app, name) ->
                        AppRow(labels, app, name, onOpenAccess)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        if (!expert) GroupFooter(stringResource(R.string.perm_manager_expert_hint))
    }
}

@Composable
private fun AppRow(labels: AppLabels, app: MonitorController.ListedApp, name: String,
                   onOpenAccess: (AppKey) -> Unit) {
    Card {
        ListRow(name,
            subtitle = app.pkg,
            leading = { AppIcon(labels, app.pkg, 36) },
            onClick = { onOpenAccess(AppKey(app.uid, app.pkg)) }
        ) { Chevron() }
    }
}
