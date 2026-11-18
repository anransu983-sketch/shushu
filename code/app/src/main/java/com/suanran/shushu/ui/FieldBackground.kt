// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0
//
// The field (薯田) every page sits on: morning light over lavender hills by day,
// night purple with warm lamplight after dark. Pages scroll over it; it never moves.
// Glass panels float on it, so the field is drawn once at the root (MainActivity) and
// every page above it keeps a transparent background.

package com.suanran.shushu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.suanran.shushu.ui.theme.LocalAppIsDark

@Composable
fun FieldBackground(modifier: Modifier = Modifier) {
    val dark = LocalAppIsDark.current
    val sky = if (dark) {
        Brush.verticalGradient(
            0f to Color(0xFF241A33),
            0.55f to Color(0xFF2E2140),
            1f to Color(0xFF3A2A1E),
        )
    } else {
        Brush.verticalGradient(
            0f to Color(0xFFFFF6EC),
            0.55f to Color(0xFFF7EEF6),
            1f to Color(0xFFEFE9F7),
        )
    }
    Box(modifier.fillMaxSize().background(sky)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Low sun / lamp glow, upper right.
            drawCircle(
                color = if (dark) Color(0xFFE8833A).copy(alpha = 0.20f) else Color(0xFFFFD9A0).copy(alpha = 0.55f),
                radius = w * 0.42f,
                center = Offset(w * 0.86f, h * 0.06f),
            )
            // Far hill, lavender.
            val far = Path().apply {
                moveTo(0f, h * 0.86f)
                quadraticBezierTo(w * 0.28f, h * 0.76f, w * 0.62f, h * 0.85f)
                quadraticBezierTo(w * 0.82f, h * 0.90f, w, h * 0.84f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(far, if (dark) Color(0xFF33264A) else Color(0xFFE4D8F3))
            // Near hill, warm earth.
            val near = Path().apply {
                moveTo(0f, h * 0.93f)
                quadraticBezierTo(w * 0.30f, h * 0.86f, w * 0.58f, h * 0.93f)
                quadraticBezierTo(w * 0.80f, h * 0.98f, w, h * 0.92f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(near, if (dark) Color(0xFF4A3423) else Color(0xFFF3D9B8))
            // A few drifting seeds / fireflies, fixed places so they never jump.
            val dots = listOf(
                0.12f to 0.30f, 0.24f to 0.18f, 0.44f to 0.26f, 0.68f to 0.16f,
                0.78f to 0.34f, 0.90f to 0.24f, 0.32f to 0.42f, 0.56f to 0.38f,
            )
            for ((fx, fy) in dots) {
                drawCircle(
                    color = Color.White.copy(alpha = if (dark) 0.35f else 0.7f),
                    radius = 2.5f,
                    center = Offset(w * fx, h * fy),
                )
            }
        }
    }
}
