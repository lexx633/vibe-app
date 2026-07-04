package dev.humanonly.yandex

import dev.humanonly.pipeline.LibraryActions

/**
 * Живой адаптер [LibraryActions] поверх [YandexClient] (ТЗ §F4). Даёт [dev.humanonly.pipeline.ActionDispatcher]
 * реальные операции с аккаунтом ЯМ вместо fake. Каждая операция идёт через лимитер клиента (хард-правило 7).
 *
 * MVP-режим — [dev.humanonly.pipeline.ActionMode.DISLIKE_ONLY]: реализованы дизлайк/снятие дизлайка.
 * Перенос в плейлист ([addToPlaylist]) требует album-aware эндпоинта `change-relative` с diff (нужен albumId,
 * которого нет в сигнатуре) → его форму НЕ выдумываем из памяти (хард-правило 9), включим по референс-репо в v1.1.
 * До тех пор режим MOVE_TO_PLAYLIST через живой ЯМ бросает [UnsupportedOperationException] явно, а не молча
 * возвращает «ничего не сделал».
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

    override fun addToPlaylist(trackId: String, playlistKind: String): Boolean =
        throw UnsupportedOperationException(
            "MOVE_TO_PLAYLIST через живой ЯМ не подключён (нужен album-aware change-relative, референс-репо, v1.1)",
        )

    override fun removeFromPlaylist(trackId: String, playlistKind: String): Boolean =
        throw UnsupportedOperationException(
            "removeFromPlaylist через живой ЯМ не подключён (см. addToPlaylist, v1.1)",
        )

    companion object {
        /** Собрать адаптер, разрешив uid аккаунта одним запросом (идёт через лимитер клиента). */
        fun create(client: YandexClient): YandexLibraryActions =
            YandexLibraryActions(client, client.accountUid().toString())
    }
}
