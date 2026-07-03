package dev.humanonly.detector

import dev.humanonly.state.TrackState

/**
 * Каскад детекции с HARD GATE (ТЗ §10, ADR-0005). Оркестрирует три уровня:
 *   0. hard gate slopless ([SloplessGate]) — hit → сразу `suspected`, дальше не идём;
 *   1. метаданные ([MetadataScorer]) → meta_score;
 *   2. аудио ([AudioScorer]) — ТОЛЬКО для серой зоны не-blacklist треков.
 *
 * Аудио-слой в MVP ОТЛОЖЕН (лицензия/патенты deezer, решение Owner 2026-07-04): [audioScorer]
 * возвращает null. Тогда серая зона (meta ≥ low) НЕ авто-флагается как suspected, а уходит в
 * **review_required** — человек решает (human-in-the-loop, §F4; никакого деструктива по метаданным одним).
 *
 * Вердикт — узел графа §5: [TrackState.CLEAN] / [TrackState.SUSPECTED] / [TrackState.REVIEW_REQUIRED].
 * Чистый и детерминированный — офлайн-тестируемый. Пороги/веса configurable ([DetectionConfig]).
 */
class DetectionCascade(
    private val gate: SloplessGate,
    private val metadataScorer: MetadataScorer,
    private val audioScorer: AudioScorer = AudioScorer.Unavailable,
    private val config: DetectionConfig = DetectionConfig(),
) {
    /**
     * @param artistId  id артиста трека (строка из API ЯМ) — для hard gate.
     * @param meta      извлечённые признаки метаданных (каскад 1).
     * @param audioRef  ссылка/хэш аудио для каскада 2 (напр. audio_hash); null — аудио недоступно.
     */
    fun detect(artistId: String, meta: TrackMetaFeatures, audioRef: String? = null): DetectionResult {
        // ── Каскад 0: hard gate ───────────────────────────────────────────────
        if (gate.isAiArtist(artistId)) {
            return DetectionResult(
                verdict = TrackState.SUSPECTED,
                blacklistHit = true,
                metaScore = null,
                audioScore = null,
                finalScore = null,
                reason = REASON_BLACKLIST,
            )
        }

        // ── Каскад 1: метаданные ──────────────────────────────────────────────
        val metaScore = metadataScorer.score(meta)
        if (metaScore < config.lowThreshold) {
            // Явно чисто по метаданным — аудио не трогаем (экономим трафик).
            return DetectionResult(
                verdict = TrackState.CLEAN,
                blacklistHit = false,
                metaScore = metaScore,
                audioScore = null,
                finalScore = metaScore,
                reason = REASON_META_LOW_CLEAN,
            )
        }

        // ── Серая зона (meta ≥ low): каскад 2 — аудио ─────────────────────────
        val audioScore = if (audioRef != null) audioScorer.score(audioRef) else null
        if (audioScore == null) {
            // Аудио недоступно (MVP): по метаданным одним не подтверждаем — к человеку.
            return DetectionResult(
                verdict = TrackState.REVIEW_REQUIRED,
                blacklistHit = false,
                metaScore = metaScore,
                audioScore = null,
                finalScore = null,
                reason = REASON_GREY_NO_AUDIO_REVIEW,
            )
        }

        // Есть аудио: взвешенный итог (веса configurable, стартово 0.4/0.6).
        val finalScore = config.weightMeta * metaScore + config.weightAudio * audioScore
        val verdict = when {
            finalScore >= config.highThreshold -> TrackState.SUSPECTED
            finalScore >= config.lowThreshold -> TrackState.REVIEW_REQUIRED
            else -> TrackState.CLEAN
        }
        return DetectionResult(
            verdict = verdict,
            blacklistHit = false,
            metaScore = metaScore,
            audioScore = audioScore,
            finalScore = finalScore,
            reason = when (verdict) {
                TrackState.SUSPECTED -> REASON_CASCADE_HIGH
                TrackState.REVIEW_REQUIRED -> REASON_CASCADE_GREY
                else -> REASON_CASCADE_LOW
            },
        )
    }

    companion object {
        const val REASON_BLACKLIST = "blacklist"
        const val REASON_META_LOW_CLEAN = "meta_low_clean"
        const val REASON_GREY_NO_AUDIO_REVIEW = "grey_no_audio_review"
        const val REASON_CASCADE_HIGH = "cascade_high_suspected"
        const val REASON_CASCADE_GREY = "cascade_grey_review"
        const val REASON_CASCADE_LOW = "cascade_low_clean"
    }
}

/**
 * Каскад 2 — аудио-скорер. Возвращает score ∈ [0,1] (вероятность AI) или **null**, если аудио-детект
 * недоступен. В MVP аудио-слой отложён → [Unavailable] всегда null (см. lessons-learned 2026-07-04).
 */
fun interface AudioScorer {
    fun score(audioRef: String): Double?

    companion object {
        /** Заглушка на время отложенного аудио-слоя: детект недоступен. */
        val Unavailable: AudioScorer = AudioScorer { null }
    }
}

/** Итог каскада. score-поля null там, где уровень не выполнялся. reason — машинный код без PII (§12). */
data class DetectionResult(
    val verdict: TrackState,
    val blacklistHit: Boolean,
    val metaScore: Double?,
    val audioScore: Double?,
    val finalScore: Double?,
    val reason: String,
)

/** Configurable веса/пороги каскада (ТЗ §10 — «не хардкод»). */
data class DetectionConfig(
    val weightMeta: Double = 0.4,
    val weightAudio: Double = 0.6,
    val highThreshold: Double = 0.85,
    val lowThreshold: Double = 0.5,
) {
    init {
        require(highThreshold in 0.0..1.0 && lowThreshold in 0.0..1.0) { "пороги вне [0,1]" }
        require(lowThreshold <= highThreshold) { "lowThreshold > highThreshold" }
        require(weightMeta >= 0 && weightAudio >= 0) { "веса не могут быть отрицательными" }
    }
}
