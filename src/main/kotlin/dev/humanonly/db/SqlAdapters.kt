package dev.humanonly.db

import dev.humanonly.archive.ArchiveCandidate
import dev.humanonly.archive.ArchiveEntry
import dev.humanonly.archive.ArchiveSink
import dev.humanonly.detector.DetectionResult
import dev.humanonly.detector.TrackMetaFeatures
import dev.humanonly.pipeline.ActionCandidate
import dev.humanonly.pipeline.ActionOp
import dev.humanonly.pipeline.ActionSink
import dev.humanonly.pipeline.TrackCandidate
import dev.humanonly.pipeline.VerdictSink
import dev.humanonly.schedule.ActionQueue
import dev.humanonly.schedule.ArchiveQueue
import dev.humanonly.schedule.ScanSource
import dev.humanonly.state.TrackState

/**
 * Живые SQL-адаптеры портов оркестратора над индексом SQLite (F3 ТЗ). Каждый — реализация интерфейса,
 * который в тестах закрывался fake'ом: [ScanSource], [VerdictSink], [ActionQueue], [ActionSink],
 * [ArchiveQueue], [ArchiveSink]. Работают через тонкий [Db]-порт (JDBC на JVM-тесте, framework на Android).
 *
 * Модель состояния трека в колонках (§5 разложен по колонкам track, не в один столбец):
 *  - `verdict` — узел детекции/ревью: clean|suspected|review_required|ai_confirmed|human_confirmed;
 *               NULL = ещё не сканирован (это и есть scan_delta для [SqlScanSource]).
 *  - `action_taken` — последнее действие цепочки чистки (disliked|moved_to_playlist|…), NULL = нет.
 *  - `phone_dl_status` — `downloaded`, когда локальный FLAC на устройстве.
 *  - `archive_status` — `archived`|`pending`|NULL.
 *
 * Каждая мутация состояния пишет строку в `audit_log` (§12: только id/action/from/to/version/ts — без PII).
 */

/** Общий репозиторий: id-lookup, мутации колонок состояния, запись audit_log. Инкапсулирует SQL. */
class TrackRepository(
    private val db: Db,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Внутренний PK трека по yandex_track_id, либо null, если трека нет в индексе. */
    fun internalId(yandexTrackId: String): Long? =
        db.query(
            "SELECT id FROM track WHERE yandex_track_id = ?",
            listOf(yandexTrackId),
        ) { it.long("id") }.firstOrNull()

    /**
     * Зарегистрировать вновь обнаруженные (scan_delta §7 шаг 1) треки: вставляет строку с verdict=NULL,
     * если такого yandex_track_id ещё нет. Идемпотентно (`INSERT OR IGNORE` по unique-индексу). Возвращает
     * число реально вставленных.
     */
    fun upsertDiscovered(refs: Iterable<DiscoveredTrack>): Int {
        val ts = now()
        var inserted = 0
        for (r in refs) {
            inserted += db.update(
                """
                INSERT OR IGNORE INTO track
                  (yandex_track_id, artist_id, audio_hash, codec, quality, type, is_dead, first_seen, last_seen, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                """.trimIndent(),
                listOf(r.yandexTrackId, r.artistId, r.audioHash, r.codec, r.quality, r.type, ts, ts, ts, ts),
            )
        }
        return inserted
    }

    /**
     * Треки без `artist_id`, ещё не сканированные (`verdict IS NULL`), живые — кандидаты на обогащение
     * метаданными ЯМ (чтобы заработал slopless-гейт каскада 0). Инкрементально: обогащённые не возвращаются.
     */
    fun tracksMissingArtist(limit: Int = 200): List<String> =
        db.query(
            "SELECT yandex_track_id FROM track WHERE artist_id IS NULL AND verdict IS NULL AND is_dead = 0 ORDER BY id LIMIT ?",
            listOf(limit),
        ) { it.string("yandex_track_id")!! }

    /** Дозаполнить `artist_id` из метаданных ЯМ. Без audit — это не переход состояния §5, а обогащение. */
    fun setArtistId(yandexTrackId: String, artistId: String) {
        db.update(
            "UPDATE track SET artist_id = ?, updated_at = ? WHERE yandex_track_id = ?",
            listOf(artistId, now(), yandexTrackId),
        )
    }

    /** Записать вердикт детекции + переход + audit одной транзакцией логики (UPDATE track, INSERT audit_log). */
    fun writeVerdict(c: TrackCandidate, result: DetectionResult, from: TrackState, to: TrackState, detectorVersion: String) {
        val ts = now()
        db.update(
            """
            UPDATE track SET
              verdict = ?, meta_score = ?, audio_score = ?, final_score = ?, blacklist_hit = ?,
              detector_version = ?, last_scan = ?, last_seen = ?, updated_at = ?
            WHERE yandex_track_id = ?
            """.trimIndent(),
            listOf(
                to.code, result.metaScore, result.audioScore, result.finalScore,
                if (result.blacklistHit) 1 else 0, detectorVersion, ts, ts, ts, c.yandexTrackId,
            ),
        )
        appendAudit(c.yandexTrackId, action = "detect", from = from, to = to, detectorVersion = detectorVersion)
    }

    /** Продвинуть колонку действия чистки (disliked/moved_to_playlist) + audit. */
    fun writeAction(yandexTrackId: String, op: ActionOp, from: TrackState, to: TrackState) {
        val ts = now()
        when (op) {
            ActionOp.WHITELIST -> db.update(
                "UPDATE track SET verdict = ?, action_taken = NULL, updated_at = ? WHERE yandex_track_id = ?",
                listOf(TrackState.HUMAN_CONFIRMED.code, ts, yandexTrackId),
            )
            else -> db.update(
                "UPDATE track SET action_taken = ?, updated_at = ? WHERE yandex_track_id = ?",
                listOf(to.code, ts, yandexTrackId),
            )
        }
        appendAudit(yandexTrackId, action = op.name.lowercase(), from = from, to = to)
    }

    /** Пометить трек заархивированным (archive_status=archived) + audit `downloaded → archived`. */
    fun writeArchived(yandexTrackId: String, from: TrackState, detectorVersion: String?) {
        val ts = now()
        db.update(
            "UPDATE track SET archive_status = ?, last_download = ?, updated_at = ? WHERE yandex_track_id = ?",
            listOf("archived", ts, ts, yandexTrackId),
        )
        appendAudit(yandexTrackId, action = "archive", from = from, to = TrackState.ARCHIVED, detectorVersion = detectorVersion)
    }

    /** Зафиксировать отложенную архивацию (archive_status=pending) — данные целы, ретрай позже (§F6). */
    fun writeArchivePending(yandexTrackId: String, reason: String) {
        val ts = now()
        db.update(
            "UPDATE track SET archive_status = ?, updated_at = ? WHERE yandex_track_id = ?",
            listOf("pending", ts, yandexTrackId),
        )
        appendAudit(yandexTrackId, action = "archive_pending:$reason", from = null, to = null)
    }

    /** Одна строка audit_log (§12: без PII). track_id — внутренний PK; трек обязан существовать. */
    fun appendAudit(
        yandexTrackId: String,
        action: String,
        from: TrackState?,
        to: TrackState?,
        detectorVersion: String? = null,
    ) {
        val id = internalId(yandexTrackId)
            ?: error("audit для неизвестного трека: yandex_track_id=$yandexTrackId")
        db.update(
            """
            INSERT INTO audit_log (track_id, action, from_state, to_state, detector_version, ts)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(id, action, from?.code, to?.code, detectorVersion, now()),
        )
    }
}

/** Минимальный реф трека из scan_delta (лайки ЯМ): id + технические поля. Без PII. */
data class DiscoveredTrack(
    val yandexTrackId: String,
    val artistId: String?,
    val audioHash: String? = null,
    val codec: String? = null,
    val quality: String? = null,
    val type: String = "music",
)

/**
 * Извлечение признаков метаданных для трека (каскад 1). В индексе признаки не хранятся — их даёт
 * вызывающий слой (парсинг DTO ЯМ — отдельный чанк). Дефолт — пустые признаки (score=0 → clean).
 */
fun interface MetaResolver {
    fun featuresFor(yandexTrackId: String): TrackMetaFeatures

    companion object {
        val Empty = MetaResolver { TrackMetaFeatures() }
    }
}

/**
 * [ScanSource] над индексом: scan_delta = треки с `verdict IS NULL` (ещё не сканированы), живые (is_dead=0).
 * Признаки метаданных подставляет [meta] (по умолчанию пустые — извлечение из DTO ЯМ отдельно).
 */
class SqlScanSource(
    private val db: Db,
    private val meta: MetaResolver = MetaResolver.Empty,
    private val limit: Int = 500,
) : ScanSource {
    override fun newCandidates(): List<TrackCandidate> =
        db.query(
            """
            SELECT yandex_track_id, artist_id, audio_hash, codec, quality, type
            FROM track WHERE verdict IS NULL AND is_dead = 0 ORDER BY id LIMIT ?
            """.trimIndent(),
            listOf(limit),
        ) { row ->
            val yid = row.string("yandex_track_id")!!
            TrackCandidate(
                yandexTrackId = yid,
                artistId = row.string("artist_id") ?: "",
                meta = meta.featuresFor(yid),
                audioHash = row.string("audio_hash"),
                codec = row.string("codec"),
                quality = row.string("quality"),
                type = row.string("type") ?: "music",
            )
        }
}

/** [VerdictSink] над индексом: UPDATE вердикта/скорингов + audit перехода `unknown → вердикт`. */
class SqlVerdictSink(
    private val repo: TrackRepository,
    private val detectorVersion: String,
) : VerdictSink {
    override fun commit(c: TrackCandidate, result: DetectionResult, from: TrackState, to: TrackState) {
        repo.writeVerdict(c, result, from, to, detectorVersion)
    }
}

/**
 * [ActionQueue] над индексом: треки, подтверждённые как ИИ (`verdict='ai_confirmed'`) и не дошедшие
 * до конца цепочки чистки. currentState учитывает уже применённое действие (resume с середины, §6.1).
 */
class SqlActionQueue(private val db: Db, private val limit: Int = 500) : ActionQueue {
    override fun pending(): List<ActionCandidate> =
        db.query(
            """
            SELECT yandex_track_id, action_taken FROM track
            WHERE verdict = 'ai_confirmed' AND (action_taken IS NULL OR action_taken <> 'moved_to_playlist')
            ORDER BY id LIMIT ?
            """.trimIndent(),
            listOf(limit),
        ) { row ->
            val current = row.string("action_taken")?.let { TrackState.fromCode(it) } ?: TrackState.AI_CONFIRMED
            ActionCandidate(trackId = row.string("yandex_track_id")!!, currentState = current)
        }
}

/** [ActionSink] над индексом: продвигает `action_taken`/`verdict` + audit (§12). */
class SqlActionSink(private val repo: TrackRepository) : ActionSink {
    override fun commit(trackId: String, op: ActionOp, from: TrackState, to: TrackState, changed: Boolean) {
        repo.writeAction(trackId, op, from, to)
    }
}

/**
 * [ArchiveQueue] над индексом: скачанные чистые треки (`phone_dl_status='downloaded'`,
 * verdict∈{clean,human_confirmed}), ещё не заархивированные (archive_status NULL/pending).
 */
class SqlArchiveQueue(private val db: Db, private val limit: Int = 500) : ArchiveQueue {
    override fun pending(): List<ArchiveCandidate> =
        db.query(
            """
            SELECT yandex_track_id, audio_hash, codec, quality, verdict, detector_version FROM track
            WHERE phone_dl_status = 'downloaded' AND verdict IN ('clean', 'human_confirmed')
              AND (archive_status IS NULL OR archive_status = 'pending')
            ORDER BY id LIMIT ?
            """.trimIndent(),
            listOf(limit),
        ) { row ->
            val yid = row.string("yandex_track_id")!!
            ArchiveCandidate(
                trackId = yid,
                hash = requireNotNull(row.string("audio_hash")) { "downloaded трек без audio_hash: $yid" },
                codec = requireNotNull(row.string("codec")) { "downloaded трек без codec: $yid" },
                quality = row.string("quality") ?: "",
                verdict = row.string("verdict") ?: "",
                detectorVersion = row.string("detector_version") ?: "",
                currentState = TrackState.DOWNLOADED,
            )
        }
}

/** [ArchiveSink] над индексом: archive_status archived/pending + audit `downloaded → archived` (§F6). */
class SqlArchiveSink(private val repo: TrackRepository) : ArchiveSink {
    override fun onArchived(entry: ArchiveEntry, from: TrackState) {
        repo.writeArchived(entry.trackId, from, entry.detectorVersion)
    }

    override fun onPending(trackId: String, reason: String) {
        repo.writeArchivePending(trackId, reason)
    }
}
