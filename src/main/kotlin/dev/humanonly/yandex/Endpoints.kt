package dev.humanonly.yandex

/**
 * Билдеры URL/params запросов к API ЯМ. Транспорт не вызывают — только собирают форму запроса
 * (тестируется офлайн на форме и парсинге фикстур). Живые вызовы — не в этой задаче.
 *
 * Приоритет кодеков и подпись get-file-info — по эталону spike/py/get_flac_reference.py (llistochek-подход):
 * дефолтный download-info отдаёт только mp3, lossless FLAC — только через подписанный get-file-info
 * (lessons-learned 2026-07-03).
 */
object Endpoints {

    /** Порядок кодеков (lossless → FLAC вперёд), точно как в эталоне. */
    const val CODECS = "flac,flac-mp4,mp3,aac,he-aac,aac-mp4,he-aac-mp4"

    data class Request(val url: String, val params: Map<String, String>)

    /**
     * Подписанный get-file-info. [ts] — unix-секунды (инъектируется для детерминизма теста).
     * params строятся в порядке [Signing.SIGN_PARAM_ORDER], затем добавляется `sign` (reuse [Signing]).
     */
    fun getFileInfo(config: YandexConfig, trackId: String, ts: Long): Request {
        val signed = linkedMapOf(
            "ts" to ts.toString(),
            "trackId" to trackId,
            "quality" to "lossless",
            "codecs" to CODECS,
            "transports" to "encraw",
        )
        val sign = Signing.sign(Signing.buildMessage(signed))
        val params = LinkedHashMap(signed).apply { put("sign", sign) }
        return Request("${config.baseUrl}/get-file-info", params)
    }

    /** Статус аккаунта (нужен uid для эндпоинта лайков). Подпись не требуется. */
    fun accountStatus(config: YandexConfig): Request =
        Request("${config.baseUrl}/account/status", emptyMap())

    /** Список лайков. Подпись не требуется — обычный авторизованный GET. */
    fun likes(config: YandexConfig, userId: String): Request =
        Request("${config.baseUrl}/users/$userId/likes/tracks", emptyMap())

    /** Метаданные трека(ов). Подпись не требуется. */
    fun trackMetadata(config: YandexConfig, trackId: String): Request =
        Request("${config.baseUrl}/tracks/$trackId", emptyMap())

    /**
     * Поставить лайк треку: `POST users/{uid}/likes/tracks/add-multiple` (form `track-ids`).
     * Выверено по референс-репо (MarshalX/yandex-music-api `_client/likes.py`: путь
     * `likes/{object_type}s/{action}`, action=`add-multiple`, поле `{object_type}-ids`) — хард-правило 9.
     * Нужен для F7-восстановления: живой дизлайк ЯМ СНИМАЕТ лайк, поэтому корректный откат «не ИИ»
     * обязан вернуть лайк (undislike сам по себе его не восстанавливает).
     */
    fun likeAdd(config: YandexConfig, userId: String, trackId: String): Request =
        Request("${config.baseUrl}/users/$userId/likes/tracks/add-multiple", mapOf("track-ids" to trackId))

    /** Снять лайк: `POST users/{uid}/likes/tracks/remove` (form `track-ids`). Обратная операция к [likeAdd]. */
    fun likeRemove(config: YandexConfig, userId: String, trackId: String): Request =
        Request("${config.baseUrl}/users/$userId/likes/tracks/remove", mapOf("track-ids" to trackId))

    /**
     * Список дизлайков (read-only). `GET users/{uid}/dislikes/tracks` — по референс-репо
     * (MarshalX/yandex-music-api `_client/likes.py::_get_dislikes`). Тот же shape ответа, что likes
     * (`result.library.tracks[].id`). Нужен для верификации/отката живого дизлайка.
     */
    fun dislikes(config: YandexConfig, userId: String): Request =
        Request("${config.baseUrl}/users/$userId/dislikes/tracks", emptyMap())

    // ── мутирующие (POST form) ────────────────────────────────────────────────
    // Формы по конвенции community-клиента yandex-music (track-ids). ХАРД-ПРАВИЛО 9: выверить точные
    // пути/поля по референс-репо перед первым живым вызовом; здесь — только форма запроса (офлайн-тест).

    /** Поставить дизлайк треку: `POST users/{uid}/dislikes/tracks/add-multiple` (form `track-ids`). */
    fun dislikeAdd(config: YandexConfig, userId: String, trackId: String): Request =
        Request("${config.baseUrl}/users/$userId/dislikes/tracks/add-multiple", mapOf("track-ids" to trackId))

    /** Снять дизлайк: `POST users/{uid}/dislikes/tracks/remove` (form `track-ids`). Откат «не ИИ». */
    fun dislikeRemove(config: YandexConfig, userId: String, trackId: String): Request =
        Request("${config.baseUrl}/users/$userId/dislikes/tracks/remove", mapOf("track-ids" to trackId))

    // ── плейлист «Определены как ИИ треки» (§F4 MOVE_TO_PLAYLIST) ─────────────────
    // Выверено по референс-репо (MarshalX/yandex-music-api `_client/playlists.py`, хард-правило 9).

    /** Чтение плейлиста (revision + состав для change-relative): `GET users/{uid}/playlists/{kind}`. */
    fun playlist(config: YandexConfig, userId: String, kind: String): Request =
        Request("${config.baseUrl}/users/$userId/playlists/$kind", emptyMap())

    /**
     * Изменение состава плейлиста: `POST users/{uid}/playlists/{kind}/change`
     * (form `kind`, `revision`, `diff` — референс-репо: `data = {'kind', 'revision', 'diff'}`).
     * `diff` — JSON-массив операций (см. [PlaylistDiff]); `revision` обязателен (иначе API отклонит).
     */
    fun playlistChange(config: YandexConfig, userId: String, kind: String, revision: Int, diff: String): Request =
        Request(
            "${config.baseUrl}/users/$userId/playlists/$kind/change",
            mapOf("kind" to kind, "revision" to revision.toString(), "diff" to diff),
        )

    /**
     * Создание плейлиста: `POST users/{uid}/playlists/create` (form `title`, `visibility`).
     * visibility=`private` по умолчанию (наш служебный плейлист приватный, не `public` как в референс-репо).
     */
    fun playlistCreate(config: YandexConfig, userId: String, title: String, visibility: String = "private"): Request =
        Request(
            "${config.baseUrl}/users/$userId/playlists/create",
            mapOf("title" to title, "visibility" to visibility),
        )

    /** Удаление плейлиста целиком: `POST users/{uid}/playlists/{kind}/delete`. Нужно для очистки live-теста. */
    fun playlistDelete(config: YandexConfig, userId: String, kind: String): Request =
        Request("${config.baseUrl}/users/$userId/playlists/$kind/delete", emptyMap())
}
