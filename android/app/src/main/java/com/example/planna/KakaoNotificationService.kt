package com.example.planna

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.planna.auth.AuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * NotificationListenerService that captures schedule-bearing messages from
 * messaging apps and forwards them to the backend for parsing.
 *
 * Doubles as the host for the RCS ContentObserver (kept alive by the OS as
 * long as notification access is granted).
 *
 * Class name kept as KakaoNotificationService for backward compatibility with
 * the existing manifest entry and notification access grant.
 */
class KakaoNotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val rcsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scope.launch { RcsSync.runOnce(applicationContext) }
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
            Log.w(TAG, "RCS observer register failed: ${e.javaClass.simpleName}")
        }
    }

    override fun onDestroy() {
        try { contentResolver.unregisterContentObserver(rcsObserver) } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!AuthManager.isLoggedIn(this)) return

        val pkg = sbn.packageName
        val source = when (pkg) {
            "com.kakao.talk" -> "kakao"
            "com.google.android.gm" -> "gmail"
            "com.nhn.android.mail" -> "naver"
            "com.nhn.android.search" -> "naver"  // 네이버 통합앱의 메일 알림
            else -> return
        }

        val extras = sbn.notification.extras
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val subText = extras.getCharSequence("android.subText")?.toString().orEmpty()

        // 카톡: text가 본문, 이메일: bigText에 더 긴 본문이 들어있음
        val body = if (bigText.length > text.length) bigText else text
        if (body.isBlank()) return

        // 메일에 한해서 사전 필터 — LLM 호출 비용 절약
        if (source != "kakao" && !ScheduleHeuristic.looksLikeSchedule(title, body)) return

        val sender = title
        val senderOrg = subText

        scope.launch {
            try {
                val outcome = ScheduleProcessor.process(
                    this@KakaoNotificationService, body,
                    source = source,
                    sender = sender,
                    senderOrg = senderOrg,
                )
                if (outcome.saved) {
                    ScheduleEventBus.notify("일정 등록: ${outcome.title}")
                }
            } catch (e: SessionExpiredException) {
                ScheduleEventBus.notifySessionExpired()
            } catch (e: NotLoggedInException) {
                // ignore
            } catch (e: Exception) {
                Log.w(TAG, "process failed: ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val TAG = "Planna"
    }
}
