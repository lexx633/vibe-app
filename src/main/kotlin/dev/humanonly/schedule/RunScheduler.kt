package dev.humanonly.schedule

/**
 * «Тело воркера» планировщика (ТЗ §4.1) — framework-agnostic мост между [CurationRun] и WorkManager.
 * Тонкий Android-`CoroutineWorker` только (1) собирает [DeviceState], (2) зовёт [execute], (3) маппит
 * [NextAction] в `Result.success()/retry()/failure()` + перепланирование. Вся логика «что делать по итогу
 * прогона» — здесь, поэтому тестируется на JVM без androidx.work (референс-адаптер — docs/android-adapter-reference.md).
 *
 * Правила (зеркало WorkManager, §4.1, §6.1):
 *  - [RunOutcome.SUCCESS] → [NextAction.Reschedule] на следующий период ([RunSchedule.nextRunAtMs]).
 *  - [RunOutcome.RETRY]   → [NextAction.Retry] с задержкой [BackoffPolicy.delayForAttemptMs] по номеру попытки
 *    (WorkManager: `runAttemptCount`). Ограничения устройства не выполнены — это тоже RETRY (без побочек).
 *  - [RunOutcome.FAILURE] → [NextAction.Drop] (неустранимо: баг/конфиг — не долбить).
 */
class RunScheduler(
    private val run: CurationRun,
    private val schedule: RunSchedule,
) {
    /**
     * Прогнать одну итерацию.
     * @param deviceState снимок устройства (Android-слой строит из системных сигналов).
     * @param attempt     номер попытки (1-based; на Android — `runAttemptCount + 1`).
     * @param nowMs       текущее время эпохи (инъектируется для детерминизма).
     */
    fun execute(deviceState: DeviceState = DeviceState(), attempt: Int = 1, nowMs: Long = System.currentTimeMillis()): SchedulerResult {
        val report = run.execute(deviceState)
        val next = when (report.outcome) {
            RunOutcome.SUCCESS -> NextAction.Reschedule(schedule.nextRunAtMs(nowMs))
            RunOutcome.RETRY -> NextAction.Retry(schedule.backoff.delayForAttemptMs(attempt.coerceAtLeast(1)))
            RunOutcome.FAILURE -> NextAction.Drop
        }
        return SchedulerResult(report, next)
    }
}

/** Итог итерации: отчёт прогона + решение планировщика (что сделает WorkManager-адаптер). */
data class SchedulerResult(
    val report: RunReport,
    val next: NextAction,
)

/** Решение по итогу прогона — маппится 1:1 в WorkManager `Result` + (пере)планирование. */
sealed interface NextAction {
    /** Успех: запланировать следующий периодический запуск на [atMs]. WorkManager: `Result.success()`. */
    data class Reschedule(val atMs: Long) : NextAction

    /** Временный сбой: повторить через [delayMs] (backoff). WorkManager: `Result.retry()` + backoff-критерии. */
    data class Retry(val delayMs: Long) : NextAction

    /** Неустранимо: снять работу. WorkManager: `Result.failure()`. */
    data object Drop : NextAction
}
