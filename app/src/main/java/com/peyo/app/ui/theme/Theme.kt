package com.peyo.app.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Whether the phone's own wallpaper colours are used in place of Peyo's.
 *
 * Off by default, and remembered. A tracker that keeps a running total in a coloured hero card
 * has to be able to promise what that colour means -- green for what has come back, warm for what
 * is still out -- and a wallpaper-derived scheme cannot promise it. Anybody who would rather have
 * the phone's own colours can say so in Settings, on a phone new enough to have them.
 */
object ThemeSettings {

    private const val PREFS = "peyo.settings"
    private const val KEY_DYNAMIC = "dynamicColor"
    private const val KEY_MODE = "themeMode"

    val supportsDynamic: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    var dynamicColor by mutableStateOf(false)
        private set

    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    fun load(context: Context) {
        val prefs = prefs(context)
        dynamicColor = supportsDynamic && prefs.getBoolean(KEY_DYNAMIC, false)
        mode = ThemeMode.of(prefs.getString(KEY_MODE, null))
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        if (!supportsDynamic) return
        dynamicColor = enabled
        prefs(context).edit().putBoolean(KEY_DYNAMIC, enabled).apply()
    }

    fun setMode(context: Context, newMode: ThemeMode) {
        mode = newMode
        prefs(context).edit().putString(KEY_MODE, newMode.stored).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

enum class ThemeMode(val stored: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun of(stored: String?): ThemeMode = entries.firstOrNull { it.stored == stored } ?: SYSTEM
    }
}

/** So a chart can take its colours from the theme rather than hard-coding one set for both. */
val LocalChartPalette = compositionLocalOf { LightChartPalette }

@Composable
fun PeyoTheme(
    darkTheme: Boolean = when (ThemeSettings.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    },
    dynamicColor: Boolean = ThemeSettings.dynamicColor,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && ThemeSettings.supportsDynamic ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> PeyoDarkColors
        else -> PeyoLightColors
    }

    // The bars are drawn through, so only the icon colour has to follow the theme. Without this a
    // dark theme leaves black status icons on a black bar, which is the one thing edge-to-edge
    // reliably gets wrong.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalChartPalette provides if (darkTheme) DarkChartPalette else LightChartPalette
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = PeyoTypography,
            shapes = PeyoShapes,
            content = content
        )
    }
}
