package com.example.expensetracker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
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
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it.text) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    }
}
