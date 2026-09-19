package com.example.expensetracker.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import com.example.expensetracker.MainActivity
import com.example.expensetracker.ui.theme.PeyoDarkColors
import com.example.expensetracker.ui.theme.PeyoLightColors
import com.example.expensetracker.ui.theme.ThemeMode
import com.example.expensetracker.ui.theme.ThemeSettings

/**
 * Peyo's own colours inside the widget, taken from the same two schemes the app is built on.
 *
 * Glance defaults to the wallpaper-derived scheme on Android 12 and above, which would leave the
 * widget a different colour from the app it belongs to on exactly the phones that show both side
 * by side. It follows the user's dynamic-colour setting instead, the same one the app follows.
 */
private val PeyoLightProviders = ColorProviders(PeyoLightColors)
private val PeyoDarkProviders = ColorProviders(PeyoDarkColors)

/**
 * Reads the stored preferences, then themes the widget from them.
 *
 * The read happens in [androidx.glance.appwidget.GlanceAppWidget.provideGlance] rather than in the
 * composable below, because a widget update can arrive with the activity long gone and
 * [ThemeSettings] is Compose state the activity would otherwise have been the only thing to fill.
 */
fun loadWidgetTheme(context: Context) = ThemeSettings.load(context)

@Composable
fun PeyoGlanceTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    // When dynamic color is active we let GlanceTheme pick up the system wallpaper palette on
    // its own — that is exactly what dynamic color means — so we pass no explicit providers.
    // Passing GlanceTheme.colors as the `colors` argument would read the current Glance color
    // scheme *before* a GlanceTheme scope exists and crash the widget composition.
    if (ThemeSettings.dynamicColor && ThemeSettings.supportsDynamic) {
        GlanceTheme(content = content)
    } else {
        GlanceTheme(
            colors = if (darkTheme) PeyoDarkProviders else PeyoLightProviders,
            content = content
        )
    }
}

/**
 * Whether the widget should draw dark, decided the same way the app decides it.
 *
 * A two-provider ColorProviders would resolve this from the widget's own configuration, which is
 * the system setting -- so somebody who has chosen Dark inside Peyo while their phone is on Light
 * would get a light widget beside a dark app. The app's own choice wins, and only System defers to
 * the phone.
 */
fun widgetIsDark(context: Context): Boolean = when (ThemeSettings.mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> context.resources.configuration.uiMode
        .and(Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

/**
 * What tapping something in a widget does.
 *
 * Each one is an action MainActivity already understands, because the launcher shortcuts use the
 * same set. A widget button and a long-press shortcut therefore cannot drift apart, and adding a
 * fifth would be one entry here and one in the manifest's filter.
 */
enum class WidgetAction(val intentAction: String?) {
    OPEN(null),
    ADD("com.example.expensetracker.ADD_EXPENSE"),
    SPLIT("com.example.expensetracker.SPLIT_EXPENSE"),
    IMPORT("com.example.expensetracker.IMPORT_PDF"),
    BALANCES("com.example.expensetracker.OPEN_BALANCES");

    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java).also {
        it.action = intentAction ?: Intent.ACTION_MAIN
        // A widget tap that lands on the task as it was left is the wrong outcome for an action:
        // the activity is singleTask, so this is what guarantees onNewIntent runs and the request
        // is actually read rather than silently dropped on resume.
        it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
}

/** A round, tinted icon button as used across the widgets. */
@Composable
fun WidgetIconButton(
    context: Context,
    iconRes: Int,
    description: String,
    action: WidgetAction,
    size: Int = 40,
    container: ColorProvider = GlanceTheme.colors.primaryContainer,
    tint: ColorProvider = GlanceTheme.colors.onPrimaryContainer
) {
    Box(
        GlanceModifier
            .size(size.dp)
            .cornerRadius((size / 2).dp)
            .background(container)
            .clickable(actionStartActivity(action.intent(context))),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = description,
            modifier = GlanceModifier.size((size * 0.5).dp).padding(0.dp),
            colorFilter = ColorFilter.tint(tint)
        )
    }
}
