package dev.humanonly.schedule

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Тесты контракта расписания (§4.1): границы periodic-интервала/flex, вычисление следующего запуска,
 * проверка ограничений устройства, backoff linear/exponential с клипом и защитой от переполнения.
 */
class RunScheduleTest {

    private val hour = 60 * 60 * 1000L

    // ── RunSchedule / nextRun ────────────────────────────────────────────────

    @Test
    fun `следующий запуск = last + interval - flex`() {
        val s = RunSchedule(intervalMs = 6 * hour, flexMs = hour)
        assertEquals(1000L + 6 * hour - hour, s.nextRunAtMs(1000L))
    }

    @Test
    fun `interval ниже минимума WorkManager → исключение`() {
        try {
            RunSchedule(intervalMs = RunSchedule.MIN_PERIODIC_INTERVAL_MS - 1)
            throw AssertionError("ожидалось")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("минимум"))
        }
    }

    @Test
    fun `flex больше интервала → исключение`() {
        try {
            RunSchedule(intervalMs = hour, flexMs = hour + 1)
            throw AssertionError("ожидалось")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("flexMs"))
        }
    }

    // ── RunConstraints ───────────────────────────────────────────────────────

    @Test
    fun `дефолтные ограничения выполнены на Wi-Fi, не-низкой батарее`() {
        val c = RunConstraints() // unmetered + battery-not-low
        assertTrue(c.satisfiedBy(DeviceState(metered = false, batteryLow = false)))
    }

    @Test
    fun `лимитная сеть блокирует запуск при requiresUnmetered`() {
        assertFalse(RunConstraints().satisfiedBy(DeviceState(metered = true)))
    }

    @Test
    fun `низкая батарея блокирует, зарядка и idle — по требованию`() {
        assertFalse(RunConstraints().satisfiedBy(DeviceState(batteryLow = true)))
        assertFalse(RunConstraints(requiresCharging = true).satisfiedBy(DeviceState(charging = false)))
        assertFalse(RunConstraints(requiresDeviceIdle = true).satisfiedBy(DeviceState(idle = false)))
        // Без требований — свободнее:
        val lax = RunConstraints(requiresUnmeteredNetwork = false, requiresBatteryNotLow = false)
        assertTrue(lax.satisfiedBy(DeviceState(metered = true, batteryLow = true)))
    }

    // ── BackoffPolicy ────────────────────────────────────────────────────────

    @Test
    fun `linear backoff растёт линейно и клипается по max`() {
        val b = BackoffPolicy(BackoffKind.LINEAR, initialDelayMs = 30_000, maxDelayMs = 100_000)
        assertEquals(30_000, b.delayForAttemptMs(1))
        assertEquals(60_000, b.delayForAttemptMs(2))
        assertEquals(90_000, b.delayForAttemptMs(3))
        assertEquals(100_000, b.delayForAttemptMs(4)) // 120k → клип
    }

    @Test
    fun `exponential backoff удваивается и клипается`() {
        val b = BackoffPolicy(BackoffKind.EXPONENTIAL, initialDelayMs = 30_000, maxDelayMs = 5 * hour)
        assertEquals(30_000, b.delayForAttemptMs(1))
        assertEquals(60_000, b.delayForAttemptMs(2))
        assertEquals(120_000, b.delayForAttemptMs(3))
        assertEquals(240_000, b.delayForAttemptMs(4))
    }

    @Test
    fun `exponential при большом attempt не переполняется, отдаёт max`() {
        val b = BackoffPolicy(BackoffKind.EXPONENTIAL, initialDelayMs = 30_000, maxDelayMs = 5 * hour)
        assertEquals(5 * hour, b.delayForAttemptMs(100)) // без overflow → ровно max
    }

    @Test
    fun `attempt меньше 1 → исключение`() {
        try {
            BackoffPolicy().delayForAttemptMs(0); throw AssertionError("ожидалось")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("attempt"))
        }
    }

    @Test
    fun `initialDelay ниже минимума WorkManager → исключение`() {
        try {
            BackoffPolicy(initialDelayMs = BackoffPolicy.MIN_BACKOFF_MS - 1)
            throw AssertionError("ожидалось")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("initialDelayMs"))
        }
    }
}
