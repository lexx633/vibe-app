package dev.humanonly.yandex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    // Поля ниже — PII (§12): парсятся опционально, ядром не используются.
    val title: String? = null,
)
