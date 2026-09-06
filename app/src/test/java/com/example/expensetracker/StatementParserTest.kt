package com.example.expensetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StatementParserTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val today: LocalDate = LocalDate.of(2026, 9, 15)

    private fun parse(text: String) = StatementParser.parse(text, zone, today)

    private fun dateOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    @Test fun `reads a bank statement row with a trailing balance`() {
        val rows = parse("05/09/2026  UPI/SWIGGY/432109876  450.00  12,340.50")
        val row = rows.single()
        assertEquals(45000, row.amountPaise)
        assertEquals(LocalDate.of(2026, 9, 5), dateOf(row.date!!))
        assertTrue(row.description.contains("SWIGGY"))
        assertFalse(row.isCredit)
    }

    @Test fun `the trailing balance is never imported as the amount`() {
        // Without dropping the last figure this would import 12,340.50 rather than 450.00.
        assertEquals(45000, parse("05/09/2026 UPI/SWIGGY 450.00 12,340.50").single().amountPaise)
    }

    @Test fun `reads several date formats`() {
        val rows = parse(
            """
            05/09/2026 UPI/SWIGGY/1 450.00
            05-09-2026 UPI/ZOMATO/2 450.00
            5 Sep 2026 UPI/OLA/3 450.00
            05-Sep-2026 UPI/UBER/4 450.00
            """.trimIndent()
        )
        assertEquals(4, rows.size)
        rows.forEach { assertEquals(LocalDate.of(2026, 9, 5), dateOf(it.date!!)) }
    }

    @Test fun `marks credits so they are not imported as spending`() {
        val rows = parse(
            """
            05/09/2026 SALARY CREDIT 50,000.00 62,340.50
            06/09/2026 UPI/SWIGGY/9 450.00 61,890.50
            07/09/2026 NEFT REFUND AMAZON 1,200.00 63,090.50
            08/09/2026 UPI/BLUETOKAI/2 385.50 Dr 62,705.00
            """.trimIndent()
        )
        assertEquals(4, rows.size)
        assertTrue(rows[0].isCredit)
        assertFalse(rows[1].isCredit)
        assertTrue(rows[2].isCredit)
        assertFalse(rows[3].isCredit)
    }

    @Test fun `a Cr suffix marks a credit even without a keyword`() {
        assertTrue(parse("05/09/2026 IMPS INWARD 900.00 Cr 12,000.00").single().isCredit)
    }

    @Test fun `skips headers totals and page furniture`() {
        val rows = parse(
            """
            Statement of account for 01/09/2026 to 30/09/2026
            Account Number 1234567890
            Date        Particulars        Debit      Balance
            Opening Balance                           10,000.00
            05/09/2026  UPI/SWIGGY/1       450.00     9,550.00
            Total                          450.00
            Closing Balance                           9,550.00
            Page 1 of 3
            """.trimIndent()
        )
        assertEquals(1, rows.size)
        assertTrue(rows.single().description.contains("SWIGGY"))
    }

    @Test fun `keeps a row whose date cannot be read rather than dropping it`() {
        // The user can still fix the date in review; silently losing the row would be worse.
        val row = parse("UPI/SWIGGY/432109876 450.00").single()
        assertNull(row.date)
        assertEquals(45000, row.amountPaise)
    }

    @Test fun `refuses a date in the future as a misread`() {
        assertNull(parse("05/09/2099 UPI/SWIGGY/1 450.00").single().date)
    }

    @Test fun `ignores lines with no money on them`() {
        assertTrue(parse("Thank you for banking with us").isEmpty())
        assertTrue(parse("").isEmpty())
    }

    @Test fun `ignores a figure with no description to identify it`() {
        assertTrue(parse("05/09/2026   450.00   9,550.00").isEmpty())
    }

    @Test fun `reads a rupee symbol and comma grouping`() {
        val row = parse("05/09/2026 AMAZON PAY ORDER ₹1,24,500.00").single()
        assertEquals(12450000, row.amountPaise)
    }

    @Test fun `strips the date and figures out of the description`() {
        val row = parse("05/09/2026 | UPI/BLUE TOKAI COFFEE/9876 | 385.50 | 12,340.50").single()
        assertFalse(row.description.contains("385.50"))
        assertFalse(row.description.contains("05/09/2026"))
        assertTrue(row.description.contains("BLUE TOKAI COFFEE"))
    }

    @Test fun `keeps the raw line so an odd row can still be checked by hand`() {
        val line = "05/09/2026 UPI/SWIGGY/1 450.00 9,550.00"
        assertEquals(line, parse(line).single().rawLine)
    }

    @Test fun `parses a realistic multi row statement`() {
        val rows = parse(
            """
            Date        Narration                        Withdrawal   Balance
            01/09/2026  UPI/BLUETOKAI/885/Coffee            385.50   14,614.50
            03/09/2026  UPI/AMAZON/1123/Order             2,600.00   12,014.50
            05/09/2026  SALARY CREDIT SEP                 50,000.00  62,014.50
            06/09/2026  UPI/OLA/7781/Ride                   238.00   61,776.50
            """.trimIndent()
        )
        assertEquals(4, rows.size)
        assertEquals(listOf(38550L, 260000L, 5000000L, 23800L), rows.map { it.amountPaise })
        assertEquals(listOf(false, false, true, false), rows.map { it.isCredit })
        assertNotNull(rows.first().date)
    }
}
