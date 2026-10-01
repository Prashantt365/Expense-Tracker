package com.peyo.app.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Where the camera app writes a photo of a bill.
 *
 * The camera is another app, so it cannot write into this one's storage by path; it is handed a
 * content Uri from the FileProvider declared in the manifest instead. The file lives in the cache:
 * saving the expense copies it into attachments like any picked image, and a photo taken for an
 * expense that is then abandoned is left for the system to clear.
 */
object CameraFiles {

    fun newPhotoUri(context: Context): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "bill-${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(context, authority(context), file)
    }

    fun authority(context: Context): String = context.packageName + ".files"
}
