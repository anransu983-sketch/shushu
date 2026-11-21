// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.suanran.shushu.ui.theme.GlassBorderDark
import com.suanran.shushu.ui.theme.GlassBorderLight
import com.suanran.shushu.ui.theme.GlassDark
import com.suanran.shushu.ui.theme.GlassLight
import com.suanran.shushu.ui.theme.Ink
import com.suanran.shushu.ui.theme.LocalAppIsDark
import com.suanran.shushu.MonitorController
import com.suanran.shushu.MonitorController.AppState
import com.suanran.shushu.MonitorController.ListedApp
import com.suanran.shushu.R
import com.suanran.shushu.data.AppKey
import com.suanran.shushu.data.AppKind
import com.suanran.shushu.data.AppSource
import com.suanran.shushu.data.ManageAction
import com.suanran.shushu.ui.icons.JiumiIcons
import com.suanran.shushu.ui.theme.AppShapes

private enum class AppsFilter(val label: Int, val state: AppState?) {
    ALL(R.string.apps_filter_all, null),
    DISABLED(R.string.apps_filter_disabled, AppState.DISABLED),
    DELETED(R.string.state_deleted, AppState.DELETED)
}

/** The four kinds of app the list is grouped into, in its order; [source] is null for system components. */
internal enum class AppGroup(val label: Int, val source: AppSource?, val tint: Color) {
    STORE(R.string.apps_stat_store, AppSource.STORE, Color(0xFF4F8CF0)),
    SIDELOADED(R.string.apps_stat_sideloaded, AppSource.SIDELOADED, Color(0xFFE5A23B)),
    PREINSTALLED(R.string.apps_stat_preinstalled, AppSource.PREINSTALLED, Color(0xFF3FB28C)),
    COMPONENT(R.string.apps_stat_component, null, Color(0xFF9A9AA4));

    companion object {
        fun of(app: ListedApp) = if (app.component) COMPONENT else entries.first { it.source == app.source }
    }
}

/**
 * Every app on the phone, system components included, grouped by where it came from. Above the
 * list, in a bar that slides away while scrolling down: the filter, a strip counting each group
 * (tapped, it glides the list there; the group in view is shaded) and search.
 */
@Composable
fun AppsScreen(controller: MonitorController, labels: AppLabels, onOpenApp: (AppKey) -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(AppsFilter.ALL.name) }
    var query by rememberSaveable { mutableStateOf("") }
    // Read again after every report, which follows each change made here. The phone's own list comes
    // first and at once (the last one shown, before that); the monitor's, which fills in apps some
    // phones hide, follows only when it is due, since it costs a round of shell commands.
    val apps by produceState(lastListed, state.ledger, state.running) {
        suspend fun named(list: List<ListedApp>) = withContext(Dispatchers.IO) {
            list.map { it to (it.backup?.label ?: labels.label(AppKey(it.uid, it.pkg))) }
                .sortedWith(compareBy({ AppGroup.of(it.first).ordinal }, { it.second.lowercase() }))
        }
        value = named(controller.listedApps(includeSystem = true)).also { lastListed = it }
        if (controller.shellListStale()) {
            value = named(controller.listedApps(fresh = true, includeSystem = true)).also { lastListed = it }
        }
    }
    val chosen = AppsFilter.entries.firstOrNull { it.name == filter } ?: AppsFilter.ALL
    val groups = remember(apps, chosen, query) {
        val q = query.trim()
        apps.orEmpty()
            .filter { (app, name) ->
                (chosen.state == null || app.state == chosen.state) &&
                    (q.isEmpty() || name.contains(q, ignoreCase = true) || app.pkg.contains(q, ignoreCase = true))
            }
            .groupBy { AppGroup.of(it.first) }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Where each group's title sits in the list: after a loading or empty notice if there is one,
    // then for every group before it a title, its rows and a gap.
    val header = if (apps == null || groups.isEmpty()) 1 else 0
    val titleIndex = remember(groups, header) {
        var i = header
        groups.mapValues { (_, list) -> i.also { i += list.size + 2 } }
    }
    // The group being read: the last one whose title is at or above the top of the list.
    val current by remember(titleIndex) {
        derivedStateOf { titleIndex.entries.lastOrNull { it.value <= listState.firstVisibleItemIndex }?.key }
    }
    val scrolled by remember { derivedStateOf { listState.canScrollBackward } }

    HidingTopBar(stringResource(R.string.tab_apps), scrolled, extra = {
        // Under the title, and hidden and shown with it: which apps, how many of each kind, and search.
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = PageTopGap, bottom = 10.dp)) {
            Segmented(
                AppsFilter.entries.map { stringResource(it.label) },
                selected = chosen.ordinal,
                onSelect = { filter = AppsFilter.entries[it].name },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            GroupStats(counts = AppGroup.entries.associateWith { groups[it]?.size }, current = current, onJump = { group ->
                titleIndex[group]?.let { scope.launch { listState.animateScrollToItem(it) } }
            })
            Spacer(Modifier.height(10.dp))
            SearchField(query, onChange = { query = it })
        }
    }) { top ->
    // What the fast-scroll bubble says for each item of the list: the group, and the initial of the app.
    val groupNames = AppGroup.entries.associateWith { stringResource(it.label) }
    val itemLabels = remember(groups, header, groupNames) {
        buildList {
            repeat(header) { add(null) }
            groups.forEach { (group, list) ->
                val name = groupNames.getValue(group)
                add(name)
                list.forEach { (_, appName) -> add("$name · ${appName.firstOrNull()?.uppercaseChar() ?: ""}") }
                add(name)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + PageTopGap, bottom = 32.dp + LocalTabBarInset.current)
    ) {
        if (apps == null) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (groups.isEmpty()) {
            item(key = "empty") {
                EmptyCard(stringResource(when {
                    query.isNotBlank() -> R.string.apps_empty_search
                    chosen == AppsFilter.DISABLED -> R.string.apps_empty_disabled
                    chosen == AppsFilter.DELETED -> R.string.apps_empty_deleted
                    else -> R.string.apps_empty
                }))
            }
        }
        // One card per group, each a few hundred rows at most; rows are items so long lists stay light.
        groups.forEach { (group, list) ->
            item(key = "title-$group") {
                GroupTitle(
                    stringResource(group.label) + " · " + formatCount(list.size.toLong()),
                    Modifier.padding(top = 4.dp),
                    hamster = when (group) {
                        AppGroup.STORE -> HamsterKind.APPS
                        AppGroup.SIDELOADED -> HamsterKind.UNINSTALL
                        AppGroup.PREINSTALLED -> HamsterKind.SETTINGS
                        AppGroup.COMPONENT -> HamsterKind.TIMELINE
                    }
                )
            }
            list.forEachIndexed { index, (app, name) ->
                item(key = "app-${app.pkg}") {
                    CardSlice(first = index == 0, last = index == list.lastIndex) {
                        if (index > 0) InsetDivider()
                        val badge = when (app.state) {
                            AppState.DISABLED -> JiumiIcons.Snowflake
                            AppState.DELETED -> JiumiIcons.Trash
                            else -> null
                        }
                        ListRow(
                            title = name,
                            subtitle = listOfNotNull(
                                stateText(app),
                                app.backup?.let { stringResource(R.string.backup_size, formatBytes(it.bytes)) },
                                app.pkg.takeIf { it != name }
                            ).joinToString(" · ").ifEmpty { null },
                            leading = {
                                if (badge != null) {
                                    AppIconWithBadge(controller, labels, app.pkg, LEADING_SIZE, badge, fromBackup = app.backup != null)
                                } else AppIcon(labels, app.pkg, LEADING_SIZE)
                            },
                            onClick = { onOpenApp(AppKey(app.uid, app.pkg)) },
                            titleColor = if (badge != null) Ink.secondary else MaterialTheme.colorScheme.onSurface
                        ) { Chevron() }
                    }
                }
            }
            item(key = "gap-$group") { Spacer(Modifier.height(16.dp)) }
        }
    }
    FastScroller(listState, modifier = Modifier.align(Alignment.TopEnd)
        .padding(top = top + PageTopGap, bottom = 16.dp + LocalTabBarInset.current)) { i -> itemLabels.getOrNull(i) }
    }
    }
}

/**
 * The count of each group side by side in one card, a coloured mark under each figure telling the
 * groups apart. The group in view is shaded; an empty one is dimmed and does nothing when tapped.
 */
@Composable
internal fun GroupStats(counts: Map<AppGroup, Int?>, current: AppGroup?, onJump: (AppGroup) -> Unit) {
    val dark = LocalAppIsDark.current
    Surface(shape = AppShapes.Card, color = if (dark) GlassDark else GlassLight,
        border = BorderStroke(1.dp, if (dark) GlassBorderDark else GlassBorderLight),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp).height(IntrinsicSize.Min)) {
            AppGroup.entries.forEachIndexed { i, group ->
                val count = counts[group]
                val enabled = (count ?: 0) > 0
                if (i > 0) Box(Modifier.padding(vertical = 14.dp).width(0.5.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant))
                val here by animateColorAsState(if (group == current && enabled) Ink.fill else Color.Transparent, label = "groupHere")
                Column(
                    Modifier.weight(1f).clip(AppShapes.Control).background(here).clickable(enabled = enabled) { onJump(group) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(count?.let { formatCount(it.toLong()) } ?: "0",
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = if (enabled) Ink.primary else Ink.tertiary)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(group.tint.copy(alpha = if (enabled) 1f else 0.35f)))
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(group.label), style = MaterialTheme.typography.labelSmall,
                            color = if (enabled) Ink.secondary else Ink.tertiary, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** What a group of apps is called by where its apps came from. */
@Composable
fun sourceName(source: AppSource): String = stringResource(when (source) {
    AppSource.STORE -> R.string.source_store
    AppSource.SIDELOADED -> R.string.source_sideloaded
    AppSource.PREINSTALLED -> R.string.source_preinstalled
})

/** The list last shown, with names, so coming back to the tab shows it at once while it is read again. */
private var lastListed: List<Pair<ListedApp, String>>? = null

@Composable
private fun stateText(app: ListedApp): String? = when (app.state) {
    AppState.ACTIVE -> null
    AppState.DISABLED -> stringResource(R.string.state_disabled)
    AppState.SUSPENDED -> stringResource(R.string.state_suspended)
    AppState.DELETED -> stringResource(R.string.state_deleted)
}

/**
 * A rounded search field with its symbol: the card colour inside a hairline, so it stands off the page
 * in both themes (a bare grey wash all but vanishes on a black page).
 */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val dark = com.suanran.shushu.ui.theme.LocalAppIsDark.current
    Surface(
        shape = AppShapes.Control,
        color = if (dark) GlassDark else GlassLight,
        border = androidx.compose.foundation.BorderStroke(
            if (dark) 0.75.dp else 0.5.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.22f else 0.12f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(JiumiIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(stringResource(R.string.apps_search), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = value, onValueChange = onChange, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (value.isNotEmpty()) {
                // A 32dp touch target around the 18dp glyph.
                Box(Modifier.size(32.dp).clickable { onChange("") }, contentAlignment = Alignment.Center) {
                    Icon(JiumiIcons.Close, stringResource(R.string.action_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** One row of a card built from list items, so a long card scrolls lazily; [first] and [last] round its ends. */
@Composable
private fun CardSlice(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    val r = AppShapes.CardRadius
    val dark = LocalAppIsDark.current
    Surface(
        shape = RoundedCornerShape(topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
            bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp),
        color = if (dark) GlassDark else GlassLight, modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(top = if (first) 4.dp else 0.dp, bottom = if (last) 4.dp else 0.dp)) { content() }
    }
}

/** What a button that carries out [action] says. */
fun actionLabel(action: ManageAction): Int = when (action) {
    ManageAction.DISABLE -> R.string.manage_action_disable
    ManageAction.ENABLE -> R.string.manage_action_enable
    ManageAction.UNSUSPEND -> R.string.manage_action_unsuspend
    ManageAction.UNINSTALL -> R.string.manage_action_uninstall
    ManageAction.REMOVE -> R.string.manage_action_remove
    ManageAction.RESTORE, ManageAction.REINSTALL -> R.string.manage_action_restore
}
