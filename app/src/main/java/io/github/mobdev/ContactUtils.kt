package io.github.mobdev

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log


data class Contact(
    val name: String?,
    val phoneNumber: String?,
    val email: String?
)
fun Context.fetchAllContacts(): List<Contact> {
    Log.d("FETCH", "fetchAllContacts called")
    val contactsMap = mutableMapOf<String, Contact>()

    val contentResolver: ContentResolver = this.contentResolver

    // Получаем телефоны
    val phoneCursor = contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        ),
        null,
        null,
        null
    )

    phoneCursor?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val phoneIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val contactIdIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)

        while (cursor.moveToNext()) {
            val contactId = if (contactIdIndex >= 0) cursor.getString(contactIdIndex) else null
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val phone = if (phoneIndex >= 0) cursor.getString(phoneIndex) else null

            if (contactId != null && name != null) {
                val existing = contactsMap[contactId]
                if (existing == null) {
                    contactsMap[contactId] = Contact(name, phone, null)
                } else if (existing.phoneNumber == null && phone != null) {
                    contactsMap[contactId] = Contact(name, phone, existing.email)
                }
            }
        }
    }

    // Получаем email
    val emailCursor = contentResolver.query(
        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.ADDRESS
        ),
        null,
        null,
        null
    )

    emailCursor?.use { cursor ->
        val contactIdIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
        val emailIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)

        while (cursor.moveToNext()) {
            val contactId = if (contactIdIndex >= 0) cursor.getString(contactIdIndex) else null
            val email = if (emailIndex >= 0) cursor.getString(emailIndex) else null

            if (contactId != null && email != null) {
                val existing = contactsMap[contactId]
                if (existing != null && existing.email == null) {
                    contactsMap[contactId] = Contact(existing.name, existing.phoneNumber, email)
                }
            }
        }
    }

    return contactsMap.values.filter { !it.name.isNullOrBlank() }.distinctBy { it.name }
}