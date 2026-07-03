package dev.humanonly.yandex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Тесты adaptive backoff по времени ответа + капча-cooldown (§F2, §6.3). */
class AdaptiveThrottleTest {

    private class Clock(var t: Long = 0) { fun now() = t }
    private fun thr(clock: Clock) = AdaptiveThrottle(nowNanos = clock::now)

    @Test
    fun `пустое окно → RPS 1_0 (нейтрально)`() {
        val a = thr(Clock())
        assertEquals(1.0, a.targetRps(), 1e-9)
        assertEquals(1.0, a.intervalMultiplier(), 1e-9)
    }

    @Test
    fun `быстрые ответы (менее 200мс) → RPS 1_5`() {
        val a = thr(Clock())
        repeat(10) { a.record(150) }
        assertEquals(1.5, a.targetRps(), 1e-9)
        assertEquals(1.0 / 1.5, a.intervalMultiplier(), 1e-9)
    }

    @Test
    fun `медленные ответы (более 800мс) → RPS 0_5`() {
        val a = thr(Clock())
        repeat(10) { a.record(1200) }
        assertEquals(0.5, a.targetRps(), 1e-9)
        assertEquals(2.0, a.intervalMultiplier(), 1e-9)
    }

    @Test
    fun `середина диапазона — линейная интерполяция`() {
        val a = thr(Clock())
        repeat(10) { a.record(500) } // ровно посередине 200..800
        // frac=0.5 → rps = 1.5 + (0.5-1.5)*0.5 = 1.0
        assertEquals(1.0, a.targetRps(), 1e-9)
    }

    @Test
    fun `скользящее окно вытесняет старые замеры`() {
        val a = AdaptiveThrottle(nowNanos = { 0 }, windowSize = 3)
        a.record(1200); a.record(1200); a.record(1200)
        assertEquals(0.5, a.targetRps(), 1e-9)
        a.record(100); a.record(100); a.record(100) // старые вытеснены
        assertEquals(1.5, a.targetRps(), 1e-9)
    }

    @Test
    fun `капча включает cooldown RPS x0_5 на 30 минут`() {
        val clock = Clock()
        val a = thr(clock)
        repeat(10) { a.record(150) } // база 1.5 rps
        a.onCaptcha()
        assertTrue(a.inCooldown())
        assertEquals(0.75, a.targetRps(), 1e-9) // 1.5 × 0.5
    }

    @Test
    fun `cooldown истекает через 30 минут по wall-clock`() {
        val clock = Clock()
        val a = thr(clock)
        repeat(10) { a.record(150) }
        a.onCaptcha()
        clock.t = 30L * 60 * 1_000_000_000 - 1
        assertTrue(a.inCooldown())
        clock.t = 30L * 60 * 1_000_000_000
        assertFalse(a.inCooldown())
        assertEquals(1.5, a.targetRps(), 1e-9)
    }

    @Test
    fun `RPS не падает ниже minRps даже с cooldown`() {
        val clock = Clock()
        val a = AdaptiveThrottle(nowNanos = clock::now, minRps = 0.25)
        repeat(10) { a.record(1200) } // 0.5 rps
        a.onCaptcha()                  // ×0.5 → 0.25
        assertEquals(0.25, a.targetRps(), 1e-9)
    }

    @Test
    fun `RateLimiter применяет extraIntervalMultiplier к интервалу`() {
        var now = 0L
        var slept = 0L
        val a = AdaptiveThrottle(nowNanos = { now })
        repeat(10) { a.record(1200) } // 0.5 rps → множитель 2.0
        val rl = RateLimiter(
            nowNanos = { now },
            sleeper = { slept = it },
            baseIntervalNanos = 1_000_000_000L,
            extraIntervalMultiplier = { a.intervalMultiplier() },
        )
        rl.acquire()              // первый — без ожидания
        assertEquals(0L, slept)
        rl.acquire()              // интервал = 1с × 2.0 = 2с
        assertEquals(2_000_000_000L, slept)
    }
}
