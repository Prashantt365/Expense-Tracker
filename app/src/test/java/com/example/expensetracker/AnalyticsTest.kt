package com.example.expensetracker

import com.example.expensetracker.data.Expense
import com.example.expensetracker.data.ExpenseDetails
import com.example.expensetracker.data.ExpenseSplit
import com.example.expensetracker.data.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AnalyticsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val today: LocalDate = LocalDate.of(2026, 9, 15)

    private fun millis(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun details(
        id: Long,
        amountPaise: Long,
        category: String = "Food",
        date: LocalDate = today,
        merchant: String = "Shop",
        splits: List<ExpenseSplit> = emptyList()
    ) = ExpenseDetails(
        expense = Expense(
            id = id,
            amountPaise = amountPaise,
            category = category,
            note = "",
            merchant = merchant,
            paidAt = millis(date)
        ),
        splits = splits.map { it.copy(expenseId = id) },
        attachments = emptyList()
    )

    private val rahul = Person(id = 1, name = "Rahul")
    private val priya = Person(id = 2, name = "Priya")

    private fun report(rows: List<ExpenseDetails>, period: Period = Period.MONTH) =
        Analytics.build(rows, listOf(rahul, priya), period, zone, today)

    @Test fun `my spend excludes what other people owe`() {
        val rows = listOf(
            details(
                1, 60000,
                splits = listOf(
                    ExpenseSplit(expenseId = 1, personId = 1, amountPaise = 20000),
                    ExpenseSplit(expenseId = 1, personId = null, amountPaise = 40000)
                )
            )
        )
        val result = report(rows)
        assertEquals(40000, result.minePaise)
        assertEquals(20000, result.onOthersPaise)
        assertEquals(60000, result.grossPaise)
    }

    @Test fun `effective cost of other people drops as they settle`() {
        val rows = listOf(
            details(
                1, 50000,
                splits = listOf(
                    ExpenseSplit(expenseId = 1, personId = 1, amountPaise = 20000, settledAt = millis(today)),
                    ExpenseSplit(expenseId = 1, personId = 2, amountPaise = 10000),
                    ExpenseSplit(expenseId = 1, personId = null, amountPaise = 20000)
                )
            )
        )
        val result = report(rows)
        assertEquals(30000, result.onOthersPaise)
        assertEquals(20000, result.settledPaise)
        // Only Priya's unsettled share is still costing me anything.
        assertEquals(10000, result.outstandingPaise)
    }

    @Test fun `per person figures separate what came back from what is still carried`() {
        val rows = listOf(
            details(
                1, 50000,
                splits = listOf(
                    ExpenseSplit(expenseId = 1, personId = 1, amountPaise = 20000, settledAt = millis(today)),
                    ExpenseSplit(expenseId = 1, personId = null, amountPaise = 30000)
                )
            ),
            details(
                2, 30000, date = today.minusDays(2),
                splits = listOf(
                    ExpenseSplit(expenseId = 2, personId = 1, amountPaise = 15000),
                    ExpenseSplit(expenseId = 2, personId = null, amountPaise = 15000)
                )
            )
        )
        val person = report(rows).people.single { it.personId == 1L }
        assertEquals(35000, person.sharedPaise)
        assertEquals(20000, person.settledPaise)
        assertEquals(15000, person.outstandingPaise)
        assertEquals(2, person.expenseCount)
        assertEquals(0.571f, person.recoveredFraction, 0.01f)
    }

    @Test fun `people are ranked by what they still owe`() {
        val rows = listOf(
            details(
                1, 100000,
                splits = listOf(
                    ExpenseSplit(expenseId = 1, personId = 1, amountPaise = 10000),
                    ExpenseSplit(expenseId = 1, personId = 2, amountPaise = 40000),
                    ExpenseSplit(expenseId = 1, personId = null, amountPaise = 50000)
                )
            )
        )
        assertEquals(listOf("Priya", "Rahul"), report(rows).people.map { it.name })
    }

    @Test fun `categories are built from my own share only`() {
        val rows = listOf(
            details(
                1, 100000, category = "Food",
                splits = listOf(
                    ExpenseSplit(expenseId = 1, personId = 1, amountPaise = 90000),
                    ExpenseSplit(expenseId = 1, personId = null, amountPaise = 10000)
                )
            ),
            details(
                2, 20000, category = "Transport",
                splits = listOf(ExpenseSplit(expenseId = 2, personId = null, amountPaise = 20000))
            )
        )
        val categories = report(rows).categories
        // Transport leads despite the smaller bill, because almost all the Food went on someone else.
        assertEquals("Transport", categories.first().category)
        assertEquals(20000, categories.first().paise)
        assertEquals(10000, categories.first { it.category == "Food" }.paise)
        assertEquals(1f, categories.sumOf { it.fraction.toDouble() }.toFloat(), 0.001f)
    }

    @Test fun `the period filters what is counted`() {
        val rows = listOf(
            details(1, 10000, date = today),
            details(2, 20000, date = today.minusMonths(2)),
            details(3, 40000, date = today.minusYears(1))
        )
        assertEquals(10000, report(rows, Period.MONTH).minePaise)
        assertEquals(30000, report(rows, Period.YEAR).minePaise)
        assertEquals(70000, report(rows, Period.ALL).minePaise)
    }

    @Test fun `the trend always spans twelve months and keeps empty ones`() {
        val rows = listOf(details(1, 10000, date = today), details(2, 20000, date = today.minusMonths(3)))
        val monthly = report(rows).monthly
        assertEquals(12, monthly.size)
        assertEquals(10000, monthly.last().minePaise)
        assertEquals(20000, monthly[monthly.size - 4].minePaise)
        assertTrue("empty months must still be plotted", monthly.any { it.totalPaise == 0L })
    }

    @Test fun `month over month compares my spend with the previous month`() {
        val rows = listOf(
            details(1, 15000, date = today),
            details(2, 10000, date = today.minusMonths(1))
        )
        assertEquals(0.5f, report(rows).monthOverMonth!!, 0.001f)
    }

    @Test fun `month over month is absent when there is nothing to compare against`() {
        assertNull(report(listOf(details(1, 15000, date = today))).monthOverMonth)
    }

    @Test fun `the weekday pattern has a bucket per day and uses my share`() {
        val monday = LocalDate.of(2026, 9, 14)
        val rows = listOf(
            details(
                1, 50000, date = monday,
                splits = listOf(
                    ExpenseSplit(expenseId = 1, personId = 1, amountPaise = 20000),
                    ExpenseSplit(expenseId = 1, personId = null, amountPaise = 30000)
                )
            )
        )
        val weekdays = report(rows).weekdays
        assertEquals(7, weekdays.size)
        assertEquals(30000, weekdays.first().paise)
        assertEquals(30000, weekdays.sumOf { it.paise })
    }

    @Test fun `an expense with no splits counts entirely as mine`() {
        val result = report(listOf(details(1, 25000)))
        assertEquals(25000, result.minePaise)
        assertEquals(0, result.onOthersPaise)
    }

    @Test fun `the daily average divides my spend by the days elapsed`() {
        // 15th of the month, so 3000.00 over 15 days is 200.00 a day.
        assertEquals(20000, report(listOf(details(1, 300000))).dailyAveragePaise)
    }

    @Test fun `an empty history reports no data rather than failing`() {
        val result = report(emptyList())
        assertTrue(!result.hasData)
        assertEquals(0, result.minePaise)
        assertEquals(12, result.monthly.size)
        assertNull(result.largest)
        assertTrue(result.categories.isEmpty())
    }

    @Test fun `a person deleted after settling still shows on past expenses`() {
        val rows = listOf(
            details(
                1, 30000,
                splits = listOf(
                    ExpenseSplit(expenseId = 1, personId = 99, amountPaise = 10000),
                    ExpenseSplit(expenseId = 1, personId = null, amountPaise = 20000)
                )
            )
        )
        assertEquals("Removed person", report(rows).people.single().name)
    }
}
