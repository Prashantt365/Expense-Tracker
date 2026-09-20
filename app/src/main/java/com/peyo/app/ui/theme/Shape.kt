package com.peyo.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Rounder than the Material baseline throughout.
 *
 * The screens here are almost entirely cards on a tinted background, and at the baseline 12dp a
 * stack of them reads as a single slab with seams. The larger radii separate them without needing
 * borders or elevation, which is what keeps the surface flat and the charts legible.
 */
val PeyoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)
