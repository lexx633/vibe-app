package dev.humanonly.db

import dev.humanonly.backup.BackupManifest
import dev.humanonly.backup.LikedTrackEntry

/**
 * Снимок текущих лайков из индекса (F7, §6.3) для **pre-destructive бэкапа** перед авто-чисткой
 * (хард-правило 5: деструктив только после свежего бэкапа). Источник — таблица `track`: в неё
 * попадают ровно обнаруженные в библиотеке лайки (`upsertDiscovered`), поэтому живые (`is_dead=0`)
 * строки = текущее множество лайков, которое авто-дизлайк собирается менять.
 *
 * PII-правило (§12): в манифест уходят ТОЛЬКО `trackId` + время появления лайка — ни title, ни artist.
 * album_id в индексе не хранится (в схеме нет колонки) → [LikedTrackEntry.albumId] остаётся null;
 * восстановление лайка идёт по одному `trackId` (уровень трека, альбом не нужен). Чистая функция над
 * [Db]-портом — тестируется на JVM через `JdbcDb`, тот же код гоняется на Android через `AndroidDb`.
 */
class SqlBackupSource(private val db: Db, private val limit: Int = 100_000) {

    /** Собрать манифест лайков на момент [createdAt] (epoch-millis). Пустой список — лайков нет. */
    fun snapshotLikes(createdAt: Long): BackupManifest {
        val likes = db.query(
            "SELECT yandex_track_id, first_seen FROM track WHERE is_dead = 0 ORDER BY id LIMIT ?",
            listOf(limit),
        ) { row ->
            LikedTrackEntry(trackId = row.string("yandex_track_id")!!, likedAt = row.long("first_seen"))
        }
        return BackupManifest(createdAt = createdAt, likes = likes)
    }
}
