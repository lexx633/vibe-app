package dev.humanonly.pipeline

import dev.humanonly.detector.AudioScorer
import dev.humanonly.detector.DetectionCascade
import dev.humanonly.detector.DetectionResult
import dev.humanonly.detector.MetadataScorer
import dev.humanonly.detector.SloplessGate
import dev.humanonly.detector.TrackMetaFeatures
import dev.humanonly.state.ProcessingStage
import dev.humanonly.state.TrackState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Тесты стриминг-конвейера §7. Оркестрация на fakes: реальный [DetectionCascade] с синтетическим
 * gate (id 100,200,300 — никаких GPL-данных), приёмник/кеш/аудио/чекпоинты — записывающие заглушки.
 */
class ScanConveyorTest {

    private fun gate() = SloplessGate.fromJson("""{"timestamp":"t","artists":[100,200,300]}""")

    private fun cascade(audioScorer: AudioScorer = AudioScorer.Unavailable) =
        DetectionCascade(gate(), MetadataScorer(), audioScorer)

    /** Приёмник вердиктов — копит зафиксированные переходы. */
    private class RecordingSink : VerdictSink {
        data class Commit(val id: String, val to: TrackState, val from: TrackState)
        val commits = mutableListOf<Commit>()
        override fun commit(c: TrackCandidate, result: DetectionResult, from: TrackState, to: TrackState) {
            commits += Commit(c.yandexTrackId, to, from)
        }
    }

    /** Чекпоинты — копит (trackId, stage) в порядке вызова. */
    private class RecordingStages : StageListener {
        val events = mutableListOf<Pair<String, ProcessingStage>>()
        override fun onStage(trackId: String, stage: ProcessingStage) {
            events += trackId to stage
        }
    }

    private fun candidate(
        id: String,
        artistId: String = "999",
        meta: TrackMetaFeatures = TrackMetaFeatures(),
        audioHash: String? = null,
        codec: String? = null,
        quality: String? = null,
        type: String = "music",
        durationMs: Long? = null,
    ) = TrackCandidate(id, artistId, meta, audioHash, codec, quality, type, durationMs)

    // ── Фильтр кандидатов ──────────────────────────────────────────────────────

    @Test
    fun `не-музыка и нулевая длина отсеиваются`() {
        // Каскад, который упадёт при вызове — доказывает, что отсеянные до него не доходят.
        val throwingCascade = DetectionCascade(
            gate(), MetadataScorer(),
            AudioScorer { throw AssertionError("каскад не должен трогать отсеянные") },
        )
        val sink = RecordingSink()
        val summary = ScanConveyor(throwingCascade, sink).run(
            listOf(
                candidate("t1", type = "podcast"),
                candidate("t2", durationMs = 0),
                candidate("t3"), // музыка, длина неизвестна → проходит (meta пусто → clean, аудио не зовётся)
            ),
        )
        assertEquals(2, summary.skippedIneligible)
        assertEquals(1, summary.scanned)
        assertEquals(1, sink.commits.size)
        assertEquals("t3", sink.commits[0].id)
    }

    // ── Базовые вердикты каскада ───────────────────────────────────────────────

    @Test
    fun `blacklist артист → suspected, переход из UNKNOWN`() {
        val sink = RecordingSink()
        val summary = ScanConveyor(cascade(), sink).run(listOf(candidate("t1", artistId = "200")))
        assertEquals(1, summary.suspected)
        assertEquals(TrackState.SUSPECTED, sink.commits[0].to)
        assertEquals(TrackState.UNKNOWN, sink.commits[0].from)
    }

    @Test
    fun `чисто по метаданным → clean`() {
        val sink = RecordingSink()
        val summary = ScanConveyor(cascade(), sink).run(listOf(candidate("t1")))
        assertEquals(1, summary.clean)
        assertEquals(TrackState.CLEAN, sink.commits[0].to)
    }

    @Test
    fun `серая зона без аудио → review_required`() {
        val sink = RecordingSink()
        val summary = ScanConveyor(cascade(), sink)
            .run(listOf(candidate("t1", meta = TrackMetaFeatures(suspiciousLabel = true))))
        assertEquals(1, summary.reviewRequired)
        assertEquals(TrackState.REVIEW_REQUIRED, sink.commits[0].to)
    }

    // ── Detect-cache ───────────────────────────────────────────────────────────

    @Test
    fun `попадание в detect-cache минует каскад и скачивание`() {
        val cached = DetectionResult(
            verdict = TrackState.CLEAN, blacklistHit = false,
            metaScore = 0.0, audioScore = null, finalScore = 0.0, reason = "cache",
        )
        val cache = object : DetectCache {
            var lookups = 0
            var stores = 0
            override fun lookup(audioHash: String, codec: String?, quality: String?): DetectionResult? {
                lookups++; return cached
            }
            override fun store(audioHash: String, codec: String?, quality: String?, result: DetectionResult) {
                stores++
            }
        }
        // Каскад, который упадёт если его вызвать — доказывает, что кеш его минует.
        val throwingCascade = DetectionCascade(
            gate(), MetadataScorer(),
            AudioScorer { throw AssertionError("аудио не должно вызываться") },
        )
        val sink = RecordingSink()
        val summary = ScanConveyor(throwingCascade, sink, cache = cache)
            .run(listOf(candidate("t1", audioHash = "h", codec = "flac", quality = "lossless")))
        assertEquals(1, summary.cacheHits)
        assertEquals(1, summary.clean)
        assertEquals(1, cache.lookups)
        assertEquals(0, cache.stores, "при попадании ничего не сохраняем заново")
        assertEquals(TrackState.CLEAN, sink.commits[0].to)
    }

    @Test
    fun `промах кеша → каскад считает и результат сохраняется в кеш`() {
        val cache = object : DetectCache {
            val stored = mutableMapOf<String, DetectionResult>()
            override fun lookup(audioHash: String, codec: String?, quality: String?): DetectionResult? = null
            override fun store(audioHash: String, codec: String?, quality: String?, result: DetectionResult) {
                stored[audioHash] = result
            }
        }
        val sink = RecordingSink()
        val summary = ScanConveyor(cascade(), sink, cache = cache)
            .run(listOf(candidate("t1", audioHash = "h")))
        assertEquals(0, summary.cacheHits)
        assertEquals(1, summary.clean)
        assertTrue(cache.stored.containsKey("h"), "результат каскада должен осесть в кеше")
    }

    @Test
    fun `без audioHash кеш не трогается вовсе`() {
        val cache = object : DetectCache {
            var lookups = 0
            var stores = 0
            override fun lookup(audioHash: String, codec: String?, quality: String?): DetectionResult? {
                lookups++; return null
            }
            override fun store(audioHash: String, codec: String?, quality: String?, result: DetectionResult) {
                stores++
            }
        }
        ScanConveyor(cascade(), RecordingSink(), cache = cache).run(listOf(candidate("t1")))
        assertEquals(0, cache.lookups)
        assertEquals(0, cache.stores)
    }

    // ── Серая зона + AudioProvider (каскад 2) ──────────────────────────────────

    @Test
    fun `серая зона + провайдер → скачивание, пере-детект по аудио, release`() {
        // Аудио вернёт высокий score → с meta=1.0 итог=1.0 ≥ high → suspected.
        val audioCascade = cascade(AudioScorer { 1.0 })
        val provider = object : AudioProvider {
            var fetched = 0
            var released = 0
            override fun fetchAudioRef(c: TrackCandidate): String? { fetched++; return "ref-${c.yandexTrackId}" }
            override fun release(ref: String) { released++ }
        }
        val sink = RecordingSink()
        val stages = RecordingStages()
        val summary = ScanConveyor(
            audioCascade, sink, audioProvider = provider, stageListener = stages,
        ).run(
            listOf(
                candidate(
                    "t1",
                    meta = TrackMetaFeatures(templateNameHit = true, suspiciousLabel = true, releasesInWindow = 50),
                ),
            ),
        )
        assertEquals(1, summary.downloaded)
        assertEquals(1, summary.suspected)
        assertEquals(1, provider.fetched)
        assertEquals(1, provider.released)
        assertEquals(TrackState.SUSPECTED, sink.commits[0].to)
        // чекпоинты прошли через DOWNLOADING и DETECTING
        val stageCodes = stages.events.filter { it.first == "t1" }.map { it.second }
        assertTrue(stageCodes.contains(ProcessingStage.DOWNLOADING))
        assertTrue(stageCodes.contains(ProcessingStage.DETECTING))
    }

    @Test
    fun `серая зона, провайдер не отдал аудио → остаётся review_required, release не зовётся`() {
        val provider = object : AudioProvider {
            var released = 0
            override fun fetchAudioRef(c: TrackCandidate): String? = null
            override fun release(ref: String) { released++ }
        }
        val sink = RecordingSink()
        val summary = ScanConveyor(cascade(), sink, audioProvider = provider)
            .run(listOf(candidate("t1", meta = TrackMetaFeatures(suspiciousLabel = true))))
        assertEquals(0, summary.downloaded)
        assertEquals(1, summary.reviewRequired)
        assertEquals(0, provider.released, "нечего освобождать — ничего не скачали")
        assertEquals(TrackState.REVIEW_REQUIRED, sink.commits[0].to)
    }

    @Test
    fun `провайдера нет (MVP) → серая зона спит на review_required`() {
        val sink = RecordingSink()
        val summary = ScanConveyor(cascade(AudioScorer { 1.0 }), sink, audioProvider = null)
            .run(listOf(candidate("t1", meta = TrackMetaFeatures(suspiciousLabel = true))))
        assertEquals(0, summary.downloaded)
        assertEquals(1, summary.reviewRequired)
    }

    // ── Чекпоинты и агрегаты ───────────────────────────────────────────────────

    @Test
    fun `чекпоинты SCAN и DONE ставятся на каждый обработанный трек`() {
        val stages = RecordingStages()
        ScanConveyor(cascade(), RecordingSink(), stageListener = stages)
            .run(listOf(candidate("t1")))
        val codes = stages.events.filter { it.first == "t1" }.map { it.second }
        assertEquals(ProcessingStage.SCAN, codes.first())
        assertEquals(ProcessingStage.DONE, codes.last())
    }

    @Test
    fun `отсеянный трек не получает чекпоинтов`() {
        val stages = RecordingStages()
        ScanConveyor(cascade(), RecordingSink(), stageListener = stages)
            .run(listOf(candidate("skip", type = "podcast")))
        assertTrue(stages.events.none { it.first == "skip" })
    }

    @Test
    fun `смешанный прогон агрегирует счётчики корректно`() {
        val sink = RecordingSink()
        val summary = ScanConveyor(cascade(), sink).run(
            listOf(
                candidate("clean1"),
                candidate("susp1", artistId = "100"),
                candidate("review1", meta = TrackMetaFeatures(suspiciousLabel = true)),
                candidate("skip1", type = "audiobook"),
            ),
        )
        assertEquals(3, summary.scanned)
        assertEquals(1, summary.clean)
        assertEquals(1, summary.suspected)
        assertEquals(1, summary.reviewRequired)
        assertEquals(1, summary.skippedIneligible)
    }
}
