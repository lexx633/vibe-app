package dev.humanonly.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
    const val RUN_NOW_WORK_NAME = "humanonly-curation-now"

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

    /**
     * Разовый прогон по требованию из UI ([MainActivity]). Тот же [CurationWorker] и та же логика
     * [dev.humanonly.schedule.RunScheduler], что и в периодике — просто без ожидания периода. Ограничение
     * только сеть (пользователь жмёт осознанно; батарея/idle тут не нужны). Rate-limit к ЯМ сохраняется
     * внутри клиента (хард-правило 7). `REPLACE` — повторный тап не копит очередь дублей.
     */
    fun runNow(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<CurationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(
            RUN_NOW_WORK_NAME, ExistingWorkPolicy.REPLACE, request,
        )
    }
}
