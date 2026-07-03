package dev.humanonly.yandex

/**
 * Adaptive backoff по времени ответа + капча-cooldown (ТЗ §F2, §6.3).
 *
 * §F2: «скользящее среднее response_time: >800мс → RPS 0.5; <200мс → до 1.5».
 * §6.3: «после капчи — cooldown: RPS ×0.5 на 30 минут» (иначе капча вернётся через ~10 треков).
 *
 * Чистая политика (без сна и сети): считает **целевой RPS** из окна времён ответа и множитель
 * cooldown, отдаёт [intervalMultiplier] относительно базовой ставки 1 rps. Втыкается в [RateLimiter]
 * как `extraIntervalMultiplier`, чтобы низкоуровневый пейсер выдержал итоговый интервал. Время —
 * инъектируемое ([nowNanos]) → тесты детерминированны.
 *
 * Итоговый интервал у пейсера = base(1с) × backoff(throttle) × intervalMultiplier(этот класс).
 * multiplier = 1 / targetRps: rps 1.5 → 0.667 · rps 1.0 → 1.0 · rps 0.5 → 2.0 · +cooldown ×2.
 */
class AdaptiveThrottle(
    private val nowNanos: () -> Long,
    private val windowSize: Int = 10,
    private val slowMs: Double = 800.0,
    private val fastMs: Double = 200.0,
    private val slowRps: Double = 0.5,
    private val fastRps: Double = 1.5,
    /** Нижний потолок RPS (в т.ч. с cooldown): защита от бесконечного замедления. */
    private val minRps: Double = 0.25,
    private val captchaCooldownNanos: Long = 30L * 60 * 1_000_000_000, // 30 минут
    private val captchaRpsFactor: Double = 0.5,
) {
    init {
        require(windowSize >= 1) { "windowSize ≥ 1" }
        require(fastMs < slowMs) { "fastMs < slowMs" }
        require(slowRps in minRps..fastRps) { "slowRps в [minRps..fastRps]" }
        require(captchaRpsFactor in 0.0..1.0) { "captchaRpsFactor в [0,1]" }
    }

    private val window = ArrayDeque<Long>(windowSize)
    private var cooldownUntilNanos: Long? = null

    /** Записать измеренное время ответа (мс) успешного/нормального запроса. */
    fun record(responseTimeMs: Long) {
        if (window.size == windowSize) window.removeFirst()
        window.addLast(responseTimeMs.coerceAtLeast(0))
    }

    /** Сигнал капчи — включить/продлить cooldown на 30 минут от текущего момента. */
    fun onCaptcha() {
        cooldownUntilNanos = nowNanos() + captchaCooldownNanos
    }

    fun inCooldown(): Boolean {
        val until = cooldownUntilNanos ?: return false
        if (nowNanos() >= until) { cooldownUntilNanos = null; return false }
        return true
    }

    /** Оставшийся cooldown в наносекундах (0 если нет). */
    fun cooldownRemainingNanos(): Long {
        val until = cooldownUntilNanos ?: return 0
        val left = until - nowNanos()
        return if (left > 0) left else 0
    }

    /** Средний response_time окна (мс); при пустом окне — нейтральный (даёт RPS 1.0). */
    fun meanResponseMs(): Double =
        if (window.isEmpty()) neutralMeanForRps1() else window.sum().toDouble() / window.size

    /** Целевой RPS с учётом времени ответа и cooldown, зажат в [minRps, fastRps]. */
    fun targetRps(): Double {
        val rps = rpsFromResponse() * (if (inCooldown()) captchaRpsFactor else 1.0)
        return rps.coerceIn(minRps, fastRps)
    }

    /** Множитель интервала относительно базы 1 rps (обратен целевому RPS). */
    fun intervalMultiplier(): Double = 1.0 / targetRps()

    private fun rpsFromResponse(): Double {
        if (window.isEmpty()) return 1.0
        val mean = window.sum().toDouble() / window.size
        return when {
            mean >= slowMs -> slowRps
            mean <= fastMs -> fastRps
            else -> {
                val frac = (mean - fastMs) / (slowMs - fastMs) // 0 при fast, 1 при slow
                fastRps + (slowRps - fastRps) * frac
            }
        }
    }

    // mean, соответствующее RPS 1.0 на линейной ветке (для meanResponseMs при пустом окне не важен).
    private fun neutralMeanForRps1(): Double {
        // rps=1.0 → frac = (fastRps-1)/(fastRps-slowRps); mean = fastMs + frac*(slowMs-fastMs)
        val frac = (fastRps - 1.0) / (fastRps - slowRps)
        return fastMs + frac * (slowMs - fastMs)
    }
}
