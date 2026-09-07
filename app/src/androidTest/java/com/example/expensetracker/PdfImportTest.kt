package com.example.expensetracker

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.expensetracker.data.PdfTextReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises the whole import path on a real document: PdfRenderer rasterises it, ML Kit reads the
 * pixels back, and the statement parser has to recover the rows from that OCR output rather than
 * from clean text.
 */
@RunWith(AndroidJUnit4::class)
class PdfImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun writeStatement(lines: List<String>): File {
        val document = PdfDocument()
        // A4 at 72dpi, which is what PdfRenderer treats as the natural page size.
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val paint = Paint().apply {
            textSize = 11f
            isAntiAlias = true
        }
        var y = 60f
        lines.forEach { line ->
            page.canvas.drawText(line, 40f, y, paint)
            y += 22f
        }
        document.finishPage(page)

        val file = File(context.cacheDir, "statement-test.pdf")
        file.outputStream().use(document::writeTo)
        document.close()
        return file
    }

    @Test fun readsTransactionsOutOfAGeneratedStatement() = runBlocking {
        val file = writeStatement(
            listOf(
                "Account Statement 01/09/2026 to 30/09/2026",
                "Date Narration Withdrawal Balance",
                "01/09/2026 UPI BLUETOKAI COFFEE 385.50 14,614.50",
                "03/09/2026 UPI AMAZON ORDER 2,600.00 12,014.50",
                "06/09/2026 UPI OLA RIDE 238.00 11,776.50"
            )
        )

        try {
            val text = PdfTextReader(context).readText(file.toUri()).getOrThrow()
            assertTrue("OCR returned nothing at all", text.isNotBlank())

            val rows = StatementParser.parse(text)
            assertTrue("expected at least 3 rows, got ${rows.size} from:\n$text", rows.size >= 3)

            // OCR of a rendered page is imperfect, so match on the amounts, which are the part
            // that has to be exact for an import to be worth anything.
            val amounts = rows.map { it.amountPaise }
            listOf(38550L, 260000L, 23800L).forEach { expected ->
                assertTrue("missing $expected in $amounts (text was:\n$text)", expected in amounts)
            }
            assertTrue("the running balance was imported as an amount", 1461450L !in amounts)
        } finally {
            file.delete()
        }
    }

    /** One transaction as Google Pay lays it out: three columns, two rows of text per entry. */
    private class GooglePayEntry(val date: String, val time: String, val payee: String, val reference: String, val amount: String)

    private fun writeGooglePayStatement(entries: List<GooglePayEntry>, pages: Int = 1): File {
        val document = PdfDocument()
        val heading = Paint().apply { textSize = 12f; isAntiAlias = true; isFakeBoldText = true }
        val body = Paint().apply { textSize = 11f; isAntiAlias = true }
        val small = Paint().apply { textSize = 9f; isAntiAlias = true }

        repeat(pages) { pageIndex ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageIndex + 1).create())
            val canvas = page.canvas
            canvas.drawText("Google Pay", 40f, 50f, heading)
            canvas.drawText("Transaction statement", 380f, 50f, heading)
            canvas.drawText("8623002665, garjepg@gmail.com", 340f, 66f, small)
            canvas.drawText("Transaction statement period", 40f, 110f, small)
            canvas.drawText("Sent", 300f, 110f, small)
            canvas.drawText("Received", 450f, 110f, small)
            canvas.drawText("01 August 2026 - 31 August 2026", 40f, 128f, body)
            canvas.drawText("46,821.76", 300f, 128f, body)
            canvas.drawText("1,339.33", 450f, 128f, body)
            canvas.drawText("Date & time", 40f, 175f, small)
            canvas.drawText("Transaction details", 180f, 175f, small)
            canvas.drawText("Amount", 480f, 175f, small)

            var y = 215f
            entries.forEach { entry ->
                // Date, payee and amount share a baseline but sit in separate columns, which is
                // exactly the arrangement that defeats reading ML Kit's blocks in order.
                canvas.drawText(entry.date, 40f, y, body)
                canvas.drawText(entry.payee, 180f, y, heading)
                canvas.drawText(entry.amount, 480f, y, body)
                canvas.drawText(entry.time, 40f, y + 20f, small)
                canvas.drawText(entry.reference, 180f, y + 20f, small)
                canvas.drawText("Paid by Union Bank of India 6254", 180f, y + 38f, small)
                y += 78f
            }
            document.finishPage(page)
        }

        val file = File(context.cacheDir, "gpay-statement-test.pdf")
        file.outputStream().use(document::writeTo)
        document.close()
        return file
    }

    @Test fun readsAGooglePayStatementLaidOutInColumns() = runBlocking {
        val file = writeGooglePayStatement(
            listOf(
                GooglePayEntry("03 Aug, 2026", "12:14 PM", "Paid to Rapido", "UPI Transaction ID: 127297424577", "80.00"),
                GooglePayEntry("03 Aug, 2026", "02:56 PM", "Paid to KOLHAPURI MISAL", "UPI Transaction ID: 127306481309", "245.50"),
                GooglePayEntry("03 Aug, 2026", "08:09 PM", "Paid to Sonu gupta", "UPI Transaction ID: 127326194873", "66.00")
            )
        )
        try {
            val text = PdfTextReader(context).readText(file.toUri()).getOrThrow()
            val rows = StatementParser.parse(text)

            val amounts = rows.map { it.amountPaise }
            listOf(8000L, 24550L, 6600L).forEach { expected ->
                assertTrue("missing $expected in $amounts. Page read as:\n$text", expected in amounts)
            }
            // The summary tiles must never be imported as transactions.
            assertTrue("the Sent summary was imported", 4682176L !in amounts)
            assertTrue("the Received summary was imported", 133933L !in amounts)
            // Nor the UPI reference or the account tail.
            assertTrue("a reference number became an amount", amounts.none { it > 100000L })

            val descriptions = rows.joinToString(" | ") { it.description }
            assertTrue("payee lost, got: $descriptions", descriptions.contains("Rapido", ignoreCase = true))
        } finally {
            file.delete()
        }
    }

    @Test fun readsEveryPageOfAMultiPageStatement() = runBlocking {
        val file = writeGooglePayStatement(
            listOf(
                GooglePayEntry("03 Aug, 2026", "12:14 PM", "Paid to Rapido", "UPI Transaction ID: 127297424577", "80.00")
            ),
            pages = 3
        )
        try {
            val text = PdfTextReader(context).readText(file.toUri()).getOrThrow()
            val rows = StatementParser.parse(text)
            // The same entry on each of three pages: all three have to survive the repeated headers.
            assertEquals(3, rows.count { it.amountPaise == 8000L })
        } finally {
            file.delete()
        }
    }

    @Test fun reportsFailureForSomethingThatIsNotAPdf() = runBlocking {
        val file = File(context.cacheDir, "not-a-statement.pdf")
        file.writeText("this is plainly not a pdf")
        try {
            assertTrue(PdfTextReader(context).readText(file.toUri()).isFailure)
        } finally {
            file.delete()
        }
    }

    @Test fun reportsEveryPageWhileReading() = runBlocking {
        val file = writeStatement(listOf("01/09/2026 UPI BLUETOKAI 385.50 14,614.50"))
        try {
            val pages = mutableListOf<Pair<Int, Int>>()
            PdfTextReader(context).readText(file.toUri()) { page, total -> pages += page to total }
            assertEquals(listOf(1 to 1), pages)
        } finally {
            file.delete()
        }
    }
}
