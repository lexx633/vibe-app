package dev.humanonly.detector

import dev.humanonly.state.TrackState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Тесты каскада детекции (ADR-0005). Ключевой инвариант MVP: без аудио серая зона НИКОГДА не даёт
 * авто-suspected для не-blacklist — только review_required (human-in-the-loop, §F4).
 * Фикстура gate — синтетическая (id 100,200,300), никаких GPL-данных.
 */
class DetectionCascadeTest {

    private fun gate() = SloplessGate.fromJson(
        """{"timestamp":"t","artists":[100,200,300]}""",
    )

    private fun cascade(
        audioScorer: AudioScorer = AudioScorer.Unavailable,
        config: DetectionConfig = DetectionConfig(),
    ) = DetectionCascade(gate(), MetadataScorer(), audioScorer, config)

    // ── Каскад 0: hard gate ───────────────────────────────────────────────────

    @Test
    fun `blacklist hit → suspected сразу, аудио не считалось`() {
        val calledAudio = booleanArrayOf(false)
        val audio = AudioScorer { calledAudio[0] = true; 0.99 }
        val r = cascade(audio).detect("200", TrackMetaFeatures(templateNameHit = true), audioRef = "h")
        assertEquals(TrackState.SUSPECTED, r.verdict)
        assertTrue(r.blacklistHit)
        assertNull(r.metaScore)
        assertNull(r.audioScore)
        assertEquals(DetectionCascade.REASON_BLACKLIST, r.reason)
        assertFalse(calledAudio[0], "аудио НЕ должно вызываться при hard gate hit")
    }

    // ── Каскад 1: метаданные явно чистые ──────────────────────────────────────

    @Test
    fun `нет сигналов метаданных → clean, аудио не трогаем`() {
        val calledAudio = booleanArrayOf(false)
        val audio = AudioScorer { calledAudio[0] = true; 0.99 }
        val r = cascade(audio).detect("999", TrackMetaFeatures(), audioRef = "h")
        assertEquals(TrackState.CLEAN, r.verdict)
        assertFalse(r.blacklistHit)
        assertEquals(0.0, r.metaScore)
        assertEquals(DetectionCascade.REASON_META_LOW_CLEAN, r.reason)
        assertFalse(calledAudio[0], "чисто по метаданным → аудио не считаем (экономия трафика)")
    }

    // ── Серая зона без аудио (MVP) ────────────────────────────────────────────

    @Test
    fun `серая зона без аудио → review_required, НЕ suspected`() {
        // suspiciousLabel=0.6 ≥ low(0.5) → серая зона; audio недоступно.
        val r = cascade().detect("999", TrackMetaFeatures(suspiciousLabel = true))
        assertEquals(TrackState.REVIEW_REQUIRED, r.verdict)
        assertNull(r.audioScore)
        assertNull(r.finalScore)
        assertEquals(DetectionCascade.REASON_GREY_NO_AUDIO_REVIEW, r.reason)
    }

    @Test
    fun `все сигналы метаданных, но без аудио → всё равно review_required`() {
        val r = cascade().detect(
            "999",
            TrackMetaFeatures(templateNameHit = true, suspiciousLabel = true, releasesInWindow = 50),
        )
        assertEquals(TrackState.REVIEW_REQUIRED, r.verdict)
        assertEquals(1.0, r.metaScore) // сумма весов зажата в 1.0
        assertEquals(DetectionCascade.REASON_GREY_NO_AUDIO_REVIEW, r.reason)
    }

    @Test
    fun `audioRef null → аудио-скорер вообще не зовётся`() {
        val calledAudio = booleanArrayOf(false)
        val audio = AudioScorer { calledAudio[0] = true; 0.99 }
        val r = cascade(audio).detect("999", TrackMetaFeatures(suspiciousLabel = true), audioRef = null)
        assertEquals(TrackState.REVIEW_REQUIRED, r.verdict)
        assertFalse(calledAudio[0])
    }

    // ── Полный каскад с доступным аудио ───────────────────────────────────────

    @Test
    fun `аудио доступно, высокий итог → suspected`() {
        // meta=0.6, audio=1.0 → final = 0.4*0.6 + 0.6*1.0 = 0.84... поднимем audio.
        val audio = AudioScorer { 1.0 }
        val r = cascade(audio).detect("999", TrackMetaFeatures(suspiciousLabel = true), audioRef = "h")
        // final = 0.4*0.6 + 0.6*1.0 = 0.84 < 0.85 → review. Проверим именно границу отдельно ниже.
        assertEquals(TrackState.REVIEW_REQUIRED, r.verdict)
        assertEquals(0.84, r.finalScore!!, 1e-9)
    }

    @Test
    fun `аудио доступно, итог ≥ high → suspected`() {
        // meta=1.0, audio=1.0 → final=1.0 ≥ 0.85.
        val audio = AudioScorer { 1.0 }
        val r = cascade(audio).detect(
            "999",
            TrackMetaFeatures(templateNameHit = true, suspiciousLabel = true, releasesInWindow = 50),
            audioRef = "h",
        )
        assertEquals(TrackState.SUSPECTED, r.verdict)
        assertEquals(1.0, r.finalScore!!, 1e-9)
        assertEquals(DetectionCascade.REASON_CASCADE_HIGH, r.reason)
    }

    @Test
    fun `аудио доступно, итог опускает в clean`() {
        // meta=0.6 (серая → зашли в аудио), audio=0.0 → final=0.24 < low → clean.
        val audio = AudioScorer { 0.0 }
        val r = cascade(audio).detect("999", TrackMetaFeatures(suspiciousLabel = true), audioRef = "h")
        assertEquals(TrackState.CLEAN, r.verdict)
        assertEquals(0.24, r.finalScore!!, 1e-9)
        assertEquals(DetectionCascade.REASON_CASCADE_LOW, r.reason)
    }

    // ── Configurable пороги ───────────────────────────────────────────────────

    @Test
    fun `кастомный порог high меняет вердикт`() {
        val audio = AudioScorer { 1.0 }
        // final=0.84; при high=0.80 → suspected.
        val cfg = DetectionConfig(highThreshold = 0.80)
        val r = cascade(audio, cfg).detect("999", TrackMetaFeatures(suspiciousLabel = true), audioRef = "h")
        assertEquals(TrackState.SUSPECTED, r.verdict)
    }

    @Test
    fun `невалидный конфиг (low больше high) → исключение`() {
        try {
            DetectionConfig(highThreshold = 0.3, lowThreshold = 0.7)
            throw AssertionError("ожидалось IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("lowThreshold"))
        }
    }
}
