package com.example.gplanagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.gplanagent.auth.AuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!AuthManager.isLoggedIn(context)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val fullText = messages.joinToString(" ") { it.messageBody }

        if (fullText.isBlank()) return

        scope.launch {
            try {
                val result = ApiService.parseAndSave(context, fullText, source = "sms")
                if (result.success) {
                    ScheduleEventBus.notify(result.message)
                }
            } catch (e: SessionExpiredException) {
                ScheduleEventBus.notifySessionExpired()
            } catch (e: NotLoggedInException) {
                // user logged out between check and call; ignore
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
