package dev.humanonly.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Модель бэкапа лайков/плейлистов (F7, §6.3). Страховка необратимого (хард-правило 5):
 * бэкап снимается ПЕРЕД любой чисткой, restore умеет откатить.
 *
 * PII-правило (§12): ТОЛЬКО id / технические поля. Никаких title/artist/name/description —
 * метаданные восстанавливаются из индекса по id, в бэкап не попадают.
 */

/** Текущая версия формата бэкапа (§13 / data-model §5 `backup_format_version`). */
const val BACKUP_FORMAT_VERSION: Int = 1

/** Один лайкнутый трек в снимке. Только id + технические поля. */
@Serializable
data class LikedTrackEntry(
    @SerialName("trackId") val trackId: String,
    @SerialName("albumId") val albumId: String? = null,
    /** epoch-millis появления лайка (если API отдал); не PII. */
    @SerialName("likedAt") val likedAt: Long? = null,
)

/** Плейлист как состав/порядок по id (§F7). Ни имени, ни описания — это PII (§12). */
@Serializable
data class PlaylistEntry(
    /** технический id плейлиста (kind), а не имя. */
    @SerialName("kind") val kind: String,
    /** порядок треков — список trackId в исходной последовательности. */
    @SerialName("trackIds") val trackIds: List<String> = emptyList(),
)

/**
 * Манифест бэкапа. Атомарное обновление на стороне хранилища (data-model §7) — здесь только модель.
 * Все поля технические; сериализованный JSON не содержит ключей title/artist/name.
 */
@Serializable
data class BackupManifest(
    @SerialName("backupFormatVersion") val backupFormatVersion: Int = BACKUP_FORMAT_VERSION,
    /** epoch-millis создания снимка. Не PII (время, не персональные данные). */
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("likes") val likes: List<LikedTrackEntry> = emptyList(),
    @SerialName("playlists") val playlists: List<PlaylistEntry> = emptyList(),
)

/** Сериализация бэкапа: стабильный round-trip, версия формата всегда записывается. */
object BackupSerialization {
    /** encodeDefaults=true — версия формата пишется даже при значении по умолчанию. */
    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    fun encode(manifest: BackupManifest): String =
        json.encodeToString(BackupManifest.serializer(), manifest)

    fun decode(text: String): BackupManifest =
        json.decodeFromString(BackupManifest.serializer(), text)
}
