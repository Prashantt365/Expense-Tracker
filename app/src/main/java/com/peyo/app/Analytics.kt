package com.peyo.app

import com.peyo.app.data.ExpenseDetails
import com.peyo.app.data.ExpenseSplit
import com.peyo.app.data.Person
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The stretch of time the dashboard reports on: one calendar month, the current year, or
 * everything. Serializable so the selection survives the activity being recreated.
 */
sealed interface Period : java.io.Serializable {
    data class Month(val month: YearMonth) : Period
    data object Year : Period
    data object All : Period

    companion object {
        fun thisMonth(today: LocalDate = LocalDate.now()): Period = Month(YearMonth.from(today))
    }
}

/** "This month" for the current month, otherwise the month by name: "August 2026". */
fun Period.label(today: LocalDate = LocalDate.now()): String = when (this) {
    is Period.Month -> if (month == YearMonth.from(today)) "This month" else monthName(month)
    Period.Year -> "This year"
    Period.All -> "All time"
}

fun monthName(month: YearMonth): String =
    month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year

/**
 * The months the dashboard offers, newest first: every one from the earliest expense to now, gaps
 * included so the list reads as a calendar. The current month is always there, even when empty.
 */
fun pickableMonths(
    expenses: List<ExpenseDetails>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone)
): List<YearMonth> {
    val current = YearMonth.from(today)
    val earliest = expenses.minOfOrNull { it.expense.paidAt }
        ?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(zone)) }
        ?.takeIf { it < current }
        ?: current
    return generateSequence(current) { it.minusMonths(1) }.takeWhile { it >= earliest }.toList()
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
    /** My unsettled shares of bills other people paid for me in the period. */
    val iOwePaise: Long,
    val expenseCount: Int,
    val dailyAveragePaise: Long,
    val largest: ExpenseDetails?,
    /** Always the trailing 12 months, so a short period still has context. */
    val monthly: List<MonthPoint>,
    val categories: List<CategorySlice>,
    val people: List<PersonAnalytics>,
    val weekdays: List<WeekdayPoint>,
    /**
     * My spend in the month in view against the month before it, as a fraction. For the current
     * month only the same days of last month are compared. Null when there is nothing to compare.
     */
    val monthOverMonth: Float?,
    /** The month [monthOverMonth] measures: the selected month, or the current one otherwise. */
    val comparedMonth: YearMonth,
    /** Whether [comparedMonth] is still in progress, so the comparison is month to date. */
    val comparedMonthToDate: Boolean
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
        val iOwePaise = inPeriod.sumOf { it.iOwePaise }
        val current = YearMonth.from(today)
        val compared = (period as? Period.Month)?.month ?: current

        return AnalyticsReport(
            period = period,
            minePaise = minePaise,
            onOthersPaise = onOthersPaise,
            settledPaise = settledPaise,
            outstandingPaise = outstandingPaise,
            iOwePaise = iOwePaise,
            expenseCount = inPeriod.size,
            dailyAveragePaise = dailyAverage(minePaise, inPeriod, period, zone, today),
            largest = inPeriod.maxByOrNull { it.expense.amountPaise },
            monthly = monthlyTrend(expenses, zone, today),
            categories = categorySlices(inPeriod),
            people = peopleAnalytics(inPeriod, people),
            weekdays = weekdayPattern(inPeriod, zone),
            monthOverMonth = monthOverMonth(expenses, zone, today, compared),
            comparedMonth = compared,
            comparedMonthToDate = compared == current
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
        // What each person costs me only arises on bills I paid. On one they paid, their share is
        // their own spending, not something I fronted.
        val shares = rows.filter { it.expense.paidByMe }.flatMap { details ->
            details.liveSplits.filter { it.personId != null }.map { details to it }
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

    /**
     * The month in view against the month before it. For the current month that is month to date
     * against the same stretch of last month: setting a partial month against a whole one would
     * show a steep fall every morning of the 3rd. On the last day of the month the whole of last
     * month is used, so a 30-day month is not denied the 31st it is being compared to. A month
     * already over is set whole against the whole of the one before.
     */
    private fun monthOverMonth(
        all: List<ExpenseDetails>,
        zone: ZoneId,
        today: LocalDate,
        month: YearMonth
    ): Float? {
        val current = month
        val previous = current.minusMonths(1)
        val inProgress = current == YearMonth.from(today)
        val throughDay = if (inProgress) today.dayOfMonth else current.lengthOfMonth()
        val cutoff = if (throughDay == current.lengthOfMonth()) previous.lengthOfMonth()
        else minOf(throughDay, previous.lengthOfMonth())
        fun spent(month: YearMonth, lastDay: Int) = all.filter { details ->
            val date = details.expense.paidAt.toLocalDate(zone)
            YearMonth.from(date) == month && date.dayOfMonth <= lastDay
        }.sumOf { it.minePaise() }
        val before = spent(previous, cutoff)
        if (before == 0L) return null
        val now = spent(current, throughDay)
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
            is Period.Month ->
                if (period.month == YearMonth.from(today)) today.dayOfMonth.toLong()
                else period.month.lengthOfMonth().toLong()
            Period.Year -> today.dayOfYear.toLong()
            // Span from the first recorded expense, so an old history is not flattered by a
            // denominator that only counts this year.
            Period.All -> {
                val first = rows.minOf { it.expense.paidAt }.toLocalDate(zone)
                java.time.temporal.ChronoUnit.DAYS.between(first, today) + 1
            }
        }.coerceAtLeast(1)
        return minePaise / days
    }

    private fun Period.contains(millis: Long, zone: ZoneId, today: LocalDate): Boolean {
        val date = millis.toLocalDate(zone)
        return when (this) {
            is Period.Month -> YearMonth.from(date) == month
            Period.Year -> date.year == today.year
            Period.All -> true
        }
    }

    private fun Long.toLocalDate(zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
}

/**
 * The splits still in force. Editing an expense soft-deletes its old splits so the removal can
 * sync, which leaves them attached to the expense; counting them would bill people twice.
 */
private val ExpenseDetails.liveSplits: List<ExpenseSplit>
    get() = splits.filter { it.deletedAt == null }

/**
 * My own share: what is left after everyone else's shares are taken out. On a bill somebody else
 * paid it is still my spending -- I owe it to them rather than having paid it out -- so it counts
 * the same way either way.
 */
fun ExpenseDetails.minePaise(): Long {
    val live = liveSplits
    return if (live.isEmpty()) expense.amountPaise
    else live.filter { it.personId == null }.sumOf { it.amountPaise }
}

/** What I fronted for other people. Nothing, on a bill somebody else paid. */
fun ExpenseDetails.othersPaise(): Long =
    if (!expense.paidByMe) 0L
    else liveSplits.filter { it.personId != null }.sumOf { it.amountPaise }

fun ExpenseDetails.settledPaise(): Long =
    if (!expense.paidByMe) 0L
    else liveSplits.filter { it.personId != null && it.settledAt != null }.sumOf { it.amountPaise }
