package org.reelhelm.wear.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.reelhelm.wear.WearApp
import java.util.concurrent.TimeUnit

/**
 * The Doze-friendly heartbeat. WorkManager runs this during the OS's maintenance
 * windows, so we pull queued messages/missed-calls without fighting Doze and
 * without FCM. Also kicked off on app resume for a fresh check.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as WearApp).repository
        return runCatching { repo.sync(); Result.success() }.getOrDefault(Result.retry())
    }

    companion object {
        private const val PERIODIC = "wear-sync-periodic"
        private const val ONESHOT = "wear-sync-now"

        /** Minimum WorkManager period is 15 min — fine for text-first. */
        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Fire an immediate sync (app resume / "Sync now" button). */
        fun syncNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                ONESHOT,
                androidx.work.ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().build(),
            )
        }
    }
}
