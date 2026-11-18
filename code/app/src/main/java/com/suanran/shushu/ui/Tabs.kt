// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suanran.shushu.R
import com.suanran.shushu.ui.icons.JiumiIcons
import com.suanran.shushu.ui.theme.Ink
import com.suanran.shushu.ui.theme.LocalAppIsDark

/** The three top-level places, in the order the bar shows them. */
enum class Tab(val label: Int, val icon: ImageVector) {
    MONITOR(R.string.tab_monitor, JiumiIcons.Shield),
    APPS(R.string.tab_apps, JiumiIcons.Apps),
    SETTINGS(R.string.tab_settings, JiumiIcons.Settings)
}

/**
 * How much of a tab page's foot the floating bar covers. A tab page adds it to the bottom of its
 * list's content padding, so the last row can scroll clear of the bar while the rest passes under it.
 */
val LocalTabBarInset = compositionLocalOf { 0.dp }

/**
 * The chosen tab's page under a floating bar of all three, as liquid glass: a clear capsule
 * that blurs whatever scrolls beneath, a raised pill under the chosen tab that slides between them.
 * The page runs to the bottom edge; [LocalTabBarInset] tells it how much to keep clear.
 */
@Composable
fun TabScaffold(selected: Tab, onSelect: (Tab) -> Unit, content: @Composable (Tab) -> Unit) {
    // The page is drawn once into this layer, which the bar then draws again, blurred, behind itself.
    val backdrop = rememberGraphicsLayer()
    val density = LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalTabBarInset provides BAR_HEIGHT + BAR_GAP * 2 + navBottom) {
            Box(
                Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.navigationBars)
                    .drawWithContent {
                        backdrop.record { this@drawWithContent.drawContent() }
                        drawLayer(backdrop)
                    }
            ) { content(selected) }
        }
        GlassTabBar(
            selected, onSelect, backdrop,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = BAR_GAP)
        )
    }
}

@Composable
private fun GlassTabBar(selected: Tab, onSelect: (Tab) -> Unit, backdrop: GraphicsLayer, modifier: Modifier) {
    val dark = LocalAppIsDark.current
    val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val density = LocalDensity.current
    // Liquid glass: clear rather than frosted, so what passes beneath stays recognisable, lightly
    // softened; near the rim it bends as through a thick lens.
    val blurPx = with(density) { 5.dp.toPx() }
    val bandPx = with(density) { 16.dp.toPx() }
    val bendPx = with(density) { 20.dp.toPx() }
    val marginPx = with(density) { 24.dp.toPx() }
    val frosted = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Before Android 12 there is no blur, so the pane is near opaque to keep its labels legible.
    val tint = when {
        !blurs -> MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        dark -> Color(0xFF1C1C1F).copy(alpha = 0.46f)
        else -> Color(0xFFF5F5F8).copy(alpha = 0.50f)
    }
    val vivid = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.6f) }) }
    // Light falls from above: a sheen over the upper part of the body, a glow along its foot where
    // the light, having passed through, gathers, as in a crystal.
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.12f else 0.55f),
        0.45f to Color.White.copy(alpha = 0f),
        0.80f to Color.White.copy(alpha = 0f),
        1f to Color.White.copy(alpha = if (dark) 0.07f else 0.30f)
    )
    // The rim catches the light: bright at the top left and bottom right, fading at the sides.
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.42f else 1f),
        0.35f to Color.White.copy(alpha = if (dark) 0.06f else 0.25f),
        0.65f to Color.White.copy(alpha = if (dark) 0.04f else 0.20f),
        1f to Color.White.copy(alpha = if (dark) 0.26f else 0.85f)
    )
    // One press source for every tab, so pressing anywhere on the bar sinks the whole pane a little.
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "barPress")
    val haptics = LocalHapticFeedback.current
    val update by com.suanran.shushu.update.UpdateNotice.available.collectAsState()
    BoxWithConstraints(
        modifier
            .graphicsLayer { scaleX = sink; scaleY = sink }
            .width(TAB_WIDTH * Tab.entries.size + BAR_PADDING * 2).height(BAR_HEIGHT)
            // Layers: a wide soft shadow the glass floats on and a tight one at its foot, so it stands
            // off a white page as clearly as off a photo.
            .shadow(if (dark) 14.dp else 22.dp, CircleShape, ambientColor = Color.Black.copy(alpha = if (dark) 0.6f else 0.10f),
                spotColor = Color.Black.copy(alpha = if (dark) 0.7f else 0.20f))
            .shadow(if (dark) 0.dp else 2.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.12f))
            // A hairline darker than any page outside the bright rim, so the glass keeps its edge on white.
            .border(0.5.dp, Color.Black.copy(alpha = if (dark) 0.55f else 0.08f), CircleShape)
            .padding(0.5.dp)
            .clip(CircleShape)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .drawBehind {
                if (blurs) {
                    // The backdrop is taken a margin wider than the bar, so the rim can bend in what
                    // lies just beyond it; the bar's clip trims the margin off again.
                    val m = marginPx
                    frosted.renderEffect = liquidGlassEffect(size.width, size.height, m, bandPx, bendPx, blurPx)
                        ?: BlurEffect(blurPx * 3, blurPx * 3, TileMode.Clamp)
                    frosted.colorFilter = vivid
                    frosted.topLeft = IntOffset(-m.toInt(), -m.toInt())
                    frosted.record(IntSize((size.width + 2 * m).toInt(), (size.height + 2 * m).toInt())) {
                        translate(m - origin.x, m - origin.y) { drawLayer(backdrop) }
                    }
                    drawLayer(frosted)
                }
                drawRect(tint)
                drawRect(sheen)
            }
            .border(1.dp, rim, CircleShape)
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
        // The chosen tab sits on a drop of the same glass, a shade denser, with its own bright rim.
        Box(
            Modifier.offset(x = left).width((right - left).coerceAtLeast(0.dp)).fillMaxHeight()
                .clip(CircleShape)
                .background(if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.055f))
                .border(0.75.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.22f else 0.9f),
                    Color.White.copy(alpha = if (dark) 0.04f else 0.3f))), CircleShape)
        )
        Row(Modifier.fillMaxSize()) {
            Tab.entries.forEach { tab ->
                val chosen = tab == selected
                val color by animateColorAsState(if (chosen) Ink.primary else Ink.secondary, label = "tabColor")
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                        .selectable(selected = chosen, role = Role.Tab, interactionSource = press, indication = null) {
                            if (!chosen) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(tab)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    // The icon of the tab just chosen gives a small springy hop.
                    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
                    LaunchedEffect(chosen) {
                        if (chosen) {
                            pop.animateTo(1.16f, androidx.compose.animation.core.tween(90))
                            pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
                        }
                    }
                    Box {
                        Icon(tab.icon, contentDescription = null, tint = color,
                            modifier = Modifier.size(18.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
                        // A newer release is marked on Settings, at the icon's lower right.
                        if (tab == Tab.SETTINGS && update != null) {
                            Box(Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 2.dp).size(8.dp)
                                .border(1.5.dp, if (dark) Color(0xFF2A2A2E) else Color.White, CircleShape).padding(1.5.dp)
                                .background(NEW_RED, CircleShape))
                        }
                    }
                    Text(stringResource(tab.label), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium, color = color,
                        modifier = Modifier.padding(top = 1.dp))
                }
            }
        }
    }
}

private val BAR_HEIGHT: Dp = 48.dp
private val BAR_GAP: Dp = 12.dp
private val BAR_PADDING: Dp = 4.dp
/** Each tab's share of the bar; the bar is only as wide as its tabs, not the screen. */
private val TAB_WIDTH: Dp = 84.dp
