package dev.humanonly.schedule

/**
 * Контракт периодического запуска куратора (ТЗ §4.1: WorkManager — планировщик + очередь).
 *
 * Framework-agnostic: чистые данные + калькуляторы, без зависимости от `androidx.work` — поэтому
 * тестируется на JVM. Тонкий Android-адаптер (`CoroutineWorker` + `PeriodicWorkRequest`) маппит эти
 * поля 1:1 в WorkManager и появится в Android-модуле; вся логика «когда и с какими ограничениями
 * запускать + как ретраить» — здесь.
 *
 * Зеркалит семантику WorkManager намеренно: [RunConstraints] ↔ `Constraints`, [BackoffPolicy] ↔
 * `setBackoffCriteria`, [RunOutcome] ↔ `Result.success()/retry()/failure()`.
 */
data class RunSchedule(
    /** Период между запусками (ТЗ §4.1 — по расписанию). WorkManager: минимум 15 мин для periodic. */
    val intervalMs: Long,
    /** Flex-окно в конце периода, в которое система вольна запустить (энергосбережение). 0 = без flex. */
    val flexMs: Long = 0,
    val constraints: RunConstraints = RunConstraints(),
    val backoff: BackoffPolicy = BackoffPolicy(),
) {
    init {
        require(intervalMs >= MIN_PERIODIC_INTERVAL_MS) {
            "intervalMs ($intervalMs) < минимум WorkManager для periodic ($MIN_PERIODIC_INTERVAL_MS)"
        }
        require(flexMs in 0..intervalMs) { "flexMs ($flexMs) вне [0, intervalMs]" }
    }

    /**
     * Время следующего планового запуска после [lastRunAtMs] (эпоха, мс). Учитывает flex-окно:
     * ранняя граница = конец периода минус flex. Идемпотентно и детерминированно (для тестов/UI «когда дальше»).
     */
    fun nextRunAtMs(lastRunAtMs: Long): Long = lastRunAtMs + intervalMs - flexMs

    companion object {
        /** Минимальный период periodic-работы в WorkManager — 15 минут. */
        const val MIN_PERIODIC_INTERVAL_MS = 15 * 60 * 1000L
    }
}

/**
 * Предусловия запуска (ТЗ §4.1: вежливость к устройству/сети). Маппятся в WorkManager `Constraints`.
 * MVP-дефолт консервативен: скачивание/загрузка блобов — только по не-лимитному соединению.
 */
data class RunConstraints(
    /** Требовать не-лимитную сеть (Wi-Fi) — трафик FLAC ~30 МБ/трек не жрёт мобильный. */
    val requiresUnmeteredNetwork: Boolean = true,
    val requiresCharging: Boolean = false,
    val requiresBatteryNotLow: Boolean = true,
    val requiresDeviceIdle: Boolean = false,
) {
    /** Выполнены ли условия при текущем состоянии устройства → можно запускать тело работы. */
    fun satisfiedBy(state: DeviceState): Boolean {
        if (requiresUnmeteredNetwork && state.metered) return false
        if (requiresCharging && !state.charging) return false
        if (requiresBatteryNotLow && state.batteryLow) return false
        if (requiresDeviceIdle && !state.idle) return false
        return true
    }
}

/** Снимок состояния устройства для проверки [RunConstraints] (предоставляет Android-слой). */
data class DeviceState(
    val metered: Boolean = false,
    val charging: Boolean = true,
    val batteryLow: Boolean = false,
    val idle: Boolean = true,
)

/**
 * Политика повторов при [RunOutcome.RETRY] (зеркало WorkManager `setBackoffCriteria`).
 * WorkManager: минимум задержки 10 c, максимум 5 ч.
 */
data class BackoffPolicy(
    val kind: BackoffKind = BackoffKind.EXPONENTIAL,
    val initialDelayMs: Long = 30_000,
    val maxDelayMs: Long = 5 * 60 * 60 * 1000L,
) {
    init {
        require(initialDelayMs >= MIN_BACKOFF_MS) { "initialDelayMs < минимум ($MIN_BACKOFF_MS)" }
        require(maxDelayMs >= initialDelayMs) { "maxDelayMs < initialDelayMs" }
    }

    /** Задержка перед попыткой [attempt] (1-based). LINEAR: init·attempt; EXPONENTIAL: init·2^(attempt-1). Клип по max. */
    fun delayForAttemptMs(attempt: Int): Long {
        require(attempt >= 1) { "attempt должен быть ≥ 1" }
        val raw = when (kind) {
            BackoffKind.LINEAR -> initialDelayMs * attempt
            BackoffKind.EXPONENTIAL -> {
                // init · 2^(attempt-1) c защитой от переполнения long.
                val shift = (attempt - 1).coerceAtMost(62)
                val factor = 1L shl shift
                if (initialDelayMs > maxDelayMs / factor) Long.MAX_VALUE else initialDelayMs * factor
            }
        }
        return raw.coerceAtMost(maxDelayMs)
    }

    companion object {
        /** Минимальная backoff-задержка WorkManager — 10 секунд. */
        const val MIN_BACKOFF_MS = 10_000L
    }
}

enum class BackoffKind { LINEAR, EXPONENTIAL }

/**
 * Итог одного прогона работы — зеркало `androidx.work.ListenableWorker.Result`:
 *  - [SUCCESS] — прогон завершён (даже если делать было нечего);
 *  - [RETRY] — временный сбой (сеть/ЯМ 5xx/капча) → перепланировать по [BackoffPolicy];
 *  - [FAILURE] — неустранимый сбой (баг/конфиг) → работу снять, не долбить.
 */
enum class RunOutcome { SUCCESS, RETRY, FAILURE }
