package com.example.expensetracker.widget

import android.content.Context
import com.example.expensetracker.AppCurrency
import com.example.expensetracker.Analytics
import com.example.expensetracker.Period
import com.example.expensetracker.data.AppDatabase
import com.example.expensetracker.data.ExpenseDetails
import kotlinx.coroutines.flow.first

/**
 * Everything a widget draws, read once per update.
 *
 * A widget is composed off the main thread and then handed to the launcher as a finished set of
 * RemoteViews: there is no recomposition afterwards and no observing a Flow from inside one. So
 * this is a single snapshot, taken at the moment the widget is asked to redraw, rather than state
 * the widget subscribes to.
 */
data class WidgetSnapshot(
    val monthPaise: Long,
    val monthGrossPaise: Long,
    val owedPaise: Long,
    val expenseCount: Int,
    val monthLabel: String,
    val recent: List<RecentExpense>,
    val topPerson: String?,
    val topPersonPaise: Long
) {
    val isEmpty: Boolean get() = expenseCount == 0 && recent.isEmpty()

    companion object {
        val Empty = WidgetSnapshot(0, 0, 0, 0, "", emptyList(), null, 0)
    }
}

data class RecentExpense(
    val id: Long,
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
     */
    suspend fun load(context: Context): WidgetSnapshot {
        AppCurrency.load(context)
        val db = AppDatabase.get(context.applicationContext)
        val expenses: List<ExpenseDetails> = db.expenseDao().observeAll().first()
        val people = db.personDao().observeAll().first()
        val balances = db.expenseDao().observeBalances().first()

        val report = Analytics.build(expenses, people, Period.MONTH)
        val worst = balances.maxByOrNull { it.owedPaise }?.takeIf { it.owedPaise > 0 }

        return WidgetSnapshot(
            monthPaise = report.minePaise,
            monthGrossPaise = report.grossPaise,
            owedPaise = balances.sumOf { it.owedPaise },
            expenseCount = report.expenseCount,
            monthLabel = report.monthly.lastOrNull()?.label.orEmpty(),
            recent = expenses.take(RECENT_SHOWN).map { details ->
                RecentExpense(
                    id = details.expense.id,
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
}
