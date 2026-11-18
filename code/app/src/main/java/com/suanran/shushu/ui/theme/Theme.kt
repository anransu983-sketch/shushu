// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Dark scheme: near-black canvas, near-white primary actions, blue for function accents.
private val DarkColorScheme = darkColorScheme(
    primary = DarkInk,            // Primary buttons and Material fills: near white in the dark, dark text on them
    onPrimary = DarkCanvas,
    primaryContainer = Neutral800,
    onPrimaryContainer = Neutral50,
    secondary = Neutral400,
    onSecondary = Neutral900,
    secondaryContainer = Neutral800,
    onSecondaryContainer = Neutral300,
    tertiary = Neutral400,        // Neutral: no colored accent
    onTertiary = Neutral0,
    tertiaryContainer = Neutral800,
    onTertiaryContainer = Neutral50,
    background = DarkCanvas,
    surface = DarkCard,
    surfaceVariant = DarkContainerHigh,
    surfaceDim = DarkCanvas,
    surfaceBright = DarkContainerHigh,
    surfaceContainerLowest = DarkCanvas,
    surfaceContainerLow = DarkCard,
    surfaceContainer = DarkCard,
    surfaceContainerHigh = DarkContainerHigh,
    surfaceContainerHighest = DarkContainerHighest,
    surfaceTint = Color.Transparent,
    onBackground = DarkInk,
    onSurface = DarkInk,
    onSurfaceVariant = DarkInkSecondary,
    outline = DarkContainerHighest,
    outlineVariant = DarkSeparator,
    error = StatusError,
    onError = Neutral0,
    errorContainer = StatusErrorContainerDark,
    onErrorContainer = StatusOnErrorContainerDark,
)

// Light scheme: a quiet light canvas, graphite primary actions, blue for function accents.
private val LightColorScheme = lightColorScheme(
    primary = LightInk,           // Primary buttons and emphasis: graphite
    onPrimary = Neutral0,
    primaryContainer = Neutral200,
    onPrimaryContainer = Neutral900,
    secondary = Neutral600,
    onSecondary = Neutral0,
    secondaryContainer = Neutral100,
    onSecondaryContainer = Neutral700,
    tertiary = Neutral400,        // Neutral: no colored accent
    onTertiary = Neutral0,
    tertiaryContainer = Neutral200,
    onTertiaryContainer = Neutral900,
    // Canvas < card < overlay, each a small step lighter.
    background = LightCanvas,
    surface = LightCard,
    surfaceVariant = LightContainerHigh,
    surfaceDim = LightContainerHigh,
    surfaceBright = LightFloating,
    surfaceContainerLowest = LightFloating,
    surfaceContainerLow = LightContainerLow,
    surfaceContainer = LightContainer,
    surfaceContainerHigh = LightContainerHigh,
    surfaceContainerHighest = LightContainerHighest,
    surfaceTint = Color.Transparent,
    onBackground = LightInk,
    onSurface = LightInk,
    onSurfaceVariant = LightInkSecondary,
    outline = LightContainerHighest,
    outlineVariant = LightContainerHigh,
    error = StatusError,
    onError = Neutral0,
)

/**
 * Tells children whether the dark theme is on (for the glass effects and the like).
 */
val LocalAppIsDark = staticCompositionLocalOf { false }

/** Follows the system light/dark setting. */
@Composable
fun JiumiTheme(content: @Composable () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDark
        }
    }

    // Selection stays neutral, like everything else: graphite handles over a grey wash.
    val selectionColors = TextSelectionColors(
        handleColor = colorScheme.onSurface,
        backgroundColor = colorScheme.onSurface.copy(alpha = 0.2f)
    )

    CompositionLocalProvider(
        LocalAppIsDark provides isDark,
        LocalTextSelectionColors provides selectionColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = remember(isDark) { jiumiTypography(isDark) },
            shapes = JiumiShapes,
            content = content
        )
    }
}
