// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suanran.shushu.ui.theme.GlassDark
import com.suanran.shushu.ui.theme.GlassLight
import com.suanran.shushu.ui.theme.Ink
import com.suanran.shushu.ui.theme.LocalAppIsDark
import com.suanran.shushu.R
import com.suanran.shushu.data.AppKey
import com.suanran.shushu.data.JiumiStore
import com.suanran.shushu.data.LogEntry
import com.suanran.shushu.data.ReportBuilder
import com.suanran.shushu.data.Segments
import com.suanran.shushu.data.TimelineItem
import com.suanran.shushu.ui.icons.JiumiIcons
import com.suanran.shushu.ui.theme.AppIconSize
import com.suanran.shushu.ui.theme.AppShapes
import com.suanran.shushu.ui.theme.StatusWarning
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records opened from a list, by the id their detail page's route carries. They live as long as
 * the process; a restored route whose record is gone returns to the list.
 */
object OpenedLogs {
    private val next = AtomicInteger()
    private val entries = HashMap<Int, LogEntry>()

    @Synchronized fun put(entry: LogEntry): Int = next.incrementAndGet().also { entries[it] = entry }
    @Synchronized fun get(id: Int): LogEntry? = entries[id]
}

fun headOf(item: TimelineItem): TimelineItem = (item as? TimelineItem.Segment)?.head ?: item

/** The destinations a record reached, newest first; a run of network activity can reach several. */
fun destinationsOf(item: TimelineItem): List<String> =
    ((item as? TimelineItem.Segment)?.members ?: listOf(item)).mapNotNull { (it as? TimelineItem.NetItem)?.label }.distinct()

/**
 * A run of network conversations told as one: bytes added up, in the background if any part was,
 * marked if any part sent the threshold in the background. Null for anything but network records.
 */
fun netView(item: TimelineItem): TimelineItem.NetItem? {
    val nets = ((item as? TimelineItem.Segment)?.members ?: listOf(item)).filterIsInstance<TimelineItem.NetItem>()
    if (nets.size <= 1) return nets.firstOrNull()
    fun sum(of: (JiumiStore.Connection) -> Long?) = nets.mapNotNull { of(it.connection) }.takeIf { it.isNotEmpty() }?.sum()
    val base = nets.firstOrNull { ReportBuilder.isUnattended(it.connection.state, it.connection.screen) } ?: nets.first()
    return base.copy(
        connection = base.connection.copy(count = nets.sumOf { it.connection.count }, sent = sum { it.sent },
            received = sum { it.received }, sentUnattended = sum { it.sentUnattended }),
        sentInBackground = nets.any { it.sentInBackground }
    )
}

fun categoryOf(item: TimelineItem): String? = when (val head = headOf(item)) {
    is TimelineItem.AccessItem -> head.category
    is TimelineItem.CallItem -> head.category
    else -> null
}

fun isUnattended(item: TimelineItem): Boolean = when (val head = headOf(item)) {
    is TimelineItem.AccessItem -> !head.access.denied &&
        ReportBuilder.isUnattended(head.access.state, head.access.screen)
    is TimelineItem.CallItem -> !head.refused && ReportBuilder.isUnattended(head.call.state, head.call.screen)
    is TimelineItem.NetItem -> ReportBuilder.isUnattended(head.connection.state, head.connection.screen)
    else -> false
}


/**
 * Marked when it happened while the app was not in use or sent data then. What Android refused gave
 * the app nothing and stays grey; the overview raises an app that keeps trying. [routine] system work
 * stays grey.
 */
@Composable
fun nodeColor(item: TimelineItem, routine: Boolean): Color {
    val head = netView(item) ?: headOf(item)
    val grey = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    if (routine) return grey
    val alarming = head is TimelineItem.NetItem && head.sentInBackground
    return if (alarming || isUnattended(head)) StatusWarning else grey
}

/** The symbol of a record's kind: its category's group, a network or a process start. */
fun recordIcon(item: TimelineItem, groupOf: Map<String, String>, groupIcons: Map<String, String>): ImageVector =
    when (val head = headOf(item)) {
        is TimelineItem.NetItem -> JiumiIcons.Network
        is TimelineItem.ProcessItem -> JiumiIcons.Play
        is TimelineItem.ActionItem -> JiumiIcons.Sliders
        else -> groupIcon(categoryOf(head)?.let(groupOf::get)?.let(groupIcons::get).orEmpty())
    }

/**
 * The line a list shows for a record: its action in words when every record it stands for did the
 * same thing ("List running apps"), otherwise the kind of data.
 */
@Composable
fun rowAction(item: TimelineItem): String {
    val members = (item as? TimelineItem.Segment)?.members ?: listOf(item)
    val actions = members.map { actionName(it) }.distinct()
    return actions.singleOrNull() ?: recordTitle(item)
}

@Composable
fun recordTitle(item: TimelineItem): String = destinationsOf(item).takeIf { it.size > 1 }?.let {
    stringResource(R.string.connected_to_many, destinationLabel(it.first()), formatCount(it.size.toLong()))
} ?: when (val head = headOf(item)) {
    is TimelineItem.AccessItem -> categoryName(head.category)
    is TimelineItem.CallItem -> categoryName(head.category)
    is TimelineItem.NetItem -> stringResource(R.string.connected_to, destinationLabel(head.label))
    is TimelineItem.ProcessItem -> stringResource(R.string.process_started)
    is TimelineItem.ActionItem -> actionName(head).orEmpty()
    is TimelineItem.Segment -> recordTitle(head.head)
}

/**
 * One record on the time axis: the time on the left, a node colored by whether it needs a look, then
 * who ([app], on cross-app lists) and what, with its circumstances. Consecutive rows of a day draw
 * one card; [first] and [last] round its ends. Tapping opens the record's own page.
 */
@Composable
fun LogRow(
    item: TimelineItem,
    routine: Boolean,
    icon: ImageVector,
    app: Pair<AppKey, AppLabels>?,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit
) {
    val time = DateFormat.getTimeFormat(LocalContext.current)
    val node = nodeColor(item, routine)
    val line = MaterialTheme.colorScheme.outlineVariant
    val corner = com.suanran.shushu.ui.theme.AppShapes.CardRadius
    val shape = RoundedCornerShape(
        topStart = if (first) corner else 0.dp, topEnd = if (first) corner else 0.dp,
        bottomStart = if (last) corner else 0.dp, bottomEnd = if (last) corner else 0.dp
    )
    val segment = item as? TimelineItem.Segment
    // Frosted like the cards, so the timeline reads as glass like the rest of the page.
    val dark = LocalAppIsDark.current
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (dark) GlassDark else GlassLight)
            .height(IntrinsicSize.Min).clickable(onClick = onClick)
            .padding(start = 16.dp, end = 10.dp, top = if (first) 4.dp else 0.dp, bottom = if (last) 4.dp else 0.dp)
    ) {
        // The time column: the latest time, and under it where a merged run began.
        Column(Modifier.width(56.dp).padding(top = 13.dp)) {
            Text(time.format(item.t), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            segment?.first?.takeIf { time.format(it) != time.format(item.t) }?.let {
                Text(time.format(it), style = MaterialTheme.typography.labelSmall,
                    color = Ink.tertiary, maxLines = 1)
            }
        }
        Box(Modifier.width(18.dp).fillMaxHeight().drawBehind {
            val x = size.width / 2
            val nodeY = 21.dp.toPx()
            if (!first) drawLine(line, Offset(x, 0f), Offset(x, nodeY), strokeWidth = 1.dp.toPx())
            if (!last) drawLine(line, Offset(x, nodeY), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            drawCircle(node, radius = 4.5.dp.toPx(), center = Offset(x, nodeY))
        })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (app != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app.second, app.first.pkg, 20)
                    Spacer(Modifier.width(8.dp))
                    Text(app.second.label(app.first), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(AppIconSize.Inline))
                Spacer(Modifier.width(6.dp))
                Text(rowAction(item),
                    style = if (app != null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (app != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val count = Segments.count(item)
                if (count > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text("×${formatCount(count.toLong())}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            RecordBadges(netView(item) ?: headOf(item), routine)
        }
        Icon(JiumiIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, top = 13.dp).size(AppIconSize.Inline))
    }
}

/**
 * Only what the user would want to know, in plain words: refused, read from the system's record,
 * sent while not in use, sent at all, with the screen locked, while not in use. Routine system work
 * has no app state, so it shows only what it sent.
 */
@Composable
fun RecordBadges(item: TimelineItem, routine: Boolean) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val badges = ArrayList<Pair<String, Color>>()
    when (item) {
        is TimelineItem.AccessItem -> {
            if (item.access.denied) badges += stringResource(R.string.access_denied) to grey
            // Read from the system's record, not seen by the monitor (it may not have been running).
            if (item.access.source == "system") badges += stringResource(R.string.badge_from_system) to grey
        }
        is TimelineItem.CallItem -> if (item.refused) badges += stringResource(R.string.access_denied) to grey
        // The bytes sent in the background, as measured, when they reach the threshold; else all sent.
        is TimelineItem.NetItem -> {
            val background = item.connection.sentUnattended?.takeIf { item.sentInBackground && !routine && it > 0 }
            if (background != null) badges += stringResource(R.string.sent_unattended_badge, formatBytes(background)) to StatusWarning
            else item.connection.sent?.takeIf { it > 0 }?.let { badges += stringResource(R.string.uploaded, formatBytes(it)) to grey }
        }
        else -> Unit
    }
    val state = when (item) {
        is TimelineItem.AccessItem -> item.access.state to item.access.screen
        is TimelineItem.CallItem -> item.call.state to item.call.screen
        is TimelineItem.NetItem -> item.connection.state to item.connection.screen
        else -> null
    }
    // Refused: where the caller was says nothing about it.
    val plain = item is TimelineItem.AccessItem && item.access.denied || item is TimelineItem.CallItem && item.refused
    if (state != null && !routine && !plain && state.first != ReportBuilder.PERSISTENT) {
        if (state.second == false) badges += stringResource(R.string.badge_locked) to StatusWarning
        else if (state.first in BACKGROUND_STATES) badges += stringResource(R.string.badge_not_in_use) to StatusWarning
    }
    if (item is TimelineItem.ProcessItem && item.start.reason.isNotEmpty()) badges += startReasonName(item.start.reason) to grey
    if (badges.isEmpty()) return
    Badges { badges.forEach { (text, color) -> Badge(text, color) } }
}

/** The date heading above a day's records. */
@Composable
fun DayTitle(day: Long, count: Int, modifier: Modifier = Modifier) {
    GroupTitle(dayLabel(day), modifier) {
        Text(pluralStringResource(R.plurals.records_count, count, formatCount(count.toLong())),
            style = MaterialTheme.typography.labelMedium, color = Ink.tertiary,
            modifier = Modifier.padding(end = 8.dp))
    }
}

val BACKGROUND_STATES = setOf("fgsvc", "bg", "cch", "gone")
const val PROVIDER_PREFIX = "ContentProvider@"

fun dayStart(t: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = t
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
fun dayLabel(day: Long): String {
    val context = LocalContext.current
    return if (DateUtils.isToday(day)) stringResource(R.string.range_today)
    else DateUtils.formatDateTime(context, day, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
}

/** Lists of records show this many at first and this many more on each request. */
const val LOG_PAGE = 150

/** Calls [onMore] whenever the list's end comes within a few rows while [more] records wait. */
@Composable
fun LoadMoreOnScroll(state: androidx.compose.foundation.lazy.LazyListState, more: Boolean, onMore: () -> Unit) {
    val latestMore by androidx.compose.runtime.rememberUpdatedState(more)
    val latestOnMore by androidx.compose.runtime.rememberUpdatedState(onMore)
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow {
            val info = state.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.collect { (last, total) -> if (latestMore && last >= total - LOAD_AHEAD) latestOnMore() }
    }
}

private const val LOAD_AHEAD = 10
