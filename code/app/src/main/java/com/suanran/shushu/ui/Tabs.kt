// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suanran.shushu.R
import com.suanran.shushu.ui.theme.GlassBorderDark
import com.suanran.shushu.ui.theme.GlassBorderLight
import com.suanran.shushu.ui.theme.GlassDark
import com.suanran.shushu.ui.theme.GlassLight
import com.suanran.shushu.ui.theme.Ink
import com.suanran.shushu.ui.theme.LocalAppIsDark

/** The three top-level places, in the order the bar shows them. */
enum class Tab(val label: Int, val hamster: HamsterKind) {
    MONITOR(R.string.tab_monitor, HamsterKind.MONITOR),
    APPS(R.string.tab_apps, HamsterKind.APPS),
    SETTINGS(R.string.tab_settings, HamsterKind.SETTINGS)
}

/**
 * How much of a tab page's foot the floating bar covers. A tab page adds it to the bottom of its
 * list's content padding, so the last row can scroll clear of the bar while the rest passes under it.
 */
val LocalTabBarInset = compositionLocalOf { 0.dp }

/**
 * The chosen tab's page under a floating glass capsule of all three, each with its hamster.
 * The page runs to the bottom edge; [LocalTabBarInset] tells it how much to keep clear.
 * The field behind comes from the theme root, so this scaffold paints nothing behind the page.
 */
@Composable
fun TabScaffold(selected: Tab, onSelect: (Tab) -> Unit, content: @Composable (Tab) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalTabBarInset provides BAR_HEIGHT + BAR_GAP * 2 + navBottom) {
            Box(
                Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.navigationBars)
            ) { content(selected) }
        }
        ShushuTabBar(
            selected, onSelect,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = BAR_GAP)
        )
    }
}

@Composable
private fun ShushuTabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier) {
    val dark = LocalAppIsDark.current
    // One press source for every tab, so pressing anywhere on the bar sinks the whole pane a little.
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "barPress")
    val haptics = LocalHapticFeedback.current
    val update by com.suanran.shushu.update.UpdateNotice.available.collectAsState()
    BoxWithConstraints(
        modifier
            .graphicsLayer { scaleX = sink; scaleY = sink }
            .width(TAB_WIDTH * Tab.entries.size + BAR_PADDING * 2).height(BAR_HEIGHT)
            .shadow(if (dark) 14.dp else 22.dp, CircleShape, ambientColor = Color.Black.copy(alpha = if (dark) 0.6f else 0.10f),
                spotColor = Color.Black.copy(alpha = if (dark) 0.7f else 0.20f))
            .background(if (dark) GlassDark else GlassLight, CircleShape)
            .border(1.dp, if (dark) GlassBorderDark else GlassBorderLight, CircleShape)
            .padding(BAR_PADDING)
            .selectableGroup()
    ) {
        val width = maxWidth / Tab.entries.size
        // The pill moves like a drop of liquid: the edge it travels towards leaves first and the other
        // follows on a softer spring, so it stretches in flight and settles back to its size.
        var from by remember { mutableIntStateOf(selected.ordinal) }
        val forward = selected.ordinal >= from
        LaunchedEffect(selected) { from = selected.ordinal }
        val lead = spring<Dp>(dampingRatio = 0.82f, stiffness = 700f)
        val trail = spring<Dp>(dampingRatio = 0.72f, stiffness = 230f)
        val left by animateDpAsState(width * selected.ordinal, if (forward) trail else lead, label = "pillLeft")
        val right by animateDpAsState(width * (selected.ordinal + 1), if (forward) lead else trail, label = "pillRight")
        // The chosen tab sits on a drop of warm orange glass.
        Box(
            Modifier.offset(x = left).width((right - left).coerceAtLeast(0.dp)).fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.22f else 0.16f))
                .border(0.75.dp, Color.White.copy(alpha = if (dark) 0.22f else 0.9f), CircleShape)
        )
        Row(Modifier.fillMaxSize()) {
            Tab.entries.forEachIndexed { index, tab ->
                val chosen = tab == selected
                val color by animateColorAsState(
                    if (chosen) MaterialTheme.colorScheme.primary else Ink.secondary, label = "tabColor"
                )
                val hamsterSize by animateDpAsState(if (chosen) 30.dp else 24.dp, spring(dampingRatio = 0.7f, stiffness = 500f), label = "hamsterSize")
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                        .selectable(selected = chosen, role = Role.Tab, interactionSource = press, indication = null) {
                            if (!chosen) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(tab)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    // The hamster of the tab just chosen gives a small springy hop.
                    val pop = remember { Animatable(1f) }
                    LaunchedEffect(chosen) {
                        if (chosen) {
                            pop.animateTo(1.16f, tween(90))
                            pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
                        }
                    }
                    Box {
                        HamsterIcon(
                            tab.hamster, hamsterSize, phase = index * 0.3f,
                            modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                        )
                        // A newer release is marked on Settings, at the hamster's lower right.
                        if (tab == Tab.SETTINGS && update != null) {
                            Box(Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 2.dp).size(8.dp)
                                .border(1.5.dp, if (dark) Color(0xFF2A2A2E) else Color.White, CircleShape).padding(1.5.dp)
                                .background(NEW_RED, CircleShape))
                        }
                    }
                    Text(stringResource(tab.label), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium, color = color,
                        modifier = Modifier.padding(top = 1.dp))
                    // A dot under the chosen tab's name.
                    Box(
                        Modifier.padding(top = 2.dp).size(4.dp)
                            .background(if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                    )
                }
            }
        }
    }
}

private val BAR_HEIGHT: Dp = 56.dp
private val BAR_GAP: Dp = 12.dp
private val BAR_PADDING: Dp = 4.dp
/** Each tab's share of the bar; the bar is only as wide as its tabs, not the screen. */
private val TAB_WIDTH: Dp = 84.dp
