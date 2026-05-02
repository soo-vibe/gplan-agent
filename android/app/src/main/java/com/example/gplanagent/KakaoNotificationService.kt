package com.example.gplanagent

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.gplanagent.auth.AuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class KakaoNotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.kakao.talk") return
        if (!AuthManager.isLoggedIn(this)) return

        val extras = sbn.notification.extras
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        if (text.isBlank()) return

        scope.launch {
            try {
                val result = ApiService.parseAndSave(this@KakaoNotificationService, text, source = "kakao")
                if (result.success) {
                    ScheduleEventBus.notify(result.message)
                }
            } catch (e: SessionExpiredException) {
                ScheduleEventBus.notifySessionExpired()
            } catch (e: NotLoggedInException) {
                // ignore
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
