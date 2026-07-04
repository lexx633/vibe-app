package dev.humanonly.android

import dev.humanonly.detector.DetectionCascade
import dev.humanonly.detector.MetaFeatureExtractor
import dev.humanonly.pipeline.CleanupBucket
import dev.humanonly.pipeline.TrackClassifier
import dev.humanonly.state.TrackState
import dev.humanonly.yandex.YandexClient

/**
 * Живой [TrackClassifier] чистки поверх [YandexClient] (§F4 + чистка мёртвых лайков). Делает РОВНО один
 * запрос `tracks/{id}` на трек через лимитер клиента (хард-правило 7) и раскладывает трек по корзине:
 *
 *   - пустые метаданные ИЛИ `available=false` → [CleanupBucket.DEAD] (трек удалён/недоступен в ЯМ);
 *   - иначе прогоняем ПРОДАКШН-каскад [DetectionCascade] (тот же, что в плановом прогоне) по вердикту:
 *       `suspected`        (slopless-гейт)        → [CleanupBucket.AI_GATE];
 *       `review_required`  (серая зона метаданных) → [CleanupBucket.GRAY];
 *       `clean`                                    → [CleanupBucket.CLEAN].
 *
 * PII (§12): имена артистов/лейблов/название живут ТОЛЬКО тут, сводятся к булевым метапризнакам
 * ([MetaFeatureExtractor]) и наружу (в план/лог/индекс) не уходят.
 */
class YandexCleanupClassifier(
    private val client: YandexClient,
    private val cascade: DetectionCascade,
    private val extractor: MetaFeatureExtractor,
) : TrackClassifier {

    override fun classify(trackId: String): CleanupBucket {
        val meta = client.trackMetadata(trackId).firstOrNull()
            ?: return CleanupBucket.DEAD // пустые метаданные — трек недоступен
        if (!meta.available) return CleanupBucket.DEAD

        val features = extractor.extract(
            title = meta.title,
            artistNames = meta.artists.mapNotNull { it.name },
            labelNames = meta.albums.flatMap { it.labels }.mapNotNull { it.name },
        )
        val result = cascade.detect(meta.primaryArtistId() ?: "", features)
        return when (result.verdict) {
            TrackState.SUSPECTED -> CleanupBucket.AI_GATE
            TrackState.REVIEW_REQUIRED -> CleanupBucket.GRAY
            else -> CleanupBucket.CLEAN
        }
    }
}
