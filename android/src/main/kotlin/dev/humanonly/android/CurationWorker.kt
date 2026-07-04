package dev.humanonly.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.humanonly.schedule.NextAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Тонкий Android-воркер (§4.1). Логики «что делать по итогу прогона» здесь НЕТ — она в оттестированном
 * [dev.humanonly.schedule.RunScheduler]. Воркер только: (1) собирает [dev.humanonly.schedule.DeviceState]
 * из системных сигналов, (2) зовёт `execute`, (3) маппит [NextAction] → `Result`. Маппинг 1:1 —
 * тестируется на JVM (RunSchedulerTest), не здесь (референс: docs/android-adapter-reference.md).
 */
class CurationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val scheduler = ServiceLocator.scheduler(applicationContext)
        val device = readDeviceState(applicationContext)
        val res = scheduler.execute(
            deviceState = device,
            attempt = runAttemptCount + 1, // WorkManager 0-based → 1-based backoff.
            nowMs = System.currentTimeMillis(),
        )
        when (res.next) {
            is NextAction.Reschedule -> Result.success() // PeriodicWorkRequest сам перезапустит по периоду.
            is NextAction.Retry -> Result.retry()        // backoff задан в PeriodicWorkRequest.
            NextAction.Drop -> Result.failure()          // неустранимо — снять работу.
        }
    }
}
