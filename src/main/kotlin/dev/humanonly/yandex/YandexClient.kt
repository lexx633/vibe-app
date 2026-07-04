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

    /** uid аккаунта (для эндпоинта лайков). */
    fun accountUid(): Long {
        val req = Endpoints.accountStatus(config)
        val body = call(req)
        return YandexJson.decodeFromString(AccountStatusResponse.serializer(), body).result.account.uid
            ?: error("account.uid отсутствует в ответе")
    }

    /** id лайкнутых треков. */
    fun likedTrackIds(userId: String): List<String> {
        val req = Endpoints.likes(config, userId)
        val body = call(req)
        return YandexJson.decodeFromString(LikesResponse.serializer(), body)
            .result.library.tracks.map { it.id }
    }

    /**
     * id треков с дизлайком (read-only). Тот же shape ответа, что [likedTrackIds]
     * (`result.library.tracks[].id`), поэтому переиспользуем [LikesResponse]. Нужен для верификации,
     * что живой дизлайк применился, и что откат ([undislikeTrack]) его снял.
     */
    fun dislikedTrackIds(userId: String): List<String> {
        val req = Endpoints.dislikes(config, userId)
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

    /**
     * Поставить дизлайк треку (мутирующее — идёт через лимитер, хард-правило 7). Возвращает `true`
     * при подтверждённом 2xx-ответе. ЯМ надёжно не сообщает «уже так», поэтому success трактуем как
     * применённое (идемпотентно, §6.2). userId — uid аккаунта ([accountUid]).
     */
    fun dislikeTrack(userId: String, trackId: String): Boolean {
        callPost(Endpoints.dislikeAdd(config, userId, trackId))
        return true
    }

    /** Снять дизлайк (откат «не ИИ», обратимо). true при подтверждённом 2xx. */
    fun undislikeTrack(userId: String, trackId: String): Boolean {
        callPost(Endpoints.dislikeRemove(config, userId, trackId))
        return true
    }

    /**
     * Поставить лайк (F7-восстановление). Живой дизлайк ЯМ снимает лайк, поэтому корректный откат
     * «не ИИ» обязан вернуть лайк отдельным вызовом. Идёт через лимитер (хард-правило 7). true при 2xx.
     */
    fun likeTrack(userId: String, trackId: String): Boolean {
        callPost(Endpoints.likeAdd(config, userId, trackId))
        return true
    }

    /** Снять лайк (обратная к [likeTrack]). true при подтверждённом 2xx. */
    fun unlikeTrack(userId: String, trackId: String): Boolean {
        callPost(Endpoints.likeRemove(config, userId, trackId))
        return true
    }

    // ── плейлист «Определены как ИИ треки» (§F4 MOVE_TO_PLAYLIST) ─────────────────

    /** Снимок плейлиста: revision (для change-relative) + упорядоченный состав (id + albumId). */
    fun playlist(userId: String, kind: String): PlaylistSnapshot {
        val body = YandexJson.decodeFromString(PlaylistResponse.serializer(), call(Endpoints.playlist(config, userId, kind))).result
        return PlaylistSnapshot(
            kind = body.kindStr ?: kind,
            revision = body.revision,
            tracks = body.tracks.map { PlaylistTrack(it.trackId, it.albumId) },
        )
    }

    /**
     * Вставить трек в плейлист в позицию [at] (album-aware — albumId обязателен, §F4). Идёт через
     * change-relative с текущей [revision] (получить из [playlist]). true при подтверждённом 2xx.
     */
    fun insertTrackToPlaylist(userId: String, kind: String, trackId: String, albumId: String, at: Int, revision: Int): Boolean {
        val diff = PlaylistDiff.insertTrack(at, trackId, albumId)
        callPost(Endpoints.playlistChange(config, userId, kind, revision, diff))
        return true
    }

    /** Удалить из плейлиста полуинтервал [[from], [to]) по текущей [revision]. true при 2xx. */
    fun deleteFromPlaylist(userId: String, kind: String, from: Int, to: Int, revision: Int): Boolean {
        val diff = PlaylistDiff.deleteRange(from, to)
        callPost(Endpoints.playlistChange(config, userId, kind, revision, diff))
        return true
    }

    /** Создать плейлист (служебный «Определены как ИИ треки», приватный). Возвращает kind нового плейлиста. */
    fun createPlaylist(userId: String, title: String, visibility: String = "private"): String {
        val body = YandexJson.decodeFromString(
            PlaylistCreateResponse.serializer(),
            callPost(Endpoints.playlistCreate(config, userId, title, visibility)),
        ).result
        return body.kindStr ?: error("kind нового плейлиста отсутствует в ответе")
    }

    /** Удалить плейлист целиком (очистка live-теста). true при подтверждённом 2xx. */
    fun deletePlaylist(userId: String, kind: String): Boolean {
        callPost(Endpoints.playlistDelete(config, userId, kind))
        return true
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

    private fun callPost(req: Endpoints.Request): String {
        rateLimiter.acquire()
        return try {
            transport.postForm(req.url, req.params, config.authHeaders())
                .also { rateLimiter.onSuccess() }
        } catch (e: YandexThrottleException) {
            rateLimiter.onThrottled()
            throw e
        }
    }
}

/** Снимок плейлиста для change-relative: актуальная ревизия + упорядоченный состав. */
data class PlaylistSnapshot(
    val kind: String,
    val revision: Int,
    val tracks: List<PlaylistTrack>,
) {
    /** Индекс трека в составе (для удаления) или -1, если трека нет. */
    fun indexOf(trackId: String): Int = tracks.indexOfFirst { it.trackId == trackId }
    val trackIds: List<String> get() = tracks.map { it.trackId }
}

/** Трек в плейлисте: id + albumId (albumId нужен, если позже переставлять/пересобирать). */
data class PlaylistTrack(val trackId: String, val albumId: String?)
