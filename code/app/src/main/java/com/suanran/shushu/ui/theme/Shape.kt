// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Corner radius scale. Pills use CircleShape; sub-8dp radii are reserved for chart and inline marks. */
internal object AppShapes {
    /** A card's corner, for cards built from list rows that round only their ends. */
    val CardRadius = 16.dp
    /** Thumbnails, icon tiles and other small media. */
    val Small = RoundedCornerShape(8.dp)
    /** Buttons, text fields, chips and surfaces nested inside cards. */
    val Control = RoundedCornerShape(10.dp)
    /** Cards, panels, dialogs and grouped lists. */
    val Card = RoundedCornerShape(CardRadius)
    /** The composer, message bubbles, popovers and full-width primary actions. */
    val Large = RoundedCornerShape(16.dp)
}

/** Material defaults follow the same scale so unstyled menus, fields and sheets match. */
internal val JiumiShapes = Shapes(
    extraSmall = AppShapes.Control,
    small = AppShapes.Control,
    medium = AppShapes.Card,
    large = AppShapes.Large,
    extraLarge = RoundedCornerShape(24.dp)
)
