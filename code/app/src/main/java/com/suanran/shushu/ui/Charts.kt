// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.suanran.shushu.ui.theme.Ink
import com.suanran.shushu.R
import com.suanran.shushu.data.Behavior
import com.suanran.shushu.data.BehaviorSummary
import com.suanran.shushu.data.Buckets
import com.suanran.shushu.ui.theme.AppIconSize
import com.suanran.shushu.ui.theme.StatusWarning
import java.text.SimpleDateFormat

/**
 * One kind of data an app reached: what, how often, then when it last did so, and what the user
 * would not know (refused, while not in use). [routine] work is told plainly.
 */
@Composable
fun SummaryRow(summary: BehaviorSummary, icon: ImageVector, routine: Boolean, selected: Boolean, onClick: () -> Unit) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val indent = AppIconSize.Leading + 10.dp
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f) else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = grey, modifier = Modifier.size(AppIconSize.Leading))
            Spacer(Modifier.width(10.dp))
            Text(categoryName(summary.category), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(pluralStringResource(R.plurals.times_count, summary.count, formatCount(summary.count.toLong())),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
        }
        categoryDescription(summary.category)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = grey, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = indent, top = 4.dp))
        }
        // When first, then what stands out about it.
        val parts = buildList {
            add(stringResource(R.string.recent_at, shortTime(summary.last)) to false)
            if (!routine) {
                if (summary.denied > 0) add(stringResource(R.string.summary_denied, formatCount(summary.denied.toLong())) to true)
                if (summary.unattended > 0) add(stringResource(R.string.summary_unattended, formatCount(summary.unattended.toLong())) to true)
            }
        }
        Text(
            buildAnnotatedString {
                parts.forEachIndexed { i, (text, warn) ->
                    if (i > 0) withStyle(SpanStyle(color = grey)) { append(" · ") }
                    withStyle(SpanStyle(color = if (warn) StatusWarning else grey)) { append(text) }
                }
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = indent, top = 6.dp)
        )
    }
}

/**
 * Occurrences over the range, one bar per hour of the day or per day, with the time under the bars:
 * hours 0, 6, 12, 18 for a day, the weekday for a week, every seventh date for a month. The part
 * while the app was not in use is amber unless [routine].
 */
@Composable
fun BehaviorChart(b: Buckets, routine: Boolean, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val stub = Ink.fill
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val flaggedColor = if (routine) base else StatusWarning
    val max = (b.total.maxOrNull() ?: 0).coerceAtLeast(1)
    val labels = axisLabels(b)
    Column(modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        // The scale: the top grid line is the busiest bar.
        Text(formatCount(max.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.End).padding(bottom = 4.dp))
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val slot = size.width / b.size
            val bar = minOf(slot * 0.62f, 14.dp.toPx())
            val r = CornerRadius(2.dp.toPx())
            listOf(0f, size.height / 2, size.height - 1f).forEach { y ->
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            for (i in 0 until b.size) {
                val x = i * slot + (slot - bar) / 2
                if (b.total[i] == 0) {
                    drawRoundRect(stub, Offset(x, size.height - 2.dp.toPx()), Size(bar, 2.dp.toPx()), r)
                    continue
                }
                val h = (size.height * b.total[i] / max).coerceAtLeast(3.dp.toPx())
                val flagged = h * b.flagged[i] / b.total[i]
                drawRoundRect(base, Offset(x, size.height - h), Size(bar, h), r)
                if (flagged > 0) drawRoundRect(flaggedColor, Offset(x, size.height - flagged), Size(bar, flagged), r)
            }
        }
        // Each label starts under its bar's slot, as a clock's hour marks do.
        BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp).padding(top = 6.dp)) {
            val slot = maxWidth / b.size
            labels.forEachIndexed { i, label ->
                label ?: return@forEachIndexed
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.offset(x = slot * i).wrapContentWidth(Alignment.Start, unbounded = true))
            }
        }
    }
}

/** The text under each bar, or null for none. */
@Composable
private fun axisLabels(b: Buckets): List<String?> {
    val context = LocalContext.current
    // The app's own language, which can differ from the system's.
    val locale = LocalConfiguration.current.locales[0]
    val weekday = SimpleDateFormat(if (locale.language == "zh") "EEEEE" else "EEE", locale)
    return (0 until b.size).map { i ->
        if (b.stepMs == Behavior.HOUR) {
            val hour = java.util.Calendar.getInstance().apply { timeInMillis = b.start + i * Behavior.HOUR }.get(java.util.Calendar.HOUR_OF_DAY)
            if (hour % 6 == 0) hour.toString() else null
        } else {
            val day = java.util.Calendar.getInstance().apply { timeInMillis = b.start; add(java.util.Calendar.DAY_OF_YEAR, i) }.timeInMillis
            when {
                b.size <= 7 -> weekday.format(day)
                (b.size - 1 - i) % 7 == 0 -> DateUtils.formatDateTime(context, day,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_NO_YEAR)
                else -> null
            }
        }
    }
}

/** A row's share of the largest row as a thin bar. */
@Composable
fun ShareBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ink.fill)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)))
    }
}
