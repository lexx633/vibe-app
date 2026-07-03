package dev.humanonly.detector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Тесты precision-gate режима «удалять» (§10, §16): ≥200 размечено И precision ≥90%. */
class PrecisionGateTest {

    private val gate = PrecisionGate() // 200 / 0.90

    @Test
    fun `мало разметки → закрыт`() {
        val d = gate.evaluate(PrecisionStats(truePositives = 150, falsePositives = 5)) // 155 < 200
        assertFalse(d.allowed)
        assertEquals(PrecisionGate.REASON_INSUFFICIENT_SAMPLES, d.reason)
        assertEquals(155, d.labeled)
    }

    @Test
    fun `хватает выборки, но precision ниже порога → закрыт`() {
        // 200 размечено, TP=170, FP=30 → precision 0.85 < 0.90.
        val d = gate.evaluate(PrecisionStats(truePositives = 170, falsePositives = 30))
        assertFalse(d.allowed)
        assertEquals(PrecisionGate.REASON_PRECISION_BELOW, d.reason)
        assertEquals(0.85, d.precision!!, 1e-9)
    }

    @Test
    fun `выборка и precision в норме → открыт`() {
        // 200 размечено, TP=185, FP=15 → precision 0.925 ≥ 0.90.
        val stats = PrecisionStats(truePositives = 185, falsePositives = 15)
        val d = gate.evaluate(stats)
        assertTrue(d.allowed)
        assertEquals(PrecisionGate.REASON_OK, d.reason)
        assertTrue(gate.isRemovalAllowed(stats))
    }

    @Test
    fun `ровно на границе 200 и 0_90 → открыт`() {
        // TP=180, FP=20 → 200 размечено, precision ровно 0.90.
        val d = gate.evaluate(PrecisionStats(truePositives = 180, falsePositives = 20))
        assertTrue(d.allowed)
        assertEquals(0.90, d.precision!!, 1e-9)
    }

    @Test
    fun `пустая разметка → precision null, закрыт`() {
        val stats = PrecisionStats(0, 0)
        assertNull(stats.precision)
        assertFalse(gate.evaluate(stats).allowed)
    }

    @Test
    fun `configurable пороги — не хардкод`() {
        val soft = PrecisionGate(minSamples = 50, minPrecision = 0.8)
        assertTrue(soft.isRemovalAllowed(PrecisionStats(truePositives = 45, falsePositives = 5))) // 50, 0.9
    }
}
