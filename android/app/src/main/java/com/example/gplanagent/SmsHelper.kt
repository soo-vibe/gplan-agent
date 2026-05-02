package com.example.gplanagent

import android.content.Context
import android.provider.Telephony

data class SmsMessage(
    val sender: String,
    val body: String,
    val date: Long
)

object SmsHelper {

    fun getRecentSms(context: Context, limit: Int = 20): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        ) ?: return messages

        cursor.use {
            val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                messages.add(
                    SmsMessage(
                        sender = it.getString(addressIdx) ?: "",
                        body = it.getString(bodyIdx) ?: "",
                        date = it.getLong(dateIdx)
                    )
                )
            }
        }
        return messages
    }
}
