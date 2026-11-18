// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

/**
 * Five sizes and nothing between them, kept small so space and alignment carry the hierarchy:
 * - page, 20: the name of a tab page in its top bar, and nothing else (headlineLarge);
 * - large, 18: the one name a page is about (an app, this app on About), dialog and bar titles;
 * - medium, 14: what a row or a paragraph says;
 * - small, 12: what it adds (subtitles, descriptions, values);
 * - caption, 11: times, counts, badges, group titles, chart axes, tab names.
 * Weight tells rank within a size: titles semibold, body regular, captions medium. Every Material
 * slot maps onto one of the five, so whichever a screen asks for lands on the scale.
 */
fun jiumiTypography(isDark: Boolean): Typography {
    val medium = FontWeight(if (isDark) 480 else 500)
    val semibold = FontWeight(if (isDark) 580 else 600)
    val page = TextStyle(fontWeight = semibold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 0.sp, lineBreak = LineBreak.Heading)
    val large = TextStyle(fontWeight = semibold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp, lineBreak = LineBreak.Heading)
    val title = TextStyle(fontWeight = semibold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
    val body = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
    val small = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
        fontFeatureSettings = "tnum")
    val smallStrong = small.copy(fontWeight = medium)
    val caption = TextStyle(fontWeight = medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp, fontFeatureSettings = "tnum")
    return Typography(
        displayLarge = large, displayMedium = large, displaySmall = large,
        headlineLarge = page, headlineMedium = large, headlineSmall = large,
        titleLarge = large, titleMedium = title, titleSmall = title,
        bodyLarge = body, bodyMedium = body, bodySmall = small,
        labelLarge = smallStrong, labelMedium = caption, labelSmall = caption
    )
}

/**
 * Three inks for text and one fill, the same on every page: primary for what is read first,
 * secondary for what it adds, tertiary for what is barely needed (a second time, a count beside a
 * heading); [fill] behind grey tiles, pills and fields. Amber (StatusWarning) is the only colour.
 */
object Ink {
    val primary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
    val secondary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val tertiary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val fill: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    /** The sunken track of a segmented control, a step darker than [fill] so its raised thumb stands out. */
    val track: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f)
}
