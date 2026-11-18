// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0
//
// Pixel-bead hamsters (点阵拼豆仓鼠): one 16x16 base grid, a 6x6 accessory sprite held
// at the body's side, and a fur colour per hamster. The grids are generated from
// ~/workspace/shushu/preview/hamster.py - edit there, re-run gen-hamster-kt.py, do not
// hand-edit the grids below. Face: dot eyes, a 3-shaped mouth, pink blush.

package com.suanran.shushu.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** The ten hamsters of the UI scheme: fur, accessory sprite, accent, extra (hat / glasses). */
enum class HamsterKind(val fur: Long, val accessory: String?, val accent: Long, val extra: String?) {
    MONITOR(0xFFB07A45, "magnifier", 0xFF6B4A2A, null),
    TIMELINE(0xFFC69A6B, "clock", 0xFF8A6B45, null),
    ATTENTION(0xFFB07A45, "flag", 0xFFB07A45, "hat"),
    APPS(0xFF8A7B6B, "blocks", 0xFF8A7B6B, null),
    PERMISSION(0xFF7A5230, "key", 0xFFE8B93C, null),
    NETWORK(0xFF8A6B8A, "antenna", 0xFF8A6B8A, null),
    DISABLED(0xFFA89E93, "snowflake", 0xFF7FB3D5, null),
    UNINSTALL(0xFFB07A45, "box", 0xFFC89B6A, null),
    EXPORT(0xFFC8A06B, "floppy", 0xFF5B8DEF, null),
    SETTINGS(0xFF8A7B6B, "gear", 0xFF8A8A96, "glasses"),
    ;
}

/** 16 wide, 16 tall. F fur, C cream belly / inner ear, D dark eye-nose-mouth, P pink blush. */
private val BASE_GRID = listOf(
        "..FF........FF..",
        ".FFFF......FFFF.",
        ".FCFF......FFCF.",
        "..FFFFFFFFFFFF..",
        ".FFDDFFFFFFDDFF.",
        ".FFDDFFFFFFDDFF.",
        ".FFPPFFFFFFPPFF.",
        ".FFFFFFDDFFFFFF.",
        ".FFFFFFFFFFFFFF.",
        "..FFFFFDDFFFFF..",
        "..FFFFFFDFFFFF..",
        "..FFFFFDDFFFFF..",
        "...FFFFFFFFFF...",
        "..FFCCCCCCCCFF..",
        "..FCCCCCCCCCCF..",
        "...FFCCCCCCFF..."
    )

/** 6x6 sprites, drawn at the body's right side. A accent, W white, G glass blue, R/B/Y blocks. */
private val ACCESSORY_GRIDS = mapOf(
    "magnifier" to listOf(
        ".AAAA.",
        "A....A",
        "A....A",
        ".AAAA.",
        "....A.",
        ".....A"
    ),
    "clock" to listOf(
        ".AAAA.",
        "AGGGGA",
        "AGDGGA",
        "AGGGGA",
        ".AAAA.",
        ".A..A."
    ),
    "flag" to listOf(
        "A.....",
        "AAYY..",
        "AAYYYY",
        "AAYY..",
        "A.....",
        "A....."
    ),
    "blocks" to listOf(
        "RR....",
        "RRBB..",
        "..BBGG",
        "..GG..",
        "......",
        "......"
    ),
    "key" to listOf(
        ".AAA..",
        "A...A.",
        "A...A.",
        ".AAA..",
        "..A...",
        "..AAA."
    ),
    "antenna" to listOf(
        "...AA.",
        "..A..A",
        "..A...",
        "..A...",
        "..A...",
        "..A..."
    ),
    "snowflake" to listOf(
        "..A...",
        "..A...",
        "AAAAAA",
        "..A...",
        "..A...",
        ".A..A."
    ),
    "box" to listOf(
        ".AAAA.",
        "AAAAAA",
        "AWWWWA",
        "AAAAAA",
        ".AAAA.",
        "..A.A."
    ),
    "floppy" to listOf(
        "AAAAAA",
        "AWWAAA",
        "AWWAAA",
        "AAAAAA",
        "AWWWWA",
        "AAAAAA"
    ),
    "gear" to listOf(
        "..AA..",
        ".AAAA.",
        "AAWWAA",
        "AAWWAA",
        ".AAAA.",
        "..AA.."
    )
    )

private val FIXED_COLORS = mapOf(
    'C' to Color(0xFFFFF3E0),
    'D' to Color(0xFF3A2A1E),
    'P' to Color(0xFFF5A3B7),
    'W' to Color.White,
    'G' to Color(0xFFBFE3F0),
    'R' to Color(0xFFE5484D),
    'B' to Color(0xFF4F8CF0),
    'Y' to Color(0xFFF5C542),
)

private fun cellsFor(kind: HamsterKind): Array<CharArray> {
    val grid = Array(16) { y -> BASE_GRID[y].toCharArray() }
    when (kind.extra) {
        "hat" -> {
            for (x in 6..9) grid[1][x] = 'Y'
            grid[0][7] = 'Y'; grid[0][8] = 'Y'
        }
        "glasses" -> {
            for ((x, y) in listOf(2 to 5, 2 to 6, 5 to 5, 5 to 6, 10 to 5, 10 to 6, 13 to 5, 13 to 6,
                3 to 4, 4 to 4, 11 to 4, 12 to 4)) grid[y][x] = 'D'
        }
    }
    return grid
}

/**
 * A pixel-bead hamster. Breathes (a slow 3s float, [phase] staggers hamsters 0.3s apart) and
 * bounces when tapped ([onClick] null means no tap handling). The accessory is the only thing
 * that differs besides fur colour, so every hamster reads as the same character.
 */
@Composable
fun HamsterIcon(
    kind: HamsterKind,
    size: Dp,
    phase: Float = 0f,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "hamsterBreath")
    val breath by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000), RepeatMode.Restart), label = "breath"
    )
    val floatY = (sin((breath + phase) * 2f * PI.toFloat()) * 1.5f).dp
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val pop by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = 0.45f, stiffness = 500f), label = "hamsterPop"
    )
    val cells = remember(kind) { cellsFor(kind) }
    val accessory = remember(kind) { kind.accessory?.let { ACCESSORY_GRIDS[it] } }
    val fur = Color(kind.fur)
    val accent = Color(kind.accent)
    // One cell is an 18th of the icon's width; computed here, because inside Canvas
    // `size` would mean the DrawScope's size, not this icon's size.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cell = with(density) { size.toPx() } / 18f
    Canvas(
        modifier
            .size(size)
            .graphicsLayer { translationY = floatY.toPx(); scaleX = pop; scaleY = pop }
            .then(if (onClick != null) Modifier.clickable(
                interactionSource = press, indication = null, onClick = onClick
            ) else Modifier)
    ) {
        fun draw(grid: List<String>, offX: Int, offY: Int) {
            grid.forEachIndexed { y, row ->
                row.forEachIndexed { x, ch ->
                    val color = when (ch) {
                        'F' -> fur
                        'A' -> accent
                        else -> FIXED_COLORS[ch]
                    } ?: return@forEachIndexed
                    drawRect(color, Offset((x + offX) * cell, (y + offY) * cell), androidx.compose.ui.geometry.Size(cell, cell))
                }
            }
        }
        // Base hamster, one cell in from the left so the accessory fits on the right.
        cells.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                val color = when (ch) {
                    'F' -> fur
                    'A' -> accent
                    else -> FIXED_COLORS[ch]
                } ?: return@forEachIndexed
                drawRect(color, Offset((x + 1) * cell, y * cell), androidx.compose.ui.geometry.Size(cell, cell))
            }
        }
        if (accessory != null) draw(accessory, 12, 9)
    }
}
