package com.peyo.app.data

import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.peyo.app.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A phone book entry: the name, and the number to file them under if one is usable. */
data class PhoneContact(val name: String, val phone: String? = null)

/**
 * Reads names, and a mobile number for each, out of the phone book.
 *
 * Nothing is written back, nothing leaves the device unless the user ticks it, and the read
 * happens solely when the user asks for it from the people settings.
 */
object ContactsReader {

    suspend fun readContacts(context: Context): List<PhoneContact> = withContext(Dispatchers.IO) {
        val names = linkedMapOf<Long, String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
            )?.use { cursor ->
                val id = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val name = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                if (id < 0 || name < 0) return@use
                while (cursor.moveToNext()) {
                    val display = cursor.getString(name)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    names[cursor.getLong(id)] = display
                }
            }
        }

        val numbers = bestNumbers(context)
        names.map { (id, name) -> PhoneContact(name, numbers[id]) }
    }

    /**
     * One number per contact: the first that reads as a mobile number, preferring the one marked
     * primary, then one saved as a mobile, then any other. A contact saved with a landline and a
     * mobile is filed under the mobile, because that is the number an account would be made with.
     */
    private fun bestNumbers(context: Context): Map<Long, String> {
        data class Candidate(val e164: String, val rank: Int)
        val best = HashMap<Long, Candidate>()
        runCatching {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.TYPE, Phone.IS_PRIMARY),
                null,
                null,
                null
            )?.use { cursor ->
                val contact = cursor.getColumnIndex(Phone.CONTACT_ID)
                val number = cursor.getColumnIndex(Phone.NUMBER)
                val normalized = cursor.getColumnIndex(Phone.NORMALIZED_NUMBER)
                val type = cursor.getColumnIndex(Phone.TYPE)
                val primary = cursor.getColumnIndex(Phone.IS_PRIMARY)
                if (contact < 0 || number < 0) return@use
                while (cursor.moveToNext()) {
                    // What the user typed first; the provider's own normalised copy is a fallback,
                    // and both go through the app's rules so they match everything else.
                    val e164 = PhoneNumbers.normalize(cursor.getString(number))
                        ?: normalized.takeIf { it >= 0 }?.let { PhoneNumbers.normalize(cursor.getString(it)) }
                        ?: continue
                    val rank = when {
                        primary >= 0 && cursor.getInt(primary) != 0 -> 0
                        type >= 0 && cursor.getInt(type) == Phone.TYPE_MOBILE -> 1
                        else -> 2
                    }
                    val id = cursor.getLong(contact)
                    val held = best[id]
                    if (held == null || rank < held.rank) best[id] = Candidate(e164, rank)
                }
            }
        }
        return best.mapValues { it.value.e164 }
    }
}
