package com.example.expensetracker.data

import android.content.Context
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads display names out of the phone book.
 *
 * Only the name is taken. Nothing is written back, nothing leaves the device, and the read happens
 * solely when the user asks for it from the people settings.
 */
object ContactsReader {

    suspend fun readNames(context: Context): List<String> = withContext(Dispatchers.IO) {
        val names = mutableListOf<String>()
        val projection = arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
            )?.use { cursor ->
                val column = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                if (column < 0) return@use
                while (cursor.moveToNext()) {
                    cursor.getString(column)?.trim()?.takeIf { it.isNotEmpty() }?.let(names::add)
                }
            }
        }
        names
    }
}
