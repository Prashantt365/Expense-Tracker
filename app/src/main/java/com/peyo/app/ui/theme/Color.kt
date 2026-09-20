package com.peyo.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Peyo's own palette, built out from the sprout in the launcher icon rather than left on the
 * Material baseline purple.
 *
 * The tones are written out by hand instead of generated from a seed at runtime, because an app
 * whose identity is a colour cannot have that colour arrive only on the phones that support
 * dynamic colour. Dynamic colour is still honoured when the user asks for it -- see
 * [com.peyo.app.ui.theme.PeyoTheme] -- it is just not the default.
 */
private val Emerald10 = Color(0xFF002117)
private val Emerald20 = Color(0xFF00382A)
private val Emerald30 = Color(0xFF00513C)
private val Emerald40 = Color(0xFF00674F)
private val Emerald80 = Color(0xFF72DBB4)
private val Emerald90 = Color(0xFF8FF7CF)

private val Sage10 = Color(0xFF072019)
private val Sage20 = Color(0xFF1D352D)
private val Sage30 = Color(0xFF334B43)
private val Sage40 = Color(0xFF4B635B)
private val Sage80 = Color(0xFFB1CCC1)
private val Sage90 = Color(0xFFCDE9DD)

private val Amber10 = Color(0xFF261A00)
private val Amber20 = Color(0xFF3F2E00)
private val Amber30 = Color(0xFF5B4300)
private val Amber40 = Color(0xFF7A5900)
private val Amber80 = Color(0xFFE6C36C)
private val Amber90 = Color(0xFFFFDF95)

private val Neutral10 = Color(0xFF171D1A)
private val Neutral90 = Color(0xFFDEE4DF)

val PeyoLightColors = lightColorScheme(
    primary = Emerald40,
    onPrimary = Color.White,
    primaryContainer = Emerald90,
    onPrimaryContainer = Emerald10,
    inversePrimary = Emerald80,

    secondary = Sage40,
    onSecondary = Color.White,
    secondaryContainer = Sage90,
    onSecondaryContainer = Sage10,

    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFF5FBF6),
    onBackground = Neutral10,
    surface = Color(0xFFF5FBF6),
    onSurface = Neutral10,
    surfaceVariant = Color(0xFFDBE5DF),
    onSurfaceVariant = Color(0xFF3F4945),
    surfaceTint = Emerald40,

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F0),
    surfaceContainer = Color(0xFFE9F0EB),
    surfaceContainerHigh = Color(0xFFE4EAE5),
    surfaceContainerHighest = Color(0xFFDEE4DF),

    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C3),
    inverseSurface = Color(0xFF2B322E),
    inverseOnSurface = Color(0xFFECF2ED),
    scrim = Color.Black
)

val PeyoDarkColors = darkColorScheme(
    primary = Emerald80,
    onPrimary = Emerald20,
    primaryContainer = Emerald30,
    onPrimaryContainer = Emerald90,
    inversePrimary = Emerald40,

    secondary = Sage80,
    onSecondary = Sage20,
    secondaryContainer = Sage30,
    onSecondaryContainer = Sage90,

    tertiary = Amber80,
    onTertiary = Amber20,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF0F1512),
    onBackground = Neutral90,
    surface = Color(0xFF0F1512),
    onSurface = Neutral90,
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C3),
    surfaceTint = Emerald80,

    surfaceContainerLowest = Color(0xFF0A100D),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF252B28),
    surfaceContainerHighest = Color(0xFF303633),

    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4945),
    inverseSurface = Neutral90,
    inverseOnSurface = Color(0xFF2B322E),
    scrim = Color.Black
)

/**
 * The chart palette, which has to be declared per theme rather than once.
 *
 * A single set of mid-tone colours is the usual shortcut and it fails at both ends: dark enough to
 * read on a pale surface is too dark to read on a black one. These are two sets of the same hues,
 * lightened for dark mode, so a category keeps its identity when the phone switches theme.
 */
data class ChartPalette(
    /** What I bore myself. */
    val mine: Color,
    /** What I am carrying for other people. */
    val others: Color,
    /** What has come back. */
    val settled: Color,
    val categorical: List<Color>
) {
    fun at(index: Int): Color = categorical[index.mod(categorical.size)]
}

val LightChartPalette = ChartPalette(
    mine = Color(0xFF00674F),
    others = Color(0xFFB3541E),
    settled = Color(0xFF2E7D6F),
    categorical = listOf(
        Color(0xFF00674F), Color(0xFF3B6FB6), Color(0xFFB3541E), Color(0xFF8E4585),
        Color(0xFF77702A), Color(0xFF9C4146), Color(0xFF2E7D6F), Color(0xFF5B5BA6)
    )
)

val DarkChartPalette = ChartPalette(
    mine = Color(0xFF72DBB4),
    others = Color(0xFFFFB68C),
    settled = Color(0xFF7FD0C0),
    categorical = listOf(
        Color(0xFF72DBB4), Color(0xFF9FC6FF), Color(0xFFFFB68C), Color(0xFFF3B0E4),
        Color(0xFFDCD48A), Color(0xFFFFB3B4), Color(0xFF7FD0C0), Color(0xFFBEC2FF)
    )
)
