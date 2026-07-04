package dev.humanonly.yandex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * DTO ответов API ЯМ (kotlinx-serialization). Все — `ignoreUnknownKeys` через общий [YandexJson],
 * т.к. API нестабилен и отдаёт много лишних полей (lessons-learned: держим слой за интерфейсом).
 *
 * ВАЖНО (эталон get_flac_reference.py): сырой API оборачивает полезную нагрузку в `{"result": {...}}`.
 * DTO это учитывают — верхний уровень содержит поле `result`.
 *
 * PII (§12 data-model): title/artist могут парситься, но в ядре не используются и не логируются.
 */

/** Общий парсер: неизвестные ключи игнорируются (форс-мажор нестабильного API ЯМ). */
val YandexJson: Json = Json { ignoreUnknownKeys = true }

// ── get-file-info ────────────────────────────────────────────────────────────

@Serializable
data class GetFileInfoResponse(val result: GetFileInfoResult)

@Serializable
data class GetFileInfoResult(
    @SerialName("downloadInfo") val downloadInfo: DownloadInfo,
)

@Serializable
data class DownloadInfo(
    val codec: String,
    val quality: String? = null,
    val bitrate: Int = 0,
    val urls: List<String> = emptyList(),
    /** hex-ключ AES-128 (32 символа). Секрет уровня трека — не логировать. */
    val key: String,
)

// ── account/status (только uid, для эндпоинта лайков) ────────────────────────

@Serializable
data class AccountStatusResponse(val result: AccountStatusResult)

@Serializable
data class AccountStatusResult(val account: Account)

@Serializable
data class Account(val uid: Long? = null)

// ── users/likes/tracks ───────────────────────────────────────────────────────

@Serializable
data class LikesResponse(val result: LikesResult)

@Serializable
data class LikesResult(val library: LikesLibrary)

@Serializable
data class LikesLibrary(val tracks: List<LikedTrackRef> = emptyList())

@Serializable
data class LikedTrackRef(
    val id: String,
    @SerialName("albumId") val albumId: String? = null,
)

// ── tracks/{id} (минимальные метаданные) ─────────────────────────────────────

@Serializable
data class TrackMetadataResponse(val result: List<TrackMetadata> = emptyList())

@Serializable
data class TrackMetadata(
    val id: String,
    val available: Boolean = false,
    /** Артисты трека — берём ТОЛЬКО id (для slopless-гейта каскада 0). Имя артиста не парсим (PII §12). */
    val artists: List<ArtistRef> = emptyList(),
    /** Альбомы трека — берём ТОЛЬКО id (нужен albumId для album-aware вставки в плейлист, §F4). */
    val albums: List<AlbumRef> = emptyList(),
    // Поля ниже — PII (§12): парсятся опционально, ядром не используются.
    val title: String? = null,
) {
    /** id основного (первого) артиста — вход slopless-гейта; null, если артистов нет. */
    fun primaryArtistId(): String? = artists.firstOrNull()?.artistId

    /** id основного (первого) альбома — обязателен для `change-relative` вставки; null, если альбомов нет. */
    fun primaryAlbumId(): String? = albums.firstOrNull()?.albumId
}

/**
 * Реф артиста трека. Парсим ТОЛЬКО `id` (имя — PII §12, не берём). id в API ЯМ приходит то числом,
 * то строкой → храним как [JsonPrimitive] и отдаём `.content` (устойчиво к обоим представлениям).
 */
@Serializable
data class ArtistRef(val id: JsonPrimitive) {
    val artistId: String get() = id.content
}

/** Реф альбома трека. Только `id` (имя альбома — PII §12, не берём). id устойчив к number/string. */
@Serializable
data class AlbumRef(val id: JsonPrimitive) {
    val albumId: String get() = id.content
}

// ── users/{uid}/playlists/{kind} (revision + состав для change-relative) ───────

@Serializable
data class PlaylistResponse(val result: PlaylistBody)

/**
 * Плейлист: `revision` (обязателен для `change-relative` — API отклонит запрос с устаревшей ревизией)
 * и состав `tracks` (TrackShort: id + albumId) — по нему считаем индекс вставки/удаления.
 */
@Serializable
data class PlaylistBody(
    val kind: JsonPrimitive? = null,
    val revision: Int = 1,
    @SerialName("trackCount") val trackCount: Int = 0,
    val tracks: List<PlaylistTrackRef> = emptyList(),
) {
    val kindStr: String? get() = kind?.content
}

/** Трек в плейлисте (TrackShort референс-репо): `id` + `albumId`. Имя/название не парсим (PII §12). */
@Serializable
data class PlaylistTrackRef(
    val id: JsonPrimitive,
    @SerialName("albumId") val albumId: String? = null,
) {
    val trackId: String get() = id.content
}

/** Ответ создания плейлиста — тот же shape, что и чтение (result = плейлист). Нужен `kind` нового плейлиста. */
@Serializable
data class PlaylistCreateResponse(val result: PlaylistBody)
