package dev.humanonly.yandex

/**
 * Read-эндпоинты ЯМ поверх [YandexTransport] + [RateLimiter] + [YandexConfig].
 *
 * Каждый вызов проходит через лимитер (хард-правило 7 — не обходить). Троттлинг
 * ([YandexThrottleException]) конвертируется в сигнал backoff лимитеру и пробрасывается наверх.
 * Живые вызовы к ЯМ этой задачей не выполняются — покрыто fake-транспортом.
 */
class YandexClient(
    private val config: YandexConfig,
    private val transport: YandexTransport,
    private val rateLimiter: RateLimiter,
) {
    /** Подписанный get-file-info → [DownloadInfo]. [ts] инъектируется для детерминизма. */
    fun getFileInfo(trackId: String, ts: Long): DownloadInfo {
        val req = Endpoints.getFileInfo(config, trackId, ts)
        val body = call(req)
        return YandexJson.decodeFromString(GetFileInfoResponse.serializer(), body).result.downloadInfo
    }

    /** id лайкнутых треков. */
    fun likedTrackIds(userId: String): List<String> {
        val req = Endpoints.likes(config, userId)
        val body = call(req)
        return YandexJson.decodeFromString(LikesResponse.serializer(), body)
            .result.library.tracks.map { it.id }
    }

    /** Минимальные метаданные трека (id/available; PII не используется). */
    fun trackMetadata(trackId: String): List<TrackMetadata> {
        val req = Endpoints.trackMetadata(config, trackId)
        val body = call(req)
        return YandexJson.decodeFromString(TrackMetadataResponse.serializer(), body).result
    }

    private fun call(req: Endpoints.Request): String {
        rateLimiter.acquire()
        return try {
            transport.getJson(req.url, req.params, config.authHeaders())
                .also { rateLimiter.onSuccess() }
        } catch (e: YandexThrottleException) {
            rateLimiter.onThrottled()
            throw e
        }
    }
}
