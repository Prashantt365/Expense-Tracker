package com.example.expensetracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.expensetracker.R
import com.example.expensetracker.ui.dayHeading
import com.example.expensetracker.ui.money
import com.example.expensetracker.ui.moneyCompact

/**
 * The home screen widget: this month's own spending, what is still owed to you, and the actions
 * worth having without opening the app.
 *
 * One widget rather than three, resized by the user, because the interesting question is not
 * which figures they want but how much room they are willing to give up. The same data is laid out
 * three ways and the launcher picks by the size actually on the home screen -- a 2x2 shows the
 * headline alone, a 4x2 adds the quick actions, a 4x4 adds what was last recorded.
 */
class SummaryWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(TINY, SMALL, MEDIUM, LARGE)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read before provideContent, not inside it: a Glance composition has no coroutine scope
        // of its own to suspend in, and a widget that composes before its data has arrived is a
        // widget that shows zero for a frame and then flickers.
        loadWidgetTheme(context)
        val snapshot = WidgetData.load(context)
        val dark = widgetIsDark(context)
        provideContent {
            PeyoGlanceTheme(dark) { SummaryContent(snapshot) }
        }
    }

    companion object {
        /** Just the headline figure: a 2x1 strip. */
        val TINY = DpSize(120.dp, 50.dp)

        /** Headline plus the owed line, the ordinary 2x2. */
        val SMALL = DpSize(140.dp, 110.dp)

        /** Wide enough for the quick actions beside the figures. */
        val MEDIUM = DpSize(250.dp, 110.dp)

        /** Tall enough for what was last recorded as well. */
        val LARGE = DpSize(250.dp, 220.dp)
    }
}

@Composable
private fun SummaryContent(snapshot: WidgetSnapshot) {
    val context = LocalContext.current
    val size = LocalSize.current
    val wide = size.width >= SummaryWidget.MEDIUM.width
    val tall = size.height >= SummaryWidget.LARGE.height
    val tiny = size.height < SummaryWidget.SMALL.height

    Column(
        GlanceModifier
            .fillMaxSize()
            .cornerRadius(24.dp)
            // The surface rather than widgetBackground: the latter comes back as the surface with
            // a primary tint blended over it, which on a green scheme lands within a few per cent
            // of the secondary container the owed strip is drawn in, and the strip disappears.
            .background(GlanceTheme.colors.surface)
            .padding(horizontal = 16.dp, vertical = if (tiny) 10.dp else 14.dp)
            .clickable(actionStartActivity(WidgetAction.OPEN.intent(context)))
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    if (tiny) "Spent" else "Spent this month",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
                Text(
                    // The compact form only where the full one would be ellipsised. A widget is
                    // read at a glance and a truncated total is worse than a rounded one.
                    if (tiny || !wide) moneyCompact(snapshot.monthPaise) else money(snapshot.monthPaise),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = if (tiny) 20.sp else 26.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
            }
            if (!tiny) Image(
                provider = ImageProvider(R.drawable.ic_widget_sprout),
                contentDescription = null,
                modifier = GlanceModifier.size(22.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary)
            )
        }

        if (!tiny) {
            Spacer(GlanceModifier.height(8.dp))
            OwedStrip(snapshot)
        }

        if (wide) {
            Spacer(GlanceModifier.height(12.dp))
            QuickActionRow(context)
        }

        if (tall) {
            Spacer(GlanceModifier.height(12.dp))
            RecentList(snapshot)
        }
    }
}

/** What is still out with other people, in the tone the app uses for exactly that. */
@Composable
private fun OwedStrip(snapshot: WidgetSnapshot) {
    Row(
        GlanceModifier
            .fillMaxWidth()
            .cornerRadius(12.dp)
            .background(GlanceTheme.colors.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(GlanceModifier.defaultWeight()) {
            Text(
                "Owed to you",
                style = TextStyle(
                    color = GlanceTheme.colors.onSecondaryContainer,
                    fontSize = 11.sp
                ),
                maxLines = 1
            )
            snapshot.topPerson?.let { name ->
                Text(
                    "$name owes ${moneyCompact(snapshot.topPersonPaise)}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSecondaryContainer,
                        fontSize = 11.sp
                    ),
                    maxLines = 1
                )
            }
        }
        Text(
            money(snapshot.owedPaise),
            style = TextStyle(
                color = GlanceTheme.colors.onSecondaryContainer,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1
        )
    }
}

@Composable
private fun QuickActionRow(context: Context) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WidgetIconButton(
            context, R.drawable.ic_widget_add, "Add an expense", WidgetAction.ADD,
            container = GlanceTheme.colors.primary,
            tint = GlanceTheme.colors.onPrimary
        )
        Spacer(GlanceModifier.width(8.dp))
        WidgetIconButton(
            context, R.drawable.ic_widget_split, "Split an expense", WidgetAction.SPLIT,
            container = GlanceTheme.colors.secondaryContainer,
            tint = GlanceTheme.colors.onSecondaryContainer
        )
        Spacer(GlanceModifier.width(8.dp))
        WidgetIconButton(
            context, R.drawable.ic_widget_people, "Who owes you", WidgetAction.BALANCES,
            container = GlanceTheme.colors.secondaryContainer,
            tint = GlanceTheme.colors.onSecondaryContainer
        )
        Spacer(GlanceModifier.width(8.dp))
        WidgetIconButton(
            context, R.drawable.ic_widget_import, "Import a statement", WidgetAction.IMPORT,
            container = GlanceTheme.colors.secondaryContainer,
            tint = GlanceTheme.colors.onSecondaryContainer
        )
    }
}

@Composable
private fun RecentList(snapshot: WidgetSnapshot) {
    if (snapshot.recent.isEmpty()) {
        Text(
            "Nothing recorded yet. Tap + to add your first expense.",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)
        )
        return
    }
    Column(GlanceModifier.fillMaxWidth()) {
        Text(
            "Latest",
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )
        Spacer(GlanceModifier.height(4.dp))
        snapshot.recent.take(4).forEach { row ->
            Row(
                GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        row.title,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                        maxLines = 1
                    )
                    Text(
                        "${row.category} • ${dayHeading(row.paidAt)}",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 10.sp
                        ),
                        maxLines = 1
                    )
                }
                Text(
                    money(row.amountPaise),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

class SummaryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SummaryWidget()
}

/**
 * A single button, for anybody who wants recording an expense to be one tap from the home screen
 * and does not want a panel of figures sitting there the rest of the time.
 */
class QuickAddWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        loadWidgetTheme(context)
        val dark = widgetIsDark(context)
        provideContent {
            PeyoGlanceTheme(dark) {
                val size = LocalSize.current
                val roomForLabel = size.height >= 80.dp
                Column(
                    GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(24.dp)
                        .background(GlanceTheme.colors.primaryContainer)
                        .clickable(actionStartActivity(WidgetAction.ADD.intent(context)))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        GlanceModifier.size(if (roomForLabel) 40.dp else 34.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_widget_add),
                            contentDescription = "Add an expense",
                            modifier = GlanceModifier.fillMaxSize(),
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer)
                        )
                    }
                    if (roomForLabel) {
                        Spacer(GlanceModifier.height(2.dp))
                        Text(
                            // "Add expense" is what the picker calls it, and what a 1x1 cell
                            // ellipsises to "Add expen...". One word fits, and beside a plus it
                            // says the same thing.
                            if (size.width >= 110.dp) "Add expense" else "Add",
                            style = TextStyle(
                                color = GlanceTheme.colors.onPrimaryContainer,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}
