package com.example.gplanagent

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.gplanagent.auth.AuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Doubles as our long-lived host for the RCS ContentObserver.
 *
 * The OS keeps NotificationListenerService bound as long as notification
 * access is granted, which gives us a reliable place to watch
 * content://im/chat in real time. When that URI changes (new RCS arrives),
 * we trigger RcsSync.runOnce.
 */
class KakaoNotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val rcsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scope.launch {
                RcsSync.runOnce(applicationContext)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            contentResolver.registerContentObserver(
                Uri.parse("content://im/chat"),
                true,
                rcsObserver,
            )
        } catch (e: Exception) {
            // 일부 디바이스(non-Samsung)는 이 URI가 없을 수 있음 — silent skip
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        try { contentResolver.unregisterContentObserver(rcsObserver) } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

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
