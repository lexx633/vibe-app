package dev.humanonly.yandex

/**
 * Rate-limiter к API ЯМ (хард-правило 7, CLAUDE.md §18): базово ≤1 rps + adaptive backoff.
 *
 * Вежливость к ЯМ — НФТ: лимитер НЕ отключается даже в тестах. Тестируемость достигается
 * инъекцией источника времени [nowNanos] и «спальника» [sleeper] вместо реального Thread.sleep,
 * так интервалы проверяются детерминированно (без реальных пауз).
 *
 * Модель:
 *  - между разрешениями выдерживается минимум [baseIntervalNanos] (базовая ставка, ≤1 rps);
 *  - при сигнале троттлинга ([onThrottled]) множитель backoff растёт (×[backoffFactor]),
 *    увеличивая фактический интервал; при серии успехов ([onSuccess]) — затухает к 1.0.
 *
 * Капча/троттлинг возвращаются ~через 10 треков без сброса RPS (lessons-learned §6.3) → backoff обязателен.
 */
class RateLimiter(
    private val nowNanos: () -> Long,
    private val sleeper: (Long) -> Unit,
    /** Базовый интервал между запросами. Дефолт 1 с = 1 rps (потолок политики). */
    private val baseIntervalNanos: Long = 1_000_000_000L,
    private val backoffFactor: Double = 2.0,
    private val maxBackoff: Double = 16.0,
    /** Сколько подряд успехов затухают backoff на один шаг вниз. */
    private val successesToDecay: Int = 3,
    /**
     * Доп. множитель интервала поверх backoff (adaptive-политика по времени ответа + капча-cooldown,
     * см. [AdaptiveThrottle]). Дефолт 1.0 — поведение не меняется. Итог: base × backoff × этот множитель.
     */
    private val extraIntervalMultiplier: () -> Double = { 1.0 },
) {
    init {
        require(baseIntervalNanos > 0) { "baseIntervalNanos должен быть > 0" }
        require(backoffFactor >= 1.0) { "backoffFactor должен быть ≥ 1" }
    }

    private var backoff = 1.0
    private var successStreak = 0
    private var lastPermitNanos: Long? = null

    /** Текущий множитель backoff (для тестов/наблюдаемости). */
    fun currentBackoff(): Double = backoff

    /**
     * Блокирует до момента, когда очередной запрос разрешён политикой.
     * Спит через инжектированный [sleeper] ровно недостающую дельту.
     */
    fun acquire() {
        val interval = (baseIntervalNanos * backoff * extraIntervalMultiplier()).toLong()
        val last = lastPermitNanos
        if (last != null) {
            val elapsed = nowNanos() - last
            val wait = interval - elapsed
            if (wait > 0) sleeper(wait)
        }
        lastPermitNanos = nowNanos()
    }

    /** Сигнал троттлинга (429/403/капча/тихий 200) — усилить backoff. */
    fun onThrottled() {
        successStreak = 0
        backoff = (backoff * backoffFactor).coerceAtMost(maxBackoff)
    }

    /** Успешный ответ — постепенно ослаблять backoff к базовой ставке. */
    fun onSuccess() {
        if (backoff <= 1.0) return
        if (++successStreak >= successesToDecay) {
            successStreak = 0
            backoff = (backoff / backoffFactor).coerceAtLeast(1.0)
        }
    }
}
