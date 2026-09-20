package com.peyo.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * The Material scale, tightened where this app actually leans on it.
 *
 * Display and headline sizes carry amounts, and the baseline tracking is set for prose: at
 * display size a positive letter spacing pulls a five-figure sum wider than the card holding it.
 * Labels go the other way and are given a little more weight, because they are read as chips and
 * axis ticks rather than as running text.
 */
val PeyoTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp
        ),
        headlineLarge = base.headlineLarge.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp
        ),
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp
        ),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium)
    )
}

/**
 * Lining, equal-width figures.
 *
 * Amounts are stacked in columns all over this app -- the transaction list, the split summary,
 * every balance row -- and proportional digits make a column of them ripple. "tnum" is the
 * OpenType feature that fixes the advance width; "lnum" keeps a font with old-style figures by
 * default from dropping a 4 below the baseline in the middle of a total.
 */
val MoneyTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontFeatureSettings = "tnum, lnum",
    textAlign = TextAlign.End
)
