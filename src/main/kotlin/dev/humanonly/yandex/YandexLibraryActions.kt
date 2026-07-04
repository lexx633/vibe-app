package dev.humanonly.yandex

import dev.humanonly.pipeline.LibraryActions

/**
 * Живой адаптер [LibraryActions] поверх [YandexClient] (ТЗ §F4). Даёт [dev.humanonly.pipeline.ActionDispatcher]
 * реальные операции с аккаунтом ЯМ вместо fake. Каждая операция идёт через лимитер клиента (хард-правило 7).
 *
 * Реализованы оба режима §F4: [dev.humanonly.pipeline.ActionMode.DISLIKE_ONLY] (дизлайк/снятие) и
 * [dev.humanonly.pipeline.ActionMode.MOVE_TO_PLAYLIST] (album-aware перенос в плейлист «Определены как ИИ треки»
 * через `change-relative`, форма выверена по референс-репо — хард-правило 9).
 *
 * Album-aware: `change-relative` требует albumId вставляемого трека, а сигнатура [LibraryActions] его не несёт —
 * поэтому [addToPlaylist] резолвит albumId сам через [YandexClient.trackMetadata]. Ревизия и позиция вставки —
 * из снимка плейлиста ([YandexClient.playlist]); оптимистичная блокировка ревизией на стороне API.
 *
 * Идемпотентность (§6.2): [addToPlaylist] — no-op (false), если трек уже в плейлисте; [removeFromPlaylist] —
 * no-op, если трека там нет.
 *
 * [userId] (uid аккаунта) резолвится один раз через [YandexClient.accountUid]; фабрика [create] делает это.
 */
class YandexLibraryActions(
    private val client: YandexClient,
    private val userId: String,
) : LibraryActions {

    override fun dislike(trackId: String): Boolean = client.dislikeTrack(userId, trackId)

    override fun undislike(trackId: String): Boolean = client.undislikeTrack(userId, trackId)

    override fun like(trackId: String): Boolean = client.likeTrack(userId, trackId)

    override fun unlike(trackId: String): Boolean = client.unlikeTrack(userId, trackId)

    override fun addToPlaylist(trackId: String, playlistKind: String): Boolean {
        val snapshot = client.playlist(userId, playlistKind)
        if (trackId in snapshot.trackIds) return false // уже в плейлисте — идемпотентный no-op (§6.2)
        val albumId = resolveAlbumId(trackId)
        // Вставляем в конец: at = текущее число треков (индексы 0..size-1 заняты).
        return client.insertTrackToPlaylist(userId, playlistKind, trackId, albumId, at = snapshot.tracks.size, revision = snapshot.revision)
    }

    override fun removeFromPlaylist(trackId: String, playlistKind: String): Boolean {
        val snapshot = client.playlist(userId, playlistKind)
        val idx = snapshot.indexOf(trackId)
        if (idx < 0) return false // трека нет в плейлисте — идемпотентный no-op (§6.2)
        return client.deleteFromPlaylist(userId, playlistKind, from = idx, to = idx + 1, revision = snapshot.revision)
    }

    /** albumId вставляемого трека (обязателен для change-relative). Бросает, если у трека нет альбома. */
    private fun resolveAlbumId(trackId: String): String {
        val meta = client.trackMetadata(trackId).firstOrNull()
            ?: error("трек $trackId: пустые метаданные — не могу разрешить albumId для вставки в плейлист")
        return meta.primaryAlbumId()
            ?: error("трек $trackId: нет albumId в метаданных — album-aware вставка невозможна")
    }

    companion object {
        /** Собрать адаптер, разрешив uid аккаунта одним запросом (идёт через лимитер клиента). */
        fun create(client: YandexClient): YandexLibraryActions =
            YandexLibraryActions(client, client.accountUid().toString())
    }
}
