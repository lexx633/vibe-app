package dev.humanonly.db

/**
 * DDL и PRAGMA индекса SQLite (F3 ТЗ) как строковые константы. Модуль чистый JVM —
 * реальный exec на Android; здесь только текст DDL.
 *
 * Колонки track/detect_cache/audit_log/meta — по §F3 и data-model.md.
 * audit_log без title/artist/playlist_name — PII-правило §12.
 * Индексы — ровно 4 (yandex_track_id unique, verdict, is_dead, last_scan): §F3.
 */
object Schema {

    val CREATE_TRACK = """
        CREATE TABLE IF NOT EXISTS track (
          id INTEGER PRIMARY KEY,
          yandex_track_id TEXT NOT NULL,
          artist_id TEXT,
          title TEXT,
          artist TEXT,
          album TEXT,
          year INTEGER,
          audio_hash TEXT,
          quality TEXT,
          codec TEXT,
          source TEXT,
          type TEXT,
          verdict TEXT,
          action_taken TEXT,
          processing_stage TEXT,
          temp_file_path TEXT,
          final_score REAL,
          meta_score REAL,
          audio_score REAL,
          blacklist_hit INTEGER,
          whitelisted INTEGER,
          is_dead INTEGER NOT NULL DEFAULT 0,
          archive_status TEXT,
          phone_dl_status TEXT,
          lk_available INTEGER,
          sample_path TEXT,
          detector_version TEXT,
          metadata_rules_version TEXT,
          created_at INTEGER,
          first_seen INTEGER,
          last_seen INTEGER,
          last_scan INTEGER,
          last_review INTEGER,
          last_download INTEGER,
          updated_at INTEGER
        )
    """.trimIndent()

    val CREATE_DETECT_CACHE = """
        CREATE TABLE IF NOT EXISTS detect_cache (
          audio_hash TEXT PRIMARY KEY,
          codec TEXT,
          quality TEXT,
          verdict TEXT,
          score REAL,
          detector_version TEXT,
          ts INTEGER
        )
    """.trimIndent()

    // Без title/artist/playlist_name — только id/verdict/version/коды (§12).
    val CREATE_AUDIT_LOG = """
        CREATE TABLE IF NOT EXISTS audit_log (
          id INTEGER PRIMARY KEY,
          track_id INTEGER NOT NULL,
          action TEXT NOT NULL,
          from_state TEXT,
          to_state TEXT,
          detector_version TEXT,
          ts INTEGER NOT NULL
        )
    """.trimIndent()

    val CREATE_META = """
        CREATE TABLE IF NOT EXISTS meta (
          schema_version INTEGER NOT NULL,
          slopless_db_version TEXT,
          backup_format_version TEXT,
          manifest_version TEXT,
          app_version TEXT
        )
    """.trimIndent()

    val CREATE_TABLES = listOf(CREATE_TRACK, CREATE_DETECT_CACHE, CREATE_AUDIT_LOG, CREATE_META)

    /** Ровно 4 индекса (§F3) — больше = медленнее частые INSERT/UPDATE джоб. */
    val INDEXES = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_track_yandex_track_id ON track (yandex_track_id)",
        "CREATE INDEX IF NOT EXISTS idx_track_verdict ON track (verdict)",
        "CREATE INDEX IF NOT EXISTS idx_track_is_dead ON track (is_dead)",
        "CREATE INDEX IF NOT EXISTS idx_track_last_scan ON track (last_scan)",
    )

    /**
     * WAL: на Android ставится официальным API (`enableWriteAheadLogging`), на JVM — этим PRAGMA.
     * Возвращает строку ("wal"/"memory") → на Android исполняется через rawQuery (см. [AndroidDb]).
     */
    const val JOURNAL_PRAGMA = "PRAGMA journal_mode=WAL"

    /**
     * Connection-PRAGMA (§F3): «safety level»/temp_store движок запрещает менять ВНУТРИ транзакции.
     * Поэтому на Android их ставят в `onConfigure` (вне транзакции OpenHelper), а НЕ в `onCreate`
     * (там только DDL). На JVM (JdbcDb) транзакции нет — идут в общем [PRAGMA].
     */
    val CONNECTION_PRAGMA = listOf(
        "PRAGMA synchronous=NORMAL",
        "PRAGMA temp_store=MEMORY",
    )

    /** Полный набор PRAGMA (§F3, цель <100 мс UI на 10k) — для JVM-инициализации на свежем соединении. */
    val PRAGMA = listOf(JOURNAL_PRAGMA) + CONNECTION_PRAGMA
}
