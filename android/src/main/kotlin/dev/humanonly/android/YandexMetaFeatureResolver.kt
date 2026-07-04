package dev.humanonly.android

import dev.humanonly.db.MetaResolver
import dev.humanonly.detector.MetaFeatureExtractor
import dev.humanonly.detector.TrackMetaFeatures
import dev.humanonly.yandex.YandexClient

/**
 * Живой [MetaResolver] каскада 1: тянет `tracks/{id}` через [YandexClient] (реальный rate-limiter,
 * хард-правило 7), сводит строковые поля (title / имя артиста / имя лейбла) к булевым признакам
 * через чистый [MetaFeatureExtractor]. Ровно один запрос на трек, без доп-эндпоинтов.
 *
 * PII (§12): исходные строки живут только в стеке этого метода — наружу уходит [TrackMetaFeatures]
 * (booleans), имена нигде не сохраняются и не логируются.
 */
class YandexMetaFeatureResolver(
    private val client: YandexClient,
    private val extractor: MetaFeatureExtractor = MetaFeatureExtractor(),
) : MetaResolver {

    override fun featuresFor(yandexTrackId: String): TrackMetaFeatures {
        val meta = client.trackMetadata(yandexTrackId).firstOrNull() ?: return TrackMetaFeatures()
        return extractor.extract(
            title = meta.title,
            artistNames = meta.artists.mapNotNull { it.name },
            labelNames = meta.albums.flatMap { it.labels }.mapNotNull { it.name },
        )
    }
}
