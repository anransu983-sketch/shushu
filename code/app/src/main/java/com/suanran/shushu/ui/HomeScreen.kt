// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import com.suanran.shushu.data.AppKind
import com.suanran.shushu.data.Attention
import com.suanran.shushu.data.AttentionRules
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suanran.shushu.MonitorController
import com.suanran.shushu.MonitorController.Busy
import com.suanran.shushu.MonitorController.Problem
import com.suanran.shushu.MonitorController.Range
import com.suanran.shushu.access.RootShell
import com.suanran.shushu.R
import com.suanran.shushu.adb.AirModePairingManager.PairingState
import com.suanran.shushu.adb.AirModePairingManager.PairingTrustState
import com.suanran.shushu.adb.messageRes
import com.suanran.shushu.data.AppKey
import com.suanran.shushu.data.AppSummary
import com.suanran.shushu.data.Peak
import com.suanran.shushu.data.GroupSummary
import com.suanran.shushu.data.LogEntry
import com.suanran.shushu.data.TimelineItem
import androidx.compose.foundation.lazy.rememberLazyListState
import com.suanran.shushu.ui.icons.JiumiIcons
import com.suanran.shushu.ui.theme.AppIconSize
import com.suanran.shushu.ui.theme.AppShapes
import com.suanran.shushu.ui.theme.StatusWarning
import com.suanran.shushu.ui.theme.StatusSuccess
import com.suanran.shushu.ui.theme.Ink

@Composable
fun HomeScreen(
    controller: MonitorController,
    labels: AppLabels,
    onOpenGroup: (String) -> Unit,
    onOpenAttention: (String) -> Unit,
    onOpenLog: (LogEntry) -> Unit,
    onOpenPairing: () -> Unit
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val pairingFlow = state.method == com.suanran.shushu.access.AccessMethod.WIRELESS &&
        state.pairingState in setOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE, PairingState.PAIRING)
    // Ready to start by the chosen way: paired, root granted, or Shizuku allowed.
    val paired = state.ready
    val ledger = state.ledger
    var view by rememberSaveable { mutableStateOf(HomeView.TIMELINE.name) }
    var shown by rememberSaveable { mutableStateOf(LOG_PAGE) }
    val list = rememberLazyListState()
    // Routine system work is listed like everything else; it is only never marked as needing a look.
    val routineKeys = remember(ledger) {
        ledger?.apps.orEmpty().filter { it.kind.id in ledger?.routineKinds.orEmpty() }.map { it.key }.toSet()
    }
    var filter by rememberSaveable { mutableStateOf(TimelineFilter.ALL.name) }
    val entries = remember(ledger, filter) {
        val f = (TimelineFilter.entries.firstOrNull { it.name == filter } ?: TimelineFilter.ALL)
        ledger?.timeline.orEmpty().filter { f.matches(it.item) }
    }
    val toggled = rememberToggledDays(state.range, filter)
    val layout = remember(entries, shown, toggled.value) { timelineRows(entries, shown, toggled.value) { it.item.t } }
    LoadMoreOnScroll(list, more = view == HomeView.TIMELINE.name && layout.more) { shown += LOG_PAGE }

    val scrolled by remember { androidx.compose.runtime.derivedStateOf { list.canScrollBackward } }
    HidingTopBar(stringResource(R.string.tab_monitor), scrolled) { top ->
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = list,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + PageTopGap, bottom = 32.dp + LocalTabBarInset.current)
    ) {
        // The monitoring service: running, interrupted, or ready to start once Wireless debugging is paired.
        val session = state.session
        item {
            when {
                state.sessionOpen && state.running && !pairingFlow && state.problem == null -> SessionStrip(state, controller)
                state.sessionOpen && !state.running && paired && !pairingFlow && session != null ->
                    InterruptedCard(state, session, controller)
                else -> ServiceCard(state, controller, pairingFlow, paired, onOpenPairing)
            }
        }

        item {
            Segmented(
                Range.entries.map { rangeLabel(it, session) },
                selected = state.range.ordinal,
                onSelect = { controller.setRange(Range.entries[it]); shown = LOG_PAGE },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
            )
        }

        if (ledger == null || ledger.apps.isEmpty()) {
            // Nothing recorded yet: the status above already says what to do, so only a running
            // monitor adds a word here.
            if (state.running) {
                item { Spacer(Modifier.height(24.dp)) }
                item { EmptyCard(stringResource(R.string.record_empty_running)) }
            }
            return@LazyColumn
        }

        item { GroupTitle(stringResource(R.string.attention_title), Modifier.padding(top = 28.dp)) }
        item { AttentionCard(ledger.attention, onOpenAttention) }

        item {
            ViewTabs(
                HomeView.entries.map { stringResource(it.label) },
                selected = HomeView.valueOf(view).ordinal,
                onSelect = { view = HomeView.entries[it].name },
                modifier = Modifier.padding(top = 32.dp, bottom = 8.dp)
            )
        }

        when (HomeView.valueOf(view)) {
            HomeView.TIMELINE -> {
                item {
                    FilterChips(TimelineFilter.entries.map { stringResource(it.label) }, (TimelineFilter.entries.firstOrNull { it.name == filter } ?: TimelineFilter.ALL).ordinal,
                        Modifier.padding(top = 4.dp)) { filter = TimelineFilter.entries[it].name; shown = LOG_PAGE }
                }
                if (entries.isEmpty()) item { Column(Modifier.padding(top = 12.dp)) { EmptyCard(stringResource(R.string.record_empty_running)) } }
                timelineItems(layout.rows, "log", { it.item }, toggled::toggle) { entry, first, last ->
                    LogRow(
                        entry.item, routine = entry.key in routineKeys,
                        icon = recordIcon(entry.item, ledger.groupOf, ledger.groupIcons),
                        app = entry.key to labels, first = first, last = last,
                        onClick = { onOpenLog(entry) }
                    )
                }
            }
            HomeView.TYPES -> {
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    // Kinds of data and the network, the one used last first.
                    val top = ledger.groups.maxOfOrNull { it.count } ?: 1
                    val network = ledger.networkApps
                    val networkLast = ledger.timeline.firstOrNull { headOf(it.item) is TimelineItem.NetItem }?.item?.t
                        ?: network.maxOfOrNull { it.last } ?: 0L
                    val rows: List<Pair<Long, @Composable () -> Unit>> = ledger.groups.map { group ->
                        group.last to @Composable { GroupRow(group, top, onClick = { onOpenGroup(group.id) }) }
                    } + if (network.isEmpty()) emptyList() else listOf(networkLast to @Composable {
                        val bytes = network.sumOf { it.traffic?.total ?: 0L }
                        val flagged = network.sumOf { it.sentInBackground }
                        ListRow(
                            title = stringResource(R.string.section_network),
                            subtitle = listOfNotNull(
                                pluralStringResource(R.plurals.apps_count, network.size, formatCount(network.size.toLong())),
                                if (bytes > 0) formatBytes(bytes) else null
                            ).joinToString(" · "),
                            leading = { TimeColumn(networkLast); IconTile(JiumiIcons.Network) },
                            extra = if (flagged > 0) {
                                { Badges { Badge(stringResource(R.string.badge_sent_in_background, formatCount(flagged.toLong())), StatusWarning) } }
                            } else null,
                            onClick = { onOpenGroup(NETWORK_GROUP) }
                        ) { Chevron() }
                    })
                    Card {
                        rows.sortedByDescending { it.first }.forEachIndexed { index, (_, row) ->
                            if (index > 0) InsetDivider(start = TIME_COLUMN_WIDTH + 60.dp)
                            row()
                        }
                    }
                }
            }
        }
    }
    if (view == HomeView.TIMELINE.name) {
        TimelineScrubber(list, layout.rows, Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = top + 8.dp, bottom = 16.dp + LocalTabBarInset.current))
    }
    }
    }
}

private enum class HomeView(val label: Int) {
    TIMELINE(R.string.view_timeline), TYPES(R.string.view_by_type)
}

/**
 * How the timeline narrows down: by circumstance, never by kind. What was done (reads, writes, calls,
 * Bluetooth, the network) is sorted in By type; here it is everything or what happened in the
 * background. What Android refused gave the app nothing, so it is marked on its row, not filtered for.
 */
private enum class TimelineFilter(val label: Int) {
    ALL(R.string.filter_all), BACKGROUND(R.string.filter_background);

    fun matches(item: TimelineItem): Boolean = when (this) {
        ALL -> true
        BACKGROUND -> (netView(item) ?: headOf(item)).let { isUnattended(it) || (it as? TimelineItem.NetItem)?.sentInBackground == true }
    }
}

/** A row of small choices, one picked: filled when picked, quiet otherwise. */
@Composable
private fun FilterChips(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Surface(onClick = { onSelect(i) }, shape = RoundedCornerShape(50),
                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
    }
}

@Composable
fun rangeLabel(range: Range, session: MonitorController.SessionView?): String = when (range) {
    // A session picked from the monitoring record is named by its start.
    Range.SESSION -> if (session == null || session.latest) stringResource(R.string.range_session) else shortDate(session.session.start)
    Range.TODAY -> stringResource(R.string.range_today)
    Range.WEEK -> stringResource(R.string.range_week)
    Range.MONTH -> stringResource(R.string.range_month)
}

/**
 * Where monitoring stands, as one card: a dot, the state or the running clock, and at the right the one
 * thing to do. Beneath, inside the card, what goes with the state ([detail]: the last hour's activity
 * while monitoring, the last session or what was covered otherwise), or [message], an error.
 */
@Composable
private fun MonitorCapsule(
    tone: Color,
    live: Boolean,
    title: String,
    clock: String? = null,
    corner: (@Composable () -> Unit)? = null,
    message: String? = null,
    detail: (@Composable ColumnScope.() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(Modifier.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = if (detail != null || message != null) 14.dp else 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(tone, live)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (clock == null) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (clock == null) Ink.primary else Ink.secondary, maxLines = 1)
                if (clock != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(clock, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold, color = Ink.primary, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                corner?.invoke()
            }
            Column(Modifier.padding(end = 6.dp)) {
                if (message != null) {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = StatusWarning, modifier = Modifier.padding(top = 6.dp))
                } else if (detail != null) {
                    Spacer(Modifier.height(8.dp))
                    detail()
                }
            }
        }
    }
}

/** A line of quiet figures, label then value: "Apps 66 · Records 340". */
@Composable
private fun RowScope.Figures(figures: List<Pair<String, String>>) {
    val primary = Ink.primary
    val secondary = Ink.secondary
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            figures.forEachIndexed { i, (value, label) ->
                if (i > 0) withStyle(androidx.compose.ui.text.SpanStyle(color = secondary.copy(alpha = 0.5f))) { append("  ·  ") }
                withStyle(androidx.compose.ui.text.SpanStyle(color = secondary)) { append(label); append(' ') }
                withStyle(androidx.compose.ui.text.SpanStyle(color = primary, fontWeight = FontWeight.Medium)) { append(value) }
            }
        },
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
    )
}

/**
 * How busy the last hour was: records in each five minutes as a bar, the latest one in the state's
 * colour, so a burst of activity shows at a glance.
 */
@Composable
private fun ActivityBars(times: List<Long>, now: Long, tone: Color) {
    val buckets = remember(times, now / BUCKET_MS) {
        IntArray(BUCKETS).also { counts ->
            val start = now - BUCKETS * BUCKET_MS
            times.forEach { t -> if (t in start..now) counts[((t - start) / BUCKET_MS).toInt().coerceIn(0, BUCKETS - 1)]++ }
        }
    }
    val top = (buckets.maxOrNull() ?: 0).coerceAtLeast(1)
    val rest = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        buckets.forEachIndexed { i, count ->
            val fraction by androidx.compose.animation.core.animateFloatAsState(
                (count.toFloat() / top).coerceAtLeast(0.06f), label = "bar")
            Box(Modifier.weight(1f).fillMaxHeight(fraction)
                .background(if (i == buckets.lastIndex) tone else rest, RoundedCornerShape(2.dp)))
        }
    }
}

private const val BUCKETS = 12
private const val BUCKET_MS = 5 * 60_000L

/** The one step to take, as a small graphite capsule at the panel's right. */
@Composable
private fun CornerButton(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled && !busy, shape = CircleShape, color = Ink.primary,
        contentColor = MaterialTheme.colorScheme.surface, modifier = Modifier.height(32.dp)) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp), color = LocalContentColor.current)
            else Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
}

/** The state's dot; while monitoring it breathes, a soft halo swelling and fading behind it. */
@Composable
private fun StatusDot(tone: Color, live: Boolean) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val t by pulse.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(1800, easing = androidx.compose.animation.core.FastOutSlowInEasing)), label = "pulseT")
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        if (live) Box(Modifier.size(18.dp).graphicsLayer { scaleX = 0.4f + 0.6f * t; scaleY = 0.4f + 0.6f * t; alpha = 1f - t }
            .background(tone.copy(alpha = 0.45f), CircleShape))
        Box(Modifier.size(9.dp).background(tone, CircleShape))
    }
}

/** Stopping, as a media control: a round grey button holding a small square, at the panel's corner. */
@Composable
private fun StopButton(enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = Ink.fill, modifier = Modifier.size(32.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).background(Ink.primary.copy(alpha = if (enabled) 1f else 0.4f), RoundedCornerShape(2.5.dp)))
        }
    }
}

/** A running time as a clock, hours:minutes:seconds; past a day, in days and hours. */
@Composable
private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    if (total >= 86_400) return formatDuration(ms)
    return "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
}

/** Monitoring in progress: the running clock and stop, the last hour's activity, and what was seen so far. */
@Composable
private fun SessionStrip(state: MonitorController.UiState, controller: MonitorController) {
    val session = state.session ?: return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val ledger = state.ledger
    val times = remember(ledger) { ledger?.timeline.orEmpty().map { it.item.t } }
    val stopLabel = stringResource(R.string.action_stop_monitoring)
    val figures = listOfNotNull(
        formatCount((ledger?.apps?.size ?: 0).toLong()) to stringResource(R.string.stat_apps),
        formatCount((ledger?.timeline?.size ?: 0).toLong()) to stringResource(R.string.stat_records),
        state.footprint?.let { "${(it.pssKb / 1024).toInt()} MB" to stringResource(R.string.stat_memory) }
    )
    MonitorCapsule(
        tone = StatusSuccess, live = true,
        title = stringResource(R.string.monitor_live), clock = clock(now - session.session.start),
        corner = {
            Box(Modifier.semantics { contentDescription = stopLabel }) {
                StopButton(enabled = state.busy == null, onClick = controller::stopMonitor)
            }
        },
        detail = {
            ActivityBars(times, now, StatusSuccess)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.monitor_last_hour), style = MaterialTheme.typography.labelMedium, color = Ink.tertiary)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.monitor_now), style = MaterialTheme.typography.labelMedium, color = Ink.tertiary)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) { Figures(figures) }
        }
    )
}

/** The session is open but the monitor stopped (a reboot, Wireless debugging off): resume it, or end it. */
@Composable
private fun InterruptedCard(state: MonitorController.UiState, session: MonitorController.SessionView, controller: MonitorController) {
    MonitorCapsule(
        tone = StatusWarning, live = false,
        title = stringResource(R.string.monitoring_interrupted),
        corner = {
            CornerButton(stringResource(R.string.action_resume_short),
                busy = state.busy == Busy.STARTING || state.pairingState == PairingState.CONNECTING, onClick = controller::startMonitor)
        },
        detail = { Row(verticalAlignment = Alignment.CenterVertically) {
            Figures(listOf(
                formatDuration(session.coverage.coveredMs) to stringResource(R.string.stat_covered),
                formatCount((state.ledger?.apps?.size ?: 0).toLong()) to stringResource(R.string.stat_apps)
            ))
            Text(stringResource(R.string.action_end_short), style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium, color = Ink.secondary,
                modifier = Modifier.clip(CircleShape).clickable(enabled = state.busy == null, onClick = controller::stopMonitor)
                    .padding(horizontal = 8.dp, vertical = 4.dp))
        } },
        message = statusMessage(state)
    )
}

/**
 * Not monitoring: start, once Wireless debugging is paired; until then the card says so and leads to
 * pairing. Beneath, when there was one, the last session.
 */
@Composable
private fun ServiceCard(
    state: MonitorController.UiState,
    controller: MonitorController,
    pairingFlow: Boolean,
    paired: Boolean,
    onOpenPairing: () -> Unit
) {
    val needsPairing = !paired && !pairingFlow
    val last = state.session?.takeIf { !it.running }
    val lastText = last?.let { stringResource(R.string.monitor_last, shortDateTime(it.session.start), formatDuration(it.coverage.coveredMs)) }
    MonitorCapsule(
        tone = if (needsPairing) StatusWarning else MaterialTheme.colorScheme.onSurfaceVariant,
        live = false,
        title = when {
            pairingFlow -> stringResource(R.string.pairing_in_progress)
            !paired && state.method == com.suanran.shushu.access.AccessMethod.ROOT -> stringResource(rootStatus(state.root))
            !paired && state.method == com.suanran.shushu.access.AccessMethod.SHIZUKU -> stringResource(R.string.service_shizuku_needed)
            !paired -> stringResource(if (state.trust == PairingTrustState.PAIRING_REQUIRED) R.string.service_repair else R.string.service_unpaired)
            state.busy == Busy.STARTING && state.pairingState == PairingState.CONNECTING -> stringResource(R.string.pairing_connecting)
            state.busy == Busy.STARTING -> stringResource(R.string.service_starting)
            else -> stringResource(R.string.hero_idle)
        },
        corner = when {
            !paired && state.method == com.suanran.shushu.access.AccessMethod.ROOT -> { {
                CornerButton(stringResource(rootAction(state.root)), busy = state.root == RootShell.State.CHECKING,
                    onClick = controller::authorizeRoot)
            } }
            !paired && state.method == com.suanran.shushu.access.AccessMethod.SHIZUKU -> { {
                CornerButton(stringResource(shizukuNextAction(state.shizuku))) { runShizukuNextAction(controller, state.shizuku) }
            } }
            !paired -> { { CornerButton(stringResource(R.string.action_pair), enabled = !pairingFlow, onClick = onOpenPairing) } }
            !pairingFlow -> { {
                CornerButton(stringResource(R.string.action_start_short),
                    busy = state.busy == Busy.STARTING || state.pairingState == PairingState.CONNECTING, onClick = controller::startMonitor)
            } }
            else -> null
        },
        detail = lastText?.takeIf { paired }?.let { text -> {
            Text(text, style = MaterialTheme.typography.bodySmall, color = Ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } },
        message = statusMessage(state)
    )
}

@Composable
private fun AttentionCard(items: List<Attention>, onOpen: (String) -> Unit) {
    val raised = items.filter { it.apps.isNotEmpty() }
    Card {
        if (raised.isEmpty()) {
            Text(stringResource(R.string.attention_none), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            return@Card
        }
        raised.forEachIndexed { index, item ->
            if (index > 0) InsetDivider()
            ListRow(
                title = attentionName(item.id),
                subtitle = pluralStringResource(R.plurals.apps_count, item.apps.size, formatCount(item.apps.size.toLong())),
                leading = { IconTile(attentionIcon(item.id)) },
                onClick = { onOpen(item.id) }
            ) { Chevron() }
        }
    }
}

fun attentionIcon(id: String): ImageVector = when (id) {
    AttentionRules.SENT_IN_BACKGROUND -> JiumiIcons.Network
    AttentionRules.BACKGROUND -> JiumiIcons.Layers
    AttentionRules.FREQUENT -> JiumiIcons.Activity
    AttentionRules.FREQUENT_DENIED -> JiumiIcons.Ban
    else -> JiumiIcons.Info
}

/** Route id of the network list, next to the category groups from the rules. */
const val NETWORK_GROUP = "network"

/** A first-level kind with its share of the busiest one as a bar. */
@Composable
private fun GroupRow(group: GroupSummary, top: Int, onClick: () -> Unit) {
    ListRow(
        title = groupName(group.id),
        subtitle = pluralStringResource(R.plurals.apps_count, group.apps, formatCount(group.apps.toLong())),
        leading = { TimeColumn(group.last); IconTile(groupIcon(group.icon)) },
        extra = {
            ShareBar(group.count.toFloat() / top.coerceAtLeast(1), Modifier.padding(top = 8.dp))
            if (group.background > 0) Badges {
                Badge(stringResource(R.string.badge_background, formatCount(group.background.toLong())), StatusWarning)
            }
        },
        onClick = onClick
    ) {
        TrailingValue(formatCount(group.count.toLong()))
        Chevron()
    }
}

/** Symbols named by the rules file. */
fun groupIcon(name: String): ImageVector = when (name) {
    "map_pin" -> JiumiIcons.MapPin
    "smartphone" -> JiumiIcons.Smartphone
    "person" -> JiumiIcons.Person
    "message" -> JiumiIcons.MessageCheck
    "calendar" -> JiumiIcons.Calendar
    "camera" -> JiumiIcons.Camera
    "image" -> JiumiIcons.Image
    "clipboard" -> JiumiIcons.Clipboard
    "activity" -> JiumiIcons.Activity
    "layers" -> JiumiIcons.Layers
    "notifications" -> JiumiIcons.Notifications
    "network" -> JiumiIcons.Network
    "eye" -> JiumiIcons.Eye
    else -> JiumiIcons.Info
}

/**
 * One app in a list: what kinds of information it touched and when, and badges for what needs a
 * look. [value] replaces the time on the right, for lists measured by something else.
 */
@Composable
fun AppRow(app: AppSummary, labels: AppLabels, value: String? = null, routine: Boolean = false, onClick: () -> Unit) {
    // What it did, every kind of data it reached, most used first; the time leads the row.
    val kinds = app.groups.map { groupName(it) }
    val summary = buildList {
        if (kinds.isNotEmpty()) add(kinds.joinToString(stringResource(R.string.list_separator)))
        if (kinds.isEmpty() && app.traffic != null) add(stringResource(R.string.traffic_total, formatBytes(app.traffic.total)))
    }
    ListRow(
        title = labels.label(app.key),
        subtitle = summary.joinToString(" · "),
        leading = { TimeColumn(app.last); AppIcon(labels, app.key.pkg, LEADING_SIZE) },
        onClick = onClick,
        extra = { AppBadges(app, routine) }
    ) {
        if (value != null) TrailingValue(value)
        Chevron()
    }
}

/**
 * Fixed order, in plain words: missing from the privacy record, sent while not in use, read while
 * not in use, many reads within 60 minutes, many refusals, then what it sent. Routine system work shows only what it sent.
 */
@Composable
fun AppBadges(app: AppSummary, routine: Boolean) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val badges = buildList {
        if (!routine) {
            if (app.sentInBackground > 0) add(stringResource(R.string.badge_sent_in_background, formatCount(app.sentInBackground.toLong())) to StatusWarning)
            if (app.background > 0) add(stringResource(R.string.badge_background, formatCount(app.background.toLong())) to StatusWarning)
            app.frequent?.let { add(stringResource(R.string.badge_frequent, categoryName(it.category), formatCount(it.count.toLong())) to StatusWarning) }
            app.frequentDenied?.let { add(stringResource(R.string.badge_frequent_denied, categoryName(it.category), formatCount(it.count.toLong())) to StatusWarning) }
        }
        // The system's own count: in the background it needs a look; otherwise only for an app the monitor did not see.
        if (!routine && app.systemBackground > 0) add(stringResource(R.string.badge_system_background, formatCount(app.systemBackground)) to StatusWarning)
        else if (app.systemTotal > 0 && app.accesses + app.calls == 0) add(stringResource(R.string.badge_system_total, formatCount(app.systemTotal)) to grey)
        if (app.uploaded > 0) add(stringResource(R.string.uploaded, formatBytes(app.uploaded)) to grey)
    }
    if (badges.isEmpty()) return
    Badges { badges.forEach { (text, color) -> Badge(text, color) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Badges(content: @Composable () -> Unit) {
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
    }
}

@Composable
fun kindName(kind: AppKind): String = stringResource(
    when (kind) {
        AppKind.THIRD_PARTY -> R.string.kind_third_party
        AppKind.PREINSTALLED -> R.string.kind_preinstalled
        AppKind.SYSTEM_APP -> R.string.kind_system_app
        AppKind.SYSTEM -> R.string.kind_system
    }
)

@Composable
fun attentionName(id: String): String = stringResource(
    when (id) {
        AttentionRules.BACKGROUND -> R.string.attention_background
        AttentionRules.SENT_IN_BACKGROUND -> R.string.attention_sent_in_background
        AttentionRules.FREQUENT -> R.string.attention_frequent
        AttentionRules.FREQUENT_DENIED -> R.string.attention_frequent_denied
        else -> R.string.cap_unknown
    }
)

/**
 * Why monitoring is not running, when something went wrong: the connection's own reason when it
 * could not be made (Wireless debugging off, no Wi-Fi), else what failed.
 */
@Composable
fun statusMessage(state: MonitorController.UiState): String? {
    if (state.method == com.suanran.shushu.access.AccessMethod.ROOT) {
        rootError(state.root)?.let { return stringResource(it) }
    }
    // Wireless debugging's own reasons say nothing about root or Shizuku.
    val failure = state.pairingFailure?.takeIf { state.method == com.suanran.shushu.access.AccessMethod.WIRELESS }
        ?.let { stringResource(it.messageRes()) }
    return when (val p = state.problem) {
        null -> failure
        Problem.CONNECT -> failure ?: problemText(p)
        else -> problemText(p)
    }
}

@Composable
private fun problemText(problem: Problem): String = stringResource(
    when (problem) {
        Problem.CONNECT -> R.string.error_connect
        Problem.START -> R.string.error_service_start
        Problem.SHIZUKU -> R.string.error_shizuku
    }
)

@Composable
fun EmptyCard(text: String) {
    Card {
        Column(Modifier.fillMaxWidth().widthIn(min = 0.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp))
        }
    }
}

/** How an attention item is judged, in plain words. */
@Composable
fun attentionRule(id: String): String? = when (id) {
    AttentionRules.BACKGROUND -> R.string.attention_rule_background
    AttentionRules.SENT_IN_BACKGROUND -> R.string.attention_rule_sent_in_background
    AttentionRules.FREQUENT -> R.string.attention_rule_frequent
    AttentionRules.FREQUENT_DENIED -> R.string.attention_rule_frequent_denied
    else -> null
}?.let { stringResource(it) }

