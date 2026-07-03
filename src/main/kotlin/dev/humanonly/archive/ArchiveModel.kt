package dev.humanonly.archive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Модель архива (ТЗ §F6, §9, data-model §7). Только FLAC-блобы и manifest — НЕ синк (§9: S3-как-БД —
 * антипаттерн). PII-правило (§12): в manifest ТОЛЬКО id / технические поля / коды — ни title, ни artist.
 *
 * `manifest.json` обновляется атомарно (data-model §7): реализация [ManifestStore] пишет temp + rename,
 * здесь — только модель и сериализация.
 */

/** Версия формата манифеста (§13 / data-model §5 `manifest_version`). */
const val MANIFEST_VERSION: Int = 1

/** Запись архива по одному треку (§F6). Все поля технические/коды — без PII. */
@Serializable
data class ArchiveEntry(
    @SerialName("trackId") val trackId: String,
    /** sha256 расшифрованного блоба — ключ дедупликации upload (§6.2). */
    @SerialName("hash") val hash: String,
    @SerialName("codec") val codec: String,
    @SerialName("quality") val quality: String,
    /** путь блоба в S3 (детерминирован из hash) — для 404-fallback отдачи из архива. */
    @SerialName("archivePath") val archivePath: String,
    /** id pre-destructive бэкапа на момент архивации (§F7); null — если архивируем без действия. */
    @SerialName("backupId") val backupId: String? = null,
    /** код вердикта (TrackState.code), напр. "clean". */
    @SerialName("verdict") val verdict: String,
    @SerialName("detectorVersion") val detectorVersion: String,
)

/**
 * Манифест архива: записи по trackId. Атомарное обновление (data-model §7) — read-modify-write в
 * [Archiver], атомарность записи гарантирует [ManifestStore]. Ключ — trackId (для 404-fallback lookup).
 */
@Serializable
data class ArchiveManifest(
    @SerialName("manifestVersion") val manifestVersion: Int = MANIFEST_VERSION,
    @SerialName("entries") val entries: Map<String, ArchiveEntry> = emptyMap(),
) {
    /** Копия с добавленной/обновлённой записью (upsert по trackId). Иммутабельно → безопасно для atomic save. */
    fun upsert(entry: ArchiveEntry): ArchiveManifest =
        copy(entries = entries + (entry.trackId to entry))

    fun get(trackId: String): ArchiveEntry? = entries[trackId]
}

/** Сериализация манифеста: стабильный round-trip, версия формата всегда записывается. */
object ArchiveSerialization {
    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    fun encode(manifest: ArchiveManifest): String =
        json.encodeToString(ArchiveManifest.serializer(), manifest)

    fun decode(text: String): ArchiveManifest =
        json.decodeFromString(ArchiveManifest.serializer(), text)
}
