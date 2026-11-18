// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suanran.shushu.ui.theme.LocalAppIsDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A handle at a long list's right edge for moving through it fast, as iOS offers: it appears while
 * the list scrolls and fades a moment after; held and dragged, it moves the list freely, grows a
 * little, and a bubble beside it names where the finger is ([label] of the item there).
 *
 * It covers [count] items starting at [first], so a list can keep a header above the part it moves
 * through. Lists shorter than [minItems] are scrolled by hand.
 */
@Composable
fun FastScroller(
    state: LazyListState,
    first: Int = 0,
    count: Int = state.layoutInfo.totalItemsCount,
    minItems: Int = 40,
    modifier: Modifier = Modifier,
    label: @Composable (Int) -> String?
) {
    if (count < minItems) return
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val dark = LocalAppIsDark.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true else { delay(HIDE_DELAY_MS); visible = false }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "scrollerAlpha")
    val grow by animateDpAsState(if (dragging) 4.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = 600f), label = "scrollerGrow")
    // The gesture outlives changes of the list (more records load while dragging), so it reads them fresh.
    val currentFirst by rememberUpdatedState(first)
    val currentCount by rememberUpdatedState(count)
    val position = if (dragging) dragFraction
    else ((state.firstVisibleItemIndex - first).toFloat() / (count - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
    val under = (position * (count - 1)).toInt().coerceIn(0, count - 1)
    val bubble = if (dragging) label(under) else null

    BoxWithConstraints(modifier.fillMaxHeight().width(if (dragging) 240.dp else HIT_WIDTH).alpha(alpha)) {
        val density = LocalDensity.current
        val track = maxHeight - THUMB_HEIGHT
        val thumbY = track * position
        var lastIndex by remember { mutableStateOf(-1) }
        fun jump(y: Float, height: Float) {
            val thumb = with(density) { THUMB_HEIGHT.toPx() }
            dragFraction = ((y - thumb / 2) / (height - thumb).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val target = currentFirst + (dragFraction * (currentCount - 1)).toInt()
            if (target != lastIndex) {
                lastIndex = target
                scope.launch { state.scrollToItem(target) }
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).fillMaxHeight().width(HIT_WIDTH)
                .then(if (visible || dragging) Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            jump(it.y, size.height.toFloat())
                        },
                        onDragEnd = { dragging = false; lastIndex = -1 },
                        onDragCancel = { dragging = false; lastIndex = -1 }
                    ) { change, _ -> change.consume(); jump(change.position.y, size.height.toFloat()) }
                } else Modifier)
        ) {
            // The handle: a small raised capsule of the card colour with two grip lines.
            // With gesture navigation the right edge means "back"; over the handle it means the handle.
            Box(
                Modifier.align(Alignment.TopEnd).offset(y = thumbY).size(width = HIT_WIDTH, height = THUMB_HEIGHT)
                    .then(if (visible || dragging) Modifier.systemGestureExclusion() else Modifier)
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = 4.dp).offset(y = thumbY)
                    .size(width = THUMB_WIDTH + grow, height = THUMB_HEIGHT)
                    .shadow(if (dragging) 8.dp else 4.dp, RoundedCornerShape(50), ambientColor = Color.Black.copy(alpha = 0.12f),
                        spotColor = Color.Black.copy(alpha = if (dark) 0.5f else 0.22f))
                    .background(if (dark) Color(0xFF3A3A3F) else Color.White, RoundedCornerShape(50))
                    .border(0.5.dp, Color.Black.copy(alpha = if (dark) 0.4f else 0.08f), RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) {
                        Box(Modifier.size(width = 8.dp, height = 1.5.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (dragging) 0.55f else 0.35f), RoundedCornerShape(1.dp)))
                    }
                }
            }
        }
        if (bubble != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = HIT_WIDTH + 8.dp)
                    .offset(y = with(density) { (thumbY.toPx() + THUMB_HEIGHT.toPx() / 2 - 18.dp.toPx()).toDp() })
            ) {
                Text(bubble, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.surface, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

private const val HIDE_DELAY_MS = 1_500L
private val THUMB_HEIGHT = 44.dp
private val THUMB_WIDTH = 18.dp
/** Wide enough to catch with a thumb, though the handle drawn is narrower. */
private val HIT_WIDTH = 36.dp
