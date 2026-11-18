// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Dark scheme: night purple field, warm orange primary, lavender secondary.
private val DarkColorScheme = darkColorScheme(
    primary = ShushuOrange,       // Primary buttons and emphasis: warm orange
    onPrimary = ShushuOrangeOnDark,
    primaryContainer = ShushuOrangeContainerDark,
    onPrimaryContainer = Color(0xFFFFD9B0),
    secondary = ShushuLavender,
    onSecondary = Neutral0,
    secondaryContainer = Color(0xFF3D3460),
    onSecondaryContainer = Color(0xFFDCD5F2),
    tertiary = ShushuLavender,
    onTertiary = Neutral0,
    tertiaryContainer = Color(0xFF3D3460),
    onTertiaryContainer = Color(0xFFDCD5F2),
    background = ShushuFieldDarkTop,
    surface = Color(0xFF2E2440),
    surfaceVariant = Color(0xFF3A2F52),
    surfaceDim = ShushuFieldDarkTop,
    surfaceBright = Color(0xFF3A2F52),
    surfaceContainerLowest = ShushuFieldDarkTop,
    surfaceContainerLow = Color(0xFF2E2440),
    surfaceContainer = Color(0xFF2E2440),
    surfaceContainerHigh = Color(0xFF3A2F52),
    surfaceContainerHighest = Color(0xFF463A63),
    surfaceTint = Color.Transparent,
    onBackground = ShushuInkDark,
    onSurface = ShushuInkDark,
    onSurfaceVariant = ShushuInkDarkSecondary,
    outline = Color(0xFF4A3D63),
    outlineVariant = Color(0xFF3A2F52),
    error = StatusError,
    onError = Neutral0,
    errorContainer = StatusErrorContainerDark,
    onErrorContainer = StatusOnErrorContainerDark,
)

// Light scheme: morning field, warm orange primary, lavender secondary.
private val LightColorScheme = lightColorScheme(
    primary = ShushuOrange,       // Primary buttons and emphasis: warm orange
    onPrimary = Neutral0,
    primaryContainer = ShushuOrangeContainerLight,
    onPrimaryContainer = Color(0xFF5A2E0E),
    secondary = ShushuLavender,
    onSecondary = Neutral0,
    secondaryContainer = ShushuLavenderContainerLight,
    onSecondaryContainer = Color(0xFF3D3460),
    tertiary = ShushuLavender,
    onTertiary = Neutral0,
    tertiaryContainer = ShushuLavenderContainerLight,
    onTertiaryContainer = Color(0xFF3D3460),
    background = ShushuFieldLightTop,
    surface = Color(0xFFFFFDF8),
    surfaceVariant = Color(0xFFF3EADF),
    surfaceDim = Color(0xFFF3EADF),
    surfaceBright = LightFloating,
    surfaceContainerLowest = LightFloating,
    surfaceContainerLow = Color(0xFFFFF8EF),
    surfaceContainer = Color(0xFFF7EFE6),
    surfaceContainerHigh = Color(0xFFF3EADF),
    surfaceContainerHighest = Color(0xFFEDE2D3),
    surfaceTint = Color.Transparent,
    onBackground = ShushuInkLight,
    onSurface = ShushuInkLight,
    onSurfaceVariant = ShushuInkLightSecondary,
    outline = Color(0xFFE8DDD2),
    outlineVariant = Color(0xFFF0E7DB),
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
        ) {
            // The field sits behind every page; pages keep transparent backgrounds.
            Box(Modifier.fillMaxSize()) {
                com.suanran.shushu.ui.FieldBackground()
                content()
            }
        }
    }
}
