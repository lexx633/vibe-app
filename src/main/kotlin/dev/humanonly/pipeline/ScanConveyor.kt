package dev.humanonly.pipeline

import dev.humanonly.detector.DetectionCascade
import dev.humanonly.detector.DetectionResult
import dev.humanonly.detector.TrackMetaFeatures
import dev.humanonly.state.ProcessingStage
import dev.humanonly.state.TrackState
import dev.humanonly.state.TrackStateMachine

/**
 * Стриминг-конвейер сканирования (ТЗ §7, §6.2). Атомарная единица — один трек, буфер = 1
 * (~30 МБ FLAC не копятся): `scan → [detect-cache | каскад → серая зона: FLAC→детект] → вердикт →
 * checkpoint`. Действия (дизлайк/F4/архив/скачивание) — за [ActionDispatcher] (F4/архив — отдельные чанки).
 *
 * Чистая оркестрация: сеть/БД/файлы — за инъектируемыми интерфейсами ([DetectCache], [AudioProvider],
 * [VerdictSink]), поэтому тестируется на fakes без реальных вызовов. Каждый шаг чекпоинтится
 * ([StageListener]) для resume после крэша (§6.1: прогресс в processing_stage). Переход состояния
 * валидируется единым [TrackStateMachine] (хард-правило 10) на границе `unknown → вердикт`.
 *
 * Каскад 2 (аудио) вызывается ТОЛЬКО для серой зоны не-blacklist (§10): каскад без аудио дал
 * `review_required` по причине «нет аудио» И подключён [AudioProvider] → качаем FLAC, пере-детектим.
 * В MVP аудио отложено → провайдер обычно null, ветка спит (но структура готова).
 */
class ScanConveyor(
    private val cascade: DetectionCascade,
    private val sink: VerdictSink,
    private val cache: DetectCache = DetectCache.None,
    private val audioProvider: AudioProvider? = null,
    private val filter: CandidateFilter = CandidateFilter.MusicOnly,
    private val stageListener: StageListener = StageListener.None,
) {
    fun run(candidates: Iterable<TrackCandidate>): ConveyorSummary {
        var scanned = 0; var clean = 0; var suspected = 0; var review = 0
        var cacheHits = 0; var downloaded = 0; var skipped = 0

        for (c in candidates) {
            if (!filter.isEligible(c)) { skipped++; continue }
            stageListener.onStage(c.yandexTrackId, ProcessingStage.SCAN)

            val cached = c.audioHash?.let { cache.lookup(it, c.codec, c.quality) }
            val result: DetectionResult = if (cached != null) {
                cacheHits++
                cached
            } else {
                var r = cascade.detect(c.artistId, c.meta, audioRef = null)
                // Серая зона без аудио + провайдер есть → скачать FLAC (буфер=1) и пере-детектить.
                if (r.reason == DetectionCascade.REASON_GREY_NO_AUDIO_REVIEW && audioProvider != null) {
                    stageListener.onStage(c.yandexTrackId, ProcessingStage.DOWNLOADING)
                    val ref = audioProvider.fetchAudioRef(c)
                    if (ref != null) {
                        downloaded++
                        stageListener.onStage(c.yandexTrackId, ProcessingStage.DETECTING)
                        try {
                            r = cascade.detect(c.artistId, c.meta, audioRef = ref)
                        } finally {
                            audioProvider.release(ref) // удалить temp .part сразу (§7: следующий трек)
                        }
                    }
                }
                c.audioHash?.let { cache.store(it, c.codec, c.quality, r) }
                r
            }

            // Вердикт = ребро графа unknown → {clean|suspected|review_required} (валидируется §5).
            val to = result.verdict
            TrackStateMachine.validateTransition(TrackState.UNKNOWN, to)
            sink.commit(c, result, from = TrackState.UNKNOWN, to = to)

            scanned++
            when (to) {
                TrackState.CLEAN -> clean++
                TrackState.SUSPECTED -> suspected++
                TrackState.REVIEW_REQUIRED -> review++
                else -> Unit
            }
            stageListener.onStage(c.yandexTrackId, ProcessingStage.DONE)
        }

        return ConveyorSummary(
            scanned = scanned, clean = clean, suspected = suspected, reviewRequired = review,
            cacheHits = cacheHits, downloaded = downloaded, skippedIneligible = skipped,
        )
    }
}

/** Кандидат из scan_delta (§7 шаг 1): id + признаки для каскада + поля для кеша/фильтра. */
data class TrackCandidate(
    val yandexTrackId: String,
    val artistId: String,
    val meta: TrackMetaFeatures,
    val audioHash: String? = null,
    val codec: String? = null,
    val quality: String? = null,
    val type: String = "music",
    val durationMs: Long? = null,
)

/** Отсев не-музыки (§7 шаг 1: подкасты/аудиокниги/нулевая длина). */
fun interface CandidateFilter {
    fun isEligible(c: TrackCandidate): Boolean

    companion object {
        val MusicOnly = CandidateFilter { c ->
            c.type == "music" && (c.durationMs == null || c.durationMs > 0)
        }
    }
}

/** Кеш детекта по audio_hash (§7 шаг 3, detect_cache): совпал кодек/качество → вердикт без скачивания. */
interface DetectCache {
    fun lookup(audioHash: String, codec: String?, quality: String?): DetectionResult?
    fun store(audioHash: String, codec: String?, quality: String?, result: DetectionResult)

    object None : DetectCache {
        override fun lookup(audioHash: String, codec: String?, quality: String?): DetectionResult? = null
        override fun store(audioHash: String, codec: String?, quality: String?, result: DetectionResult) {}
    }
}

/** Поставщик аудио для каскада 2: скачивает FLAC (→.part), отдаёт ref/hash; release удаляет temp. */
interface AudioProvider {
    fun fetchAudioRef(c: TrackCandidate): String?
    fun release(ref: String)
}

/** Приёмник вердикта: персистит вердикт + переход состояния + audit (реализация — в БД-слое). */
fun interface VerdictSink {
    fun commit(c: TrackCandidate, result: DetectionResult, from: TrackState, to: TrackState)
}

/** Чекпоинт внутритрекового прогресса (§6.1) — для resume после крэша. */
fun interface StageListener {
    fun onStage(trackId: String, stage: ProcessingStage)

    companion object {
        val None = StageListener { _, _ -> }
    }
}

/** Итоги прогона для уведомления (§7 шаг 5): N новых / K как AI / Q на ревью. */
data class ConveyorSummary(
    val scanned: Int,
    val clean: Int,
    val suspected: Int,
    val reviewRequired: Int,
    val cacheHits: Int,
    val downloaded: Int,
    val skippedIneligible: Int,
)
