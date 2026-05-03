package com.example.gplanagent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.gplanagent.auth.AuthManager
import java.util.concurrent.TimeUnit

/**
 * Periodically scans the device's RCS provider for new received messages and
 * forwards them to the backend for schedule extraction.
 *
 * The provider doesn't fire SMS_RECEIVED broadcasts, so we have no event-driven
 * hook — pure polling. Combined with an immediate sync on app foreground.
 */
class RcsSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!AuthManager.isLoggedIn(ctx)) return Result.success()

        val messages = RcsHelper.queryNewMessages(ctx)
        if (messages.isEmpty()) return Result.success()

        var maxId = 0L
        for (msg in messages) {
            try {
                val contact = ContactLookup.lookupByPhone(ctx, msg.address)
                ApiService.parseAndSave(
                    ctx, msg.body,
                    source = "rcs",
                    sender = contact.name.ifBlank { msg.address },
                    senderOrg = contact.organization,
                )
                if (msg.id > maxId) maxId = msg.id
            } catch (e: SessionExpiredException) {
                ScheduleEventBus.notifySessionExpired()
                break
            } catch (e: NotLoggedInException) {
                break
            } catch (e: Exception) {
                e.printStackTrace()
                // skip this one but keep advancing through the rest
                if (msg.id > maxId) maxId = msg.id
            }
        }
        if (maxId > 0) RcsHelper.updateLastSeenId(ctx, maxId)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "rcs-sync"

        fun schedulePeriodic(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<RcsSyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
