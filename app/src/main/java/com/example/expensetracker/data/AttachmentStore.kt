package com.example.expensetracker.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Copies picked or shared images into app-private storage.
 *
 * A share grant lapses with the activity that received it, so the incoming Uri is unreadable by
 * the time the expense is opened again. Only the copy under filesDir survives.
 */
class AttachmentStore(private val context: Context) {

    private val dir = File(context.filesDir, "attachments")

    suspend fun copyIn(source: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val target = File(dir, "${UUID.randomUUID()}.jpg")
            context.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "no stream for $source" }
                target.outputStream().use(input::copyTo)
            }
            target.absolutePath
        }.getOrNull()
    }

    suspend fun delete(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.forEach { runCatching { File(it).delete() } }
    }
}
