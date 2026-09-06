package com.example.expensetracker

import com.example.expensetracker.data.ExpenseDetails
import com.example.expensetracker.data.Person
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

enum class Period(val label: String) {
    MONTH("This month"),
    YEAR("This year"),
    ALL("All time")
}

data class MonthPoint(
    val month: YearMonth,
    val label: String,
    /** What I actually bore. */
    val minePaise: Long,
    /** What was assigned to other people. */
    val othersPaise: Long
) {
    val totalPaise: Long get() = minePaise + othersPaise
}

data class CategorySlice(val category: String, val paise: Long, val fraction: Float)

data class PersonAnalytics(
    val personId: Long,
    val name: String,
    /** Everything ever assigned to them in the period. */
    val sharedPaise: Long,
    /** What they have paid back. */
    val settledPaise: Long,
    /** What I am still carrying for them: the effective cost of this person to me. */
    val outstandingPaise: Long,
    val expenseCount: Int,
    val lastSharedAt: Long?
) {
    val recoveredFraction: Float get() = if (sharedPaise == 0L) 0f else settledPaise.toFloat() / sharedPaise
}

data class WeekdayPoint(val label: String, val paise: Long)

data class AnalyticsReport(
    val period: Period,
    /** My own spend, with everything other people owe stripped out. */
    val minePaise: Long,
    /** Total assigned to other people in the period. */
    val onOthersPaise: Long,
    val settledPaise: Long,
    /** What remains unrecovered: the effective amount I have spent on other people. */
    val outstandingPaise: Long,
    val expenseCount: Int,
    val dailyAveragePaise: Long,
    val largest: ExpenseDetails?,
    /** Always the trailing 12 months, so a short period still has context. */
    val monthly: List<MonthPoint>,
    val categories: List<CategorySlice>,
    val people: List<PersonAnalytics>,
    val weekdays: List<WeekdayPoint>,
    /** My spend this month against last month, as a fraction. Null when last month is empty. */
    val monthOverMonth: Float?
) {
    val grossPaise: Long get() = minePaise + onOthersPaise
    val hasData: Boolean get() = expenseCount > 0
}

/**
 * Builds every figure the dashboard shows.
 *
 * Kept as pure functions over the already-loaded expense list rather than SQL aggregates: a
 * personal tracker holds few enough rows for it not to matter, and it makes the arithmetic - which
 * is the part that is easy to get subtly wrong - directly testable.
 */
object Analytics {

    fun build(
        expenses: List<ExpenseDetails>,
        people: List<Person>,
        period: Period,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone)
    ): AnalyticsReport {
        val inPeriod = expenses.filter { period.contains(it.expense.paidAt, zone, today) }

        val minePaise = inPeriod.sumOf { it.minePaise() }
        val onOthersPaise = inPeriod.sumOf { it.othersPaise() }
        val settledPaise = inPeriod.sumOf { it.settledPaise() }
        val outstandingPaise = inPeriod.sumOf { it.outstandingPaise }

        return AnalyticsReport(
            period = period,
            minePaise = minePaise,
            onOthersPaise = onOthersPaise,
            settledPaise = settledPaise,
            outstandingPaise = outstandingPaise,
            expenseCount = inPeriod.size,
            dailyAveragePaise = dailyAverage(minePaise, inPeriod, period, zone, today),
            largest = inPeriod.maxByOrNull { it.expense.amountPaise },
            monthly = monthlyTrend(expenses, zone, today),
            categories = categorySlices(inPeriod),
            people = peopleAnalytics(inPeriod, people),
            weekdays = weekdayPattern(inPeriod, zone),
            monthOverMonth = monthOverMonth(expenses, zone, today)
        )
    }

    /** The trailing 12 months including the current one, with empty months kept as zeroes. */
    private fun monthlyTrend(all: List<ExpenseDetails>, zone: ZoneId, today: LocalDate): List<MonthPoint> {
        val current = YearMonth.from(today)
        val byMonth = all.groupBy { YearMonth.from(it.expense.paidAt.toLocalDate(zone)) }
        return (11 downTo 0).map { back ->
            val month = current.minusMonths(back.toLong())
            val rows = byMonth[month].orEmpty()
            MonthPoint(
                month = month,
                label = month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                minePaise = rows.sumOf { it.minePaise() },
                othersPaise = rows.sumOf { it.othersPaise() }
            )
        }
    }

    /** Categories describe my own spending, so other people's shares are excluded. */
    private fun categorySlices(rows: List<ExpenseDetails>): List<CategorySlice> {
        val totals = rows.groupBy { it.expense.category }
            .mapValues { entry -> entry.value.sumOf { it.minePaise() } }
            .filterValues { it > 0 }
        val total = totals.values.sum()
        if (total == 0L) return emptyList()
        return totals.entries
            .sortedByDescending { it.value }
            .map { CategorySlice(it.key, it.value, it.value.toFloat() / total) }
    }

    private fun peopleAnalytics(rows: List<ExpenseDetails>, people: List<Person>): List<PersonAnalytics> {
        val names = people.associate { it.id to it.name }
        val shares = rows.flatMap { details ->
            details.splits.filter { it.personId != null }.map { details to it }
        }
        return shares.groupBy { (_, split) -> split.personId!! }
            .map { (personId, entries) ->
                PersonAnalytics(
                    personId = personId,
                    name = names[personId] ?: "Removed person",
                    sharedPaise = entries.sumOf { (_, split) -> split.amountPaise },
                    settledPaise = entries.filter { (_, split) -> split.settledAt != null }
                        .sumOf { (_, split) -> split.amountPaise },
                    outstandingPaise = entries.filter { (_, split) -> split.settledAt == null }
                        .sumOf { (_, split) -> split.amountPaise },
                    expenseCount = entries.size,
                    lastSharedAt = entries.maxOfOrNull { (details, _) -> details.expense.paidAt }
                )
            }
            .sortedByDescending { it.outstandingPaise }
    }

    private fun weekdayPattern(rows: List<ExpenseDetails>, zone: ZoneId): List<WeekdayPoint> {
        val byDay = rows.groupBy { it.expense.paidAt.toLocalDate(zone).dayOfWeek }
        return DayOfWeek.entries.map { day ->
            WeekdayPoint(
                label = day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                paise = byDay[day].orEmpty().sumOf { it.minePaise() }
            )
        }
    }

    private fun monthOverMonth(all: List<ExpenseDetails>, zone: ZoneId, today: LocalDate): Float? {
        val current = YearMonth.from(today)
        val previous = current.minusMonths(1)
        val byMonth = all.groupBy { YearMonth.from(it.expense.paidAt.toLocalDate(zone)) }
        val before = byMonth[previous].orEmpty().sumOf { it.minePaise() }
        if (before == 0L) return null
        val now = byMonth[current].orEmpty().sumOf { it.minePaise() }
        return (now - before).toFloat() / before
    }

    private fun dailyAverage(
        minePaise: Long,
        rows: List<ExpenseDetails>,
        period: Period,
        zone: ZoneId,
        today: LocalDate
    ): Long {
        if (rows.isEmpty()) return 0
        val days = when (period) {
            Period.MONTH -> today.dayOfMonth.toLong()
            Period.YEAR -> today.dayOfYear.toLong()
            // Span from the first recorded expense, so an old history is not flattered by a
            // denominator that only counts this year.
            Period.ALL -> {
                val first = rows.minOf { it.expense.paidAt }.toLocalDate(zone)
                java.time.temporal.ChronoUnit.DAYS.between(first, today) + 1
            }
        }.coerceAtLeast(1)
        return minePaise / days
    }

    private fun Period.contains(millis: Long, zone: ZoneId, today: LocalDate): Boolean {
        val date = millis.toLocalDate(zone)
        return when (this) {
            Period.MONTH -> YearMonth.from(date) == YearMonth.from(today)
            Period.YEAR -> date.year == today.year
            Period.ALL -> true
        }
    }

    private fun Long.toLocalDate(zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
}

/** My own share: what is left after everyone else's shares are taken out. */
fun ExpenseDetails.minePaise(): Long =
    if (splits.isEmpty()) expense.amountPaise
    else splits.filter { it.personId == null }.sumOf { it.amountPaise }

fun ExpenseDetails.othersPaise(): Long =
    splits.filter { it.personId != null }.sumOf { it.amountPaise }

fun ExpenseDetails.settledPaise(): Long =
    splits.filter { it.personId != null && it.settledAt != null }.sumOf { it.amountPaise }
