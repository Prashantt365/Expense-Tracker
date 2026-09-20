package com.peyo.app.widget

import android.content.Context
import com.peyo.app.AppCurrency
import com.peyo.app.data.AppDatabase
import com.peyo.app.minePaise
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything a widget draws, read once per update.
 *
 * A widget is composed off the main thread and then handed to the launcher as a finished set of
 * RemoteViews: there is no recomposition afterwards and nothing to observe a Flow from. So this is
 * a single snapshot, taken at the moment the widget is asked to redraw, rather than state the
 * widget subscribes to.
 */
data class WidgetSnapshot(
    val monthPaise: Long,
    val owedPaise: Long,
    val expenseCount: Int,
    val recent: List<RecentExpense>,
    val topPerson: String?,
    val topPersonPaise: Long
) {
    companion object {
        val Empty = WidgetSnapshot(0, 0, 0, emptyList(), null, 0)
    }
}

data class RecentExpense(
    val title: String,
    val category: String,
    val amountPaise: Long,
    val paidAt: Long
)

object WidgetData {

    /**
     * Reads the current figures straight from the shared database.
     *
     * [AppCurrency] is loaded here too. It is Compose state that the activity fills on start, and
     * a widget update can arrive with the activity long since gone -- without this the widget
     * would format every amount in the fallback currency rather than the chosen one.
     *
     * Bounded by a timeout, and empty rather than absent on failure. Whatever goes wrong down
     * here, the caller has to be able to compose something: a widget whose provideGlance never
     * returns is a widget that shows its placeholder layout until it is removed and added again,
     * with nothing on screen to say why.
     */
    suspend fun load(context: Context): WidgetSnapshot = try {
        withTimeout(TIMEOUT_MILLIS) { read(context) }
    } catch (_: TimeoutCancellationException) {
        WidgetSnapshot.Empty
    }

    private suspend fun read(context: Context): WidgetSnapshot {
        AppCurrency.load(context)
        val dao = AppDatabase.get(context.applicationContext).expenseDao()

        val monthStart = LocalDate.now(ZoneId.systemDefault())
            .withDayOfMonth(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val thisMonth = dao.detailsSince(monthStart)
        val balances = dao.balancesNow()
        val worst = balances.maxByOrNull { it.owedPaise }?.takeIf { it.owedPaise > 0 }

        return WidgetSnapshot(
            // The same arithmetic the Insights headline uses: my own share, with everything
            // assigned to other people taken out.
            monthPaise = thisMonth.sumOf { it.minePaise() },
            owedPaise = balances.sumOf { it.owedPaise },
            expenseCount = thisMonth.size,
            recent = dao.recentDetails(RECENT_SHOWN).map { details ->
                RecentExpense(
                    title = details.expense.merchant.ifBlank { details.expense.category },
                    category = details.expense.category,
                    amountPaise = details.expense.amountPaise,
                    paidAt = details.expense.paidAt
                )
            },
            topPerson = worst?.name,
            topPersonPaise = worst?.owedPaise ?: 0
        )
    }

    /** As many as the tallest supported widget can show without scrolling into nothing. */
    private const val RECENT_SHOWN = 5

    /**
     * Comfortably longer than the query takes on a large database, and comfortably shorter than
     * the window the system gives a widget update before it gives up on it.
     */
    private const val TIMEOUT_MILLIS = 5_000L
}
