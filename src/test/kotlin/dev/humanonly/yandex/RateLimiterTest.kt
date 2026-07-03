package dev.humanonly.yandex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Офлайн, детерминированно: инжектируем виртуальные часы и «спальник», реального Thread.sleep нет.
 * Лимитер НЕ выключается (хард-правило 7) — тестируем именно его поведение.
 */
class RateLimiterTest {

    /** Виртуальные часы: sleeper двигает время вперёд ровно на запрошенную дельту. */
    private class Clock {
        var now = 0L
        val slept = mutableListOf<Long>()
        fun sleeper(ns: Long) { slept += ns; now += ns }
    }

    private fun limiter(clock: Clock, base: Long = 1_000_000_000L) =
        RateLimiter(nowNanos = { clock.now }, sleeper = clock::sleeper, baseIntervalNanos = base)

    @Test
    fun `первый acquire не спит, второй выдерживает базовый интервал`() {
        val clock = Clock()
        val rl = limiter(clock)
        rl.acquire()
        assertTrue(clock.slept.isEmpty(), "первый запрос не должен ждать")
        rl.acquire()
        assertEquals(1, clock.slept.size)
        assertEquals(1_000_000_000L, clock.slept.single(), "интервал = базовая ставка (1 rps)")
    }

    @Test
    fun `если время уже прошло — не спим лишнего`() {
        val clock = Clock()
        val rl = limiter(clock)
        rl.acquire()
        clock.now += 2_000_000_000L // прошло 2 с > интервала
        rl.acquire()
        assertTrue(clock.slept.isEmpty(), "интервал уже выдержан снаружи — ждать не нужно")
    }

    @Test
    fun `backoff растёт после throttled и увеличивает интервал`() {
        val clock = Clock()
        val rl = limiter(clock)
        rl.acquire()
        rl.onThrottled() // ×2
        rl.acquire()
        assertEquals(2.0, rl.currentBackoff())
        assertEquals(2_000_000_000L, clock.slept.last(), "интервал удвоился из-за backoff")
    }

    @Test
    fun `backoff затухает после серии успехов`() {
        val clock = Clock()
        val rl = limiter(clock)
        rl.onThrottled(); rl.onThrottled() // ×4
        assertEquals(4.0, rl.currentBackoff())
        repeat(3) { rl.onSuccess() } // один шаг вниз: /2
        assertEquals(2.0, rl.currentBackoff())
        repeat(3) { rl.onSuccess() }
        assertEquals(1.0, rl.currentBackoff())
        repeat(3) { rl.onSuccess() } // не опускается ниже 1.0
        assertEquals(1.0, rl.currentBackoff())
    }

    @Test
    fun `backoff ограничен потолком maxBackoff`() {
        val clock = Clock()
        val rl = RateLimiter(
            nowNanos = { clock.now }, sleeper = clock::sleeper,
            baseIntervalNanos = 1_000_000_000L, backoffFactor = 2.0, maxBackoff = 8.0,
        )
        repeat(10) { rl.onThrottled() }
        assertEquals(8.0, rl.currentBackoff())
    }
}
