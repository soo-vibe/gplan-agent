package com.example.planna

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic backup poll. Real-time delivery comes from a ContentObserver
 * registered in KakaoNotificationService; this Worker is the safety net for
 * when that service isn't bound (notification access revoked, OS killed it).
 */
class RcsSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        RcsSync.runOnce(applicationContext)
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
