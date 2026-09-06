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
