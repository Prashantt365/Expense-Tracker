package com.example.expensetracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** An attachment already stored on disk, or one just picked and not yet copied in. */
sealed interface AttachmentPreview {
    data class Stored(val id: Long, val path: String) : AttachmentPreview
    data class Picked(val uri: Uri) : AttachmentPreview
}

/**
 * Decodes a downsampled preview off the main thread. Receipt screenshots are full-resolution, so
 * decoding one at native size for a 72dp thumbnail is a quick route to an OutOfMemoryError.
 */
@Composable
fun rememberThumbnail(preview: AttachmentPreview, maxPx: Int = 512): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(preview, maxPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(preview, maxPx) {
        bitmap = withContext(Dispatchers.IO) { decode(context, preview, maxPx)?.asImageBitmap() }
    }
    return bitmap
}

private fun decode(context: Context, preview: AttachmentPreview, maxPx: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream(context, preview).use { BitmapFactory.decodeStream(it, null, bounds) }

    var sample = 1
    while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }

    openStream(context, preview).use { BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()

private fun openStream(context: Context, preview: AttachmentPreview) = when (preview) {
    is AttachmentPreview.Stored -> File(preview.path).inputStream()
    is AttachmentPreview.Picked -> context.contentResolver.openInputStream(preview.uri)
        ?: error("no stream for ${preview.uri}")
}
