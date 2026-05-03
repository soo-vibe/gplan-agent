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
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val subText = extras.getCharSequence("android.subText")?.toString().orEmpty()

        if (text.isBlank()) return

        // 1:1 채팅: title = 발신자 닉네임, subText 비어있음
        // 그룹 채팅: title = 발신자 닉네임, subText = 방 이름 (대개)
        val sender = title
        val senderOrg = subText

        scope.launch {
            try {
                val result = ApiService.parseAndSave(
                    this@KakaoNotificationService, text,
                    source = "kakao",
                    sender = sender,
                    senderOrg = senderOrg,
                )
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
