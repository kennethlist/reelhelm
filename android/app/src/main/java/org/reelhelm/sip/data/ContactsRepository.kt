package org.reelhelm.sip.data

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Contact(
    val name: String,
    val number: String,
    val photoUri: String?,
)

/**
 * Read-only access to the device address book (ContactsContract). Used for the
 * contact picker and for resolving caller-ID / SMS-sender display names.
 *
 * No write access and no separate directory — exactly the "contacts from
 * Android" requirement.
 */
class ContactsRepository(private val context: Context) {

    private val phoneUtil = PhoneNumberUtil.getInstance()

    /** Full address-book list (name + a primary number). Caller must hold READ_CONTACTS. */
    suspend fun all(): List<Contact> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Contact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { c ->
            val nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val photoIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
            while (c.moveToNext()) {
                out += Contact(
                    name = c.getString(nameIdx) ?: continue,
                    number = c.getString(numIdx) ?: continue,
                    photoUri = c.getString(photoIdx),
                )
            }
        }
        out
    }

    /**
     * Reverse lookup: given a phone number, return the matching contact (or null).
     * Uses PhoneLookup, which handles most carrier number formatting itself.
     */
    suspend fun lookup(number: String): Contact? = withContext(Dispatchers.IO) {
        if (number.isBlank()) return@withContext null
        // Try the number as-is first (PhoneLookup is fairly lenient), then the
        // E.164-normalized form, so a stored "(555) 123-4567" still matches an
        // incoming "+15551234567" and vice-versa. Normalization is for matching
        // only — callers keep displaying the original number.
        queryPhoneLookup(number)?.let { return@withContext it }
        val e164 = normalize(number)
        if (e164 != number) queryPhoneLookup(e164) else null
    }

    private fun queryPhoneLookup(number: String): Contact? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.NUMBER,
                ContactsContract.PhoneLookup.PHOTO_URI,
            ),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                return Contact(
                    name = c.getString(0) ?: number,
                    number = c.getString(1) ?: number,
                    photoUri = c.getString(2),
                )
            }
        }
        return null
    }

    /** Best-effort E.164 normalization for storage/matching. Falls back to the raw input. */
    fun normalize(number: String, defaultRegion: String = "US"): String = try {
        val parsed = phoneUtil.parse(number, defaultRegion)
        phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    } catch (_: Exception) {
        number.trim()
    }
}
