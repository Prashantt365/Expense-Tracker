package com.example.expensetracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.glance.appwidget.updateAll

/**
 * Keeping the home screen in step with the app, and putting a widget there from inside it.
 */
object PeyoWidgets {

    /**
     * Redraws every placed widget.
     *
     * Called after anything that changes a figure a widget shows. The alternative -- leaning on
     * the 30 minute updatePeriodMillis in the widget's own XML -- means the total on the home
     * screen can disagree with the total in the app for half an hour after recording an expense,
     * which is the single most visible way for a widget to look broken.
     *
     * Safe to call when no widget has been added: updateAll simply finds nothing to update.
     */
    suspend fun refresh(context: Context) {
        val application = context.applicationContext
        runCatching {
            SummaryWidget().updateAll(application)
            QuickAddWidget().updateAll(application)
        }
    }

    /** Which widgets this app offers, as the settings screen lists them. */
    enum class Kind(
        val title: String,
        val description: String,
        internal val receiver: Class<*>
    ) {
        SUMMARY(
            "Spending summary",
            "This month's spending and what you are owed, with quick actions. Resize it: the " +
                "wider and taller it is, the more it shows.",
            SummaryWidgetReceiver::class.java
        ),
        QUICK_ADD(
            "Add expense",
            "A single button that opens a blank expense, for recording a payment the moment you " +
                "make it.",
            QuickAddWidgetReceiver::class.java
        )
    }

    /**
     * Whether the launcher will accept a widget placed by the app rather than dragged in by hand.
     *
     * Most will; a few, and every launcher before Android 8, will not, and there is no way to ask
     * except to ask. The settings screen falls back to telling the user how to do it themselves.
     */
    fun canPin(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

    /** Asks the launcher to place [kind] on the home screen. Returns false if it declined. */
    fun requestPin(context: Context, kind: Kind): Boolean {
        if (!canPin(context)) return false
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, kind.receiver)
        // The callback is only so that the launcher has somewhere to report success; nothing in
        // the app depends on it, since a placed widget draws itself from the database anyway.
        val callback = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, kind.receiver).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return runCatching { manager.requestPinAppWidget(provider, null, callback) }
            .getOrDefault(false)
    }
}
