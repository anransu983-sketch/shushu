// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suanran.shushu.R
import com.suanran.shushu.data.Segments
import com.suanran.shushu.data.TimelineItem
import com.suanran.shushu.ui.icons.JiumiIcons
import com.suanran.shushu.ui.theme.AppIconSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A line of a timeline: a day's heading (open or folded into a summary) or one of its records. */
sealed interface TimelineRow<T> {
    val day: Long

    class Day<T>(override val day: Long, val entries: List<T>, val open: Boolean) : TimelineRow<T>
    class Record<T>(override val day: Long, val entry: T, val first: Boolean, val last: Boolean) : TimelineRow<T>
}

/**
 * Days the user opened or folded against the default: when records span more than [OPEN_DAYS]
 * days, only the newest day is open, so a week or a month reads as one line per day.
 */
@Composable
fun rememberToggledDays(vararg keys: Any?): MutableState<List<Long>> =
    rememberSaveable(*keys) { mutableStateOf(emptyList()) }

/** The rows of a timeline; [more] when records of open days wait beyond those listed. */
class TimelineLayout<T>(val rows: List<TimelineRow<T>>, val more: Boolean)

/**
 * [entries] (newest first) laid out by day. Records of open days are listed up to [shown] of them;
 * folded days cost nothing against it.
 */
fun <T> timelineRows(entries: List<T>, shown: Int, toggled: List<Long>, timeOf: (T) -> Long): TimelineLayout<T> {
    val days = entries.groupBy { dayStart(timeOf(it)) }
    val newest = days.keys.firstOrNull()
    val rows = ArrayList<TimelineRow<T>>()
    var budget = shown
    var more = false
    for ((day, list) in days) {
        val open = (days.size <= OPEN_DAYS || day == newest) != (day in toggled)
        if (open && budget <= 0) { more = true; break }
        rows += TimelineRow.Day(day, list, open)
        if (!open) continue
        val taken = list.take(budget)
        budget -= taken.size
        if (taken.size < list.size) more = true
        taken.forEachIndexed { i, entry -> rows += TimelineRow.Record(day, entry, i == 0, i == taken.lastIndex) }
    }
    return TimelineLayout(rows, more)
}

/**
 * What a record stays as across refreshes. A segment grows at its newest end, so it is known by its
 * oldest member.
 */
internal fun identity(item: TimelineItem): String = when (item) {
    is TimelineItem.AccessItem -> "a-${item.access.t}-${item.access.uid}-${item.access.op}-${item.access.attr}"
    is TimelineItem.CallItem -> "c-${item.call.t}-${item.call.uid}-${item.call.iface}-${item.call.method}"
    is TimelineItem.ProcessItem -> "p-${item.start.t}-${item.start.pid}"
    is TimelineItem.ActionItem -> "u-${item.action.t}-${item.action.uid}-${item.action.action}"
    is TimelineItem.NetItem -> "n-${item.connection.t}-${item.connection.uid}-${item.connection.proto}-${item.connection.addr}-${item.connection.port}"
    is TimelineItem.Segment -> "s-" + identity(item.members.last())
}

/** Opens a folded day or folds an open one. */
fun MutableState<List<Long>>.toggle(day: Long) {
    value = if (day in value) value - day else value + day
}

/**
 * [rows] as list items: an open day's heading stays pinned while its records scroll under it; a
 * folded day is one card with its count and what happened most. Tapping a heading opens or folds it.
 * [itemOf] gives the record behind an entry; [row] draws one record.
 */
@OptIn(ExperimentalFoundationApi::class)
fun <T> LazyListScope.timelineItems(
    rows: List<TimelineRow<T>>,
    prefix: String,
    itemOf: (T) -> TimelineItem,
    onToggle: (Long) -> Unit,
    row: @Composable (entry: T, first: Boolean, last: Boolean) -> Unit
) {
    // Keys follow the record, not its place: new records arrive at the top every few seconds, and a
    // key by position would hand one row's state (its icon, its expansion) to another app's record.
    val seen = HashMap<String, Int>()
    rows.forEach { r ->
        when (r) {
            is TimelineRow.Day -> if (r.open) {
                stickyHeader(key = "$prefix-day-${r.day}") {
                    // Frosted, so the day stays readable while records slide under it.
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)).clickable { onToggle(r.day) }
                        .padding(top = 20.dp)) {
                        DayTitle(r.day, r.entries.size)
                    }
                }
            } else {
                item(key = "$prefix-day-${r.day}") {
                    FoldedDay(r.day, r.entries.map(itemOf), Modifier.padding(top = 12.dp)) { onToggle(r.day) }
                }
            }
            is TimelineRow.Record -> {
                val base = "$prefix-${identity(itemOf(r.entry))}"
                val n = seen.merge(base, 1, Int::plus)!!
                item(key = if (n == 1) base else "$base#$n") { row(r.entry, r.first, r.last) }
            }
        }
    }
}

/** A day in one card: its date and count, then the three things done most, connections as one. */
@Composable
private fun FoldedDay(day: Long, items: List<TimelineItem>, modifier: Modifier, onOpen: () -> Unit) {
    val nets = items.filter { headOf(it) is TimelineItem.NetItem }
    val actions = items.filter { headOf(it) !is TimelineItem.NetItem }
        .groupBy { rowAction(it) }.mapValues { (_, list) -> list.sumOf { Segments.count(it) } }
        .entries.sortedByDescending { it.value }.map { it.key }
    val addresses = nets.flatMap { destinationsOf(it) }.distinct().size
    val summary = (actions.take(if (addresses > 0) 2 else 3) +
        listOfNotNull(addresses.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.day_addresses, it, formatCount(it.toLong())) }))
        .joinToString(" · ")
    Column(modifier) { Card {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dayLabel(day), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(pluralStringResource(R.plurals.records_count, items.size, formatCount(items.size.toLong())),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (summary.isNotEmpty()) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Icon(JiumiIcons.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).size(AppIconSize.Inline))
        }
    } }
}

/**
 * The fast-scroll handle for a timeline: [FastScroller] over its [rows], naming the day under the
 * finger. The timeline must be the list's last section, so its rows are the last items of [state].
 */
@Composable
fun <T> TimelineScrubber(state: LazyListState, rows: List<TimelineRow<T>>, modifier: Modifier = Modifier) {
    if (rows.size < SCRUB_MIN_ROWS) return
    val base = (state.layoutInfo.totalItemsCount - rows.size).coerceAtLeast(0)
    val days = rows.map { it.day }
    FastScroller(state, first = base, count = rows.size, modifier = modifier) { i -> dayLabel(days[i.coerceIn(0, days.lastIndex)]) }
}

/** Up to this many days every day is open. */
private const val OPEN_DAYS = 2
/** Below this many rows a list is short enough to scroll by hand. */
private const val SCRUB_MIN_ROWS = 60

