package com.example.gplanagent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Looks up a phone number against the device's contacts to retrieve display name
 * and organization. Returns blanks for any field not available.
 *
 * Returns blanks (not throws) when READ_CONTACTS is not granted — callers can
 * still proceed with whatever raw identifier they have (e.g. the phone number).
 */
object ContactLookup {

    data class Result(val name: String, val organization: String)

    fun lookupByPhone(ctx: Context, phoneNumber: String): Result {
        if (phoneNumber.isBlank()) return Result("", "")
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return Result("", "")

        val name = lookupName(ctx, phoneNumber) ?: return Result("", "")
        val org = lookupOrganization(ctx, phoneNumber) ?: ""
        return Result(name, org)
    }

    private fun lookupName(ctx: Context, phoneNumber: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        ctx.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private fun lookupOrganization(ctx: Context, phoneNumber: String): String? {
        // Phone -> contact id
        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val contactId: Long = ctx.contentResolver.query(
            lookupUri,
            arrayOf(ContactsContract.PhoneLookup._ID),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        } ?: return null

        // Contact id -> organization data row
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Organization.COMPANY,
                ContactsContract.CommonDataKinds.Organization.DEPARTMENT,
            ),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val company = c.getString(0) ?: ""
                val dept = c.getString(1) ?: ""
                return when {
                    company.isNotEmpty() && dept.isNotEmpty() -> "$company / $dept"
                    company.isNotEmpty() -> company
                    dept.isNotEmpty() -> dept
                    else -> null
                }
            }
        }
        return null
    }
}
