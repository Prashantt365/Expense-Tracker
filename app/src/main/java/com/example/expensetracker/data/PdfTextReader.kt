package com.example.expensetracker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the text out of a PDF statement.
 *
 * PdfRenderer only rasterises pages, it does not expose their text, so each page is rendered and
 * put through the same on-device OCR the receipt flow uses. That keeps the work offline and adds
 * no dependency, at the cost of reading the page as an image.
 */
class PdfTextReader(private val context: Context) {

    /** Roughly 200dpi for A4, which is enough for OCR without producing enormous bitmaps. */
    private val targetWidthPx = 1654

    suspend fun readText(uri: Uri, onProgress: (page: Int, total: Int) -> Unit = { _, _ -> }): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                // PdfRenderer needs a seekable descriptor; a provider stream is not guaranteed to
                // be one, so the document is copied into the cache first.
                val working = File(context.cacheDir, "import-${System.currentTimeMillis()}.pdf")
                try {
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "cannot open $uri" }
                        working.outputStream().use(input::copyTo)
                    }
                    readPages(working, onProgress)
                } finally {
                    working.delete()
                }
            }
        }

    private suspend fun readPages(file: File, onProgress: (Int, Int) -> Unit): String {
        val text = StringBuilder()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                for (index in 0 until renderer.pageCount) {
                    onProgress(index + 1, renderer.pageCount)
                    val bitmap = renderPage(renderer, index)
                    try {
                        text.appendLine(recognise(bitmap))
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
        return text.toString()
    }

    private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap =
        renderer.openPage(index).use { page ->
            val scale = (targetWidthPx.toFloat() / page.width).coerceIn(1f, 4f)
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt(),
                (page.height * scale).toInt(),
                Bitmap.Config.ARGB_8888
            )
            // Pages render with transparency where the paper is; OCR needs it opaque and white.
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }

    private suspend fun recognise(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(inReadingOrder(it)) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    }

    /**
     * Rebuilds the page as visual rows rather than taking [Text.getText] as it comes.
     *
     * ML Kit groups text into blocks by proximity, so a statement laid out in columns can come
     * back as one block per column: every date, then every description, then every amount. That
     * destroys the association between them. Grouping recognised lines by their vertical position
     * and ordering each group left to right restores the row a reader actually sees, which is what
     * the statement parser needs.
     */
    private fun inReadingOrder(text: Text): String {
        val lines = text.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line -> line.boundingBox?.let { it to line.text } }
        if (lines.isEmpty()) return text.text

        val rows = mutableListOf<MutableList<Pair<Rect, String>>>()
        lines.sortedBy { (box, _) -> box.centerY() }.forEach { entry ->
            val (box, _) = entry
            val open = rows.lastOrNull()
            val anchor = open?.first()?.first
            // Two fragments belong to the same row when their centres sit within roughly half a
            // line height of each other, which tolerates the baseline drift OCR introduces.
            val tolerance = (box.height() * 0.6f).toInt().coerceAtLeast(6)
            if (open != null && anchor != null && kotlin.math.abs(anchor.centerY() - box.centerY()) <= tolerance) {
                open += entry
            } else {
                rows += mutableListOf(entry)
            }
        }

        return rows.joinToString("\n") { row ->
            row.sortedBy { (box, _) -> box.left }.joinToString("  ") { (_, value) -> value }
        }
    }
}
