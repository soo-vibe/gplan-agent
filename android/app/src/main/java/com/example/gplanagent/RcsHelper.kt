package com.example.gplanagent

import android.content.Context
import android.net.Uri

/**
 * Reads RCS (Rich Communication Services) messages from the device's telephony
 * provider. Used to capture chat-style messages that don't fire SMS_RECEIVED.
 *
 * Uses content://im/chat (com.android.providers.telephony.ImProvider) which is
 * accessible with READ_SMS permission. Provider may not exist on every device.
 */
object RcsHelper {

    data class RcsMessage(
        val id: Long,
        val address: String,
        val body: String,
        val date: Long,
    )

    private val URI = Uri.parse("content://im/chat")
    private const val PREFS = "rcs_sync"
    private const val KEY_LAST_ID = "last_id"

    /** Returns received (type=1) messages with _id strictly greater than the last seen id. */
    fun queryNewMessages(ctx: Context): List<RcsMessage> {
        val lastId = lastSeenId(ctx)
        val out = mutableListOf<RcsMessage>()
        try {
            ctx.contentResolver.query(
                URI,
                arrayOf("_id", "address", "body", "date"),
                "_id > ? AND type = 1",
                arrayOf(lastId.toString()),
                "_id ASC",
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow("_id")
                val addrIdx = c.getColumnIndexOrThrow("address")
                val bodyIdx = c.getColumnIndexOrThrow("body")
                val dateIdx = c.getColumnIndexOrThrow("date")
                while (c.moveToNext()) {
                    val body = c.getString(bodyIdx) ?: continue
                    if (body.isBlank()) continue
                    out += RcsMessage(
                        id = c.getLong(idIdx),
                        address = c.getString(addrIdx) ?: "",
                        body = body,
                        date = c.getLong(dateIdx),
                    )
                }
            }
        } catch (e: SecurityException) {
            // App doesn't have READ_SMS or provider rejected access
        } catch (e: IllegalArgumentException) {
            // URI doesn't exist on this device (non-Samsung, no RCS support)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return out
    }

    /** Marks the highest seen id so subsequent queries skip already-processed rows. */
    fun updateLastSeenId(ctx: Context, id: Long) {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getLong(KEY_LAST_ID, 0L)
        if (id > current) prefs.edit().putLong(KEY_LAST_ID, id).apply()
    }

    /** Initializes last_id to current max so first run doesn't re-process old history. */
    fun primeLastSeenIdIfUnset(ctx: Context) {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_LAST_ID)) return
        try {
            ctx.contentResolver.query(
                URI,
                arrayOf("_id"),
                null, null,
                "_id DESC LIMIT 1",
            )?.use { c ->
                val maxId = if (c.moveToFirst()) c.getLong(0) else 0L
                prefs.edit().putLong(KEY_LAST_ID, maxId).apply()
            }
        } catch (e: Exception) {
            prefs.edit().putLong(KEY_LAST_ID, 0L).apply()
        }
    }

    private fun lastSeenId(ctx: Context): Long =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_ID, 0L)
}
