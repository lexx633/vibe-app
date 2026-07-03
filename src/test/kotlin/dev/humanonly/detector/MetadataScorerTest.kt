package dev.humanonly.detector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Тесты каскада 1 (метаданные-скорер): суммирование весов, зажим в [0,1], каденция, конфиг. */
class MetadataScorerTest {

    @Test
    fun `нет сигналов → 0`() {
        assertEquals(0.0, MetadataScorer().score(TrackMetaFeatures()))
    }

    @Test
    fun `одиночные сигналы дают свой вес`() {
        val s = MetadataScorer()
        assertEquals(0.5, s.score(TrackMetaFeatures(templateNameHit = true)))
        assertEquals(0.6, s.score(TrackMetaFeatures(suspiciousLabel = true)))
        assertEquals(0.4, s.score(TrackMetaFeatures(releasesInWindow = 30)))
    }

    @Test
    fun `каденция ниже порога не срабатывает`() {
        assertEquals(0.0, MetadataScorer().score(TrackMetaFeatures(releasesInWindow = 29)))
    }

    @Test
    fun `каденция null не срабатывает`() {
        assertEquals(0.0, MetadataScorer().score(TrackMetaFeatures(releasesInWindow = null)))
    }

    @Test
    fun `сумма сигналов зажимается в 1_0`() {
        val s = MetadataScorer().score(
            TrackMetaFeatures(templateNameHit = true, suspiciousLabel = true, releasesInWindow = 40),
        )
        assertEquals(1.0, s) // 0.5+0.6+0.4=1.5 → clamp 1.0
    }

    @Test
    fun `кастомные веса и порог`() {
        val rules = MetadataRules(
            templateNameWeight = 0.1,
            suspiciousLabelWeight = 0.2,
            releaseFloodWeight = 0.05,
            releaseFloodThreshold = 10,
        )
        val s = MetadataScorer(rules)
        assertEquals(0.35, s.score(TrackMetaFeatures(true, true, 10)), 1e-9)
        assertEquals("meta-v1", s.rulesVersion)
    }

    @Test
    fun `отрицательный вес → исключение`() {
        assertThrows(IllegalArgumentException::class.java) {
            MetadataRules(templateNameWeight = -0.1)
        }
    }
}
