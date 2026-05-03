package com.example.gplanagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.gplanagent.auth.AuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!AuthManager.isLoggedIn(context)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val fullText = messages.joinToString(" ") { it.messageBody }
        val originatingAddress = messages.firstOrNull()?.originatingAddress.orEmpty()

        if (fullText.isBlank()) return

        // goAsync keeps the receiver alive past onReceive return so the coroutine
        // can finish; without it the process can die mid-IO. applicationContext
        // avoids holding the BroadcastReceiver-scoped Context.
        val pendingResult = goAsync()
        val appCtx = context.applicationContext
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                val contact = ContactLookup.lookupByPhone(appCtx, originatingAddress)
                val senderName = contact.name.ifBlank { originatingAddress }
                val senderOrg = contact.organization
                val result = ApiService.parseAndSave(
                    appCtx, fullText,
                    source = "sms",
                    sender = senderName,
                    senderOrg = senderOrg,
                )
                if (result.success) {
                    ScheduleEventBus.notify(result.message)
                }
            } catch (e: SessionExpiredException) {
                ScheduleEventBus.notifySessionExpired()
            } catch (e: NotLoggedInException) {
                // user logged out between check and call; ignore
            } catch (e: Exception) {
                Log.w(TAG, "SMS parseAndSave failed: ${e.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "GPlanAgent"
    }
}
