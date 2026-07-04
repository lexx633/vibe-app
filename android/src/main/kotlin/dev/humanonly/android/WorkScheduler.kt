package dev.humanonly.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.humanonly.schedule.BackoffKind
import dev.humanonly.schedule.RunSchedule
import java.util.concurrent.TimeUnit

/**
 * Планирование периодической работы куратора. Поля берём 1:1 из оттестированного [RunSchedule]/
 * `RunConstraints`/`BackoffPolicy` — здесь только маппинг в WorkManager API, логики нет.
 */
object WorkScheduler {

    const val UNIQUE_WORK_NAME = "humanonly-curation"

    fun schedule(ctx: Context, s: RunSchedule) {
        val c = s.constraints
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (c.requiresUnmeteredNetwork) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(c.requiresCharging)
            .setRequiresBatteryNotLow(c.requiresBatteryNotLow)
            .setRequiresDeviceIdle(c.requiresDeviceIdle)
            .build()

        val request = PeriodicWorkRequestBuilder<CurationWorker>(
            s.intervalMs, TimeUnit.MILLISECONDS,
            s.flexMs.coerceAtLeast(1), TimeUnit.MILLISECONDS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                if (s.backoff.kind == BackoffKind.EXPONENTIAL) BackoffPolicy.EXPONENTIAL else BackoffPolicy.LINEAR,
                s.backoff.initialDelayMs, TimeUnit.MILLISECONDS,
            )
            .build()

        WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }
}
