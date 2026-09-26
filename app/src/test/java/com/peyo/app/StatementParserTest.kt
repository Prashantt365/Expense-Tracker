package com.peyo.app

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

    // --- Google Pay transaction statement ---------------------------------------------------
    //
    // Laid out as a table: Date & time | Transaction details | Amount. Once PdfTextReader has
    // rebuilt the page into visual rows, each transaction arrives as a date/payee/amount row
    // followed by its time and reference lines.

    private val googlePayPage = """
        Google Pay                                    Transaction statement
                                                      8623002665, garjepg@gmail.com
        Transaction statement period      Sent            Received
        01 August 2026 - 31 August 2026   ₹46,821.76      ₹1,339.33
        Date & time    Transaction details    Amount
        03 Aug, 2026   Paid to Rapido    ₹80
        12:14 PM   UPI Transaction ID: 127297424577
        Paid by Union Bank of India 6254
        03 Aug, 2026   Paid to KOLHAPURI MISAL CENTRE    ₹80
        02:56 PM   UPI Transaction ID: 127306481309
        Paid by Union Bank of India 6254
        03 Aug, 2026   Paid to Sonu gupta    ₹66
        08:09 PM   UPI Transaction ID: 127326194873
        Paid by Union Bank of India 6254
    """.trimIndent()

    @Test fun `reads a Google Pay transaction statement`() {
        val rows = parse(googlePayPage)
        assertEquals(3, rows.size)
        assertEquals(listOf(8000L, 8000L, 6600L), rows.map { it.amountPaise })
        assertEquals(
            listOf("Rapido", "KOLHAPURI MISAL CENTRE", "Sonu gupta"),
            rows.map { it.description }
        )
        assertTrue("none of these are money in", rows.none { it.isCredit })
    }

    @Test fun `reads the Google Pay date format with a comma`() {
        val row = parse(googlePayPage).first()
        assertEquals(LocalDate.of(2026, 8, 3), dateOf(row.date!!))
    }

    @Test fun `keeps the time of day so imported rows order correctly`() {
        val rows = parse(googlePayPage)
        val times = rows.map { Instant.ofEpochMilli(it.date!!).atZone(zone).toLocalTime() }
        assertEquals(java.time.LocalTime.of(12, 14), times[0])
        assertEquals(java.time.LocalTime.of(14, 56), times[1])
        assertEquals(java.time.LocalTime.of(20, 9), times[2])
    }

    @Test fun `ignores the Google Pay summary tiles and statement period`() {
        // The period row starts with a date and carries two large figures, so without the range
        // and summary rules it would import 46,821.76 as a transaction.
        val amounts = parse(googlePayPage).map { it.amountPaise }
        assertFalse(4682176L in amounts)
        assertFalse(133933L in amounts)
    }

    @Test fun `does not mistake a UPI reference or a bank tail for the amount`() {
        val amounts = parse(googlePayPage).map { it.amountPaise }
        // 127297424577 as a reference, 6254 as the account tail.
        assertTrue(amounts.all { it in listOf(8000L, 6600L) })
    }

    @Test fun `reads money received as a credit`() {
        val rows = parse(
            """
            Date & time    Transaction details    Amount
            04 Aug, 2026   Received from Sonu gupta    ₹500
            09:15 AM   UPI Transaction ID: 127326194999
            Paid by Union Bank of India 6254
            """.trimIndent()
        )
        assertEquals(50000, rows.single().amountPaise)
        assertEquals("Sonu gupta", rows.single().description)
        assertTrue(rows.single().isCredit)
    }

    @Test fun `a payee whose name contains a credit word is still a payment`() {
        val row = parse("05 Aug, 2026   Paid to Credit Union Store    ₹250").single()
        assertFalse(row.isCredit)
    }

    @Test fun `carries on across page breaks and repeated headers`() {
        val rows = parse(
            googlePayPage + "\n" + """
            Page 2 of 3
            Google Pay                                    Transaction statement
            Date & time    Transaction details    Amount
            04 Aug, 2026   Paid to Swiggy    ₹432.50
            07:41 PM   UPI Transaction ID: 127400000001
            Paid by Union Bank of India 6254
            05 Aug, 2026   Paid to Amazon    ₹1,299
            11:02 AM   UPI Transaction ID: 127400000002
            Paid by Union Bank of India 6254
            """.trimIndent()
        )
        assertEquals(5, rows.size)
        assertEquals(listOf(8000L, 8000L, 6600L, 43250L, 129900L), rows.map { it.amountPaise })
        assertEquals("Amazon", rows.last().description)
    }

    @Test fun `a record does not swallow the transaction after it`() {
        // Two payments to the same payee on the same day must stay separate rows.
        val rows = parse(
            """
            03 Aug, 2026   Paid to Rapido    ₹80
            12:14 PM   UPI Transaction ID: 127297424577
            03 Aug, 2026   Paid to Rapido    ₹95
            06:40 PM   UPI Transaction ID: 127297424999
            """.trimIndent()
        )
        assertEquals(listOf(8000L, 9500L), rows.map { it.amountPaise })
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

    @Test fun `a dotted date is never read as the amount`() {
        // "05.09" looks like two decimal places; imported, this row would be ₹5.09.
        val row = parse("05.09.2026 SWIGGY 450.00 12,340.50").single()
        assertEquals(45000, row.amountPaise)
        assertEquals(LocalDate.of(2026, 9, 5), dateOf(row.date!!))
    }

    @Test fun `a dotted value date inside the row is not an amount either`() {
        assertEquals(45000, parse("05/09/2026 05.09.2026 SWIGGY 450.00 12,340.50").single().amountPaise)
    }

    @Test fun `rs inside a word is not a currency marker`() {
        // "Cars24" once tagged ₹24 and hid the real ₹5,000.
        assertEquals(500000, parse("05 Aug, 2026   Paid to Cars24    ₹5,000").single().amountPaise)
    }

    @Test fun `a payee starting with CR is not a credit suffix`() {
        val row = parse("05/09/2026 UPI 450.00 CROMA RETAIL 12,340.50").single()
        assertEquals(45000, row.amountPaise)
        assertFalse(row.isCredit)
    }

    @Test fun `reads Sept as September`() {
        val rows = parse(
            """
            12 Sept 2026 UPI/SWIGGY/1 450.00
            12 Sept. 2026 UPI/ZOMATO/2 450.00
            """.trimIndent()
        )
        rows.forEach { assertEquals(LocalDate.of(2026, 9, 12), dateOf(it.date!!)) }
        assertEquals(2, rows.size)
    }
}
