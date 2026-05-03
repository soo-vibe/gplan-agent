package com.example.gplanagent

import android.content.Context
import android.util.Log
import com.example.gplanagent.auth.AuthManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single entry point for RCS catch-up sync. Called from:
 *  - WorkManager periodic backup poll
 *  - MainActivity.onResume (immediate catch-up on foreground)
 *  - KakaoNotificationService ContentObserver (real-time on new RCS arrival)
 *
 * A mutex serializes concurrent calls so the three triggers above never
 * race against each other or hit the backend with the same message twice.
 */
object RcsSync {

    private val mutex = Mutex()

    suspend fun runOnce(ctx: Context) {
        if (!AuthManager.isLoggedIn(ctx)) return
        mutex.withLock {
            val messages = RcsHelper.queryNewMessages(ctx)
            if (messages.isEmpty()) return@withLock

            var maxId = 0L
            var savedAny = false
            for (msg in messages) {
                try {
                    val contact = ContactLookup.lookupByPhone(ctx, msg.address)
                    val result = ApiService.parseAndSave(
                        ctx, msg.body,
                        source = "rcs",
                        sender = contact.name.ifBlank { msg.address },
                        senderOrg = contact.organization,
                    )
                    if (result.success) savedAny = true
                } catch (e: SessionExpiredException) {
                    ScheduleEventBus.notifySessionExpired()
                    return@withLock
                } catch (e: NotLoggedInException) {
                    return@withLock
                } catch (e: Exception) {
                    Log.w(TAG, "RCS parseAndSave failed: ${e.javaClass.simpleName}")
                }
                if (msg.id > maxId) maxId = msg.id
            }
            if (maxId > 0) RcsHelper.updateLastSeenId(ctx, maxId)
            if (savedAny) ScheduleEventBus.notify("RCS 일정 등록됨")
        }
    }

    private const val TAG = "GPlanAgent"
}
