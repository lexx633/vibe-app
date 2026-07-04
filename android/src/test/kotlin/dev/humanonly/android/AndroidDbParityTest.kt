package dev.humanonly.android

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import dev.humanonly.db.DiscoveredTrack
import dev.humanonly.db.SqlScanSource
import dev.humanonly.db.TrackRepository
import dev.humanonly.db.initSchema
import dev.humanonly.detector.DetectionResult
import dev.humanonly.detector.TrackMetaFeatures
import dev.humanonly.pipeline.TrackCandidate
import dev.humanonly.state.TrackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Паритет [AndroidDb] (framework `SQLiteDatabase`) vs `Schema`/SQL-адаптеры — БЕЗ устройства/эмулятора.
 * Robolectric поднимает РЕАЛЬНЫЙ SQLite за настоящим android `SQLiteDatabase` API на JVM, поэтому те же
 * [dev.humanonly.db.Schema] DDL и `Sql*`-адаптеры, что проверены на JVM против `JdbcDb`, гоняются здесь
 * через тот самый код, что поедет в APK. Цель — доказать, что DDL валиден движком Android и адаптеры
 * дают тот же результат (ловля SQL-опечаток/несовместимостей на границе framework SQLite).
 *
 * sdk=35 (Android 15), НЕ 36: android-all под API 36 у Robolectric требует JDK 21 (у нас JDK 17,
 * как compileOptions/toolchain всего проекта). Для паритета это неважно — проверяем DDL и SQL-адаптеры,
 * а семантика framework `SQLiteDatabase` между API 35 и 36 идентична (ставить JDK 21 ради теста — нет).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidDbParityTest {

    private lateinit var sqlite: SQLiteDatabase
    private lateinit var db: AndroidDb
    private lateinit var repo: TrackRepository

    @Before
    fun setup() {
        // In-memory реальный SQLite (Robolectric nativeruntime) за тем же AndroidDb, что в APK.
        sqlite = SQLiteDatabase.create(null)
        db = AndroidDb(sqlite)
        db.initSchema()
        repo = TrackRepository(db) { 1000L }
    }

    @After
    fun teardown() = sqlite.close()

    private fun scalarLong(sql: String): Long = db.query(sql) { it.long("v")!! }.first()

    // ── Schema.DDL исполняется движком Android ──────────────────────────────────

    @Test
    fun `initSchema создаёт таблицы и 4 индекса на framework SQLite`() {
        val tables = db.query(
            "SELECT name AS v FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
        ) { it.string("v")!! }.toSet()
        assertTrue("track/detect_cache/audit_log/meta должны существовать",
            tables.containsAll(setOf("track", "detect_cache", "audit_log", "meta")))

        val indexes = db.query(
            "SELECT name AS v FROM sqlite_master WHERE type='index' AND name LIKE 'idx_%'",
        ) { it.string("v")!! }.toSet()
        assertEquals(setOf(
            "idx_track_yandex_track_id", "idx_track_verdict", "idx_track_is_dead", "idx_track_last_scan",
        ), indexes)
    }

    // ── scan_delta паритет (verdict IS NULL) ────────────────────────────────────

    @Test
    fun `upsertDiscovered дедупит по yandex_track_id и выходит в scan_delta`() {
        val n = repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "a1"), DiscoveredTrack("t2", "a2")))
        assertEquals(2, n)
        assertEquals("повтор не дублит", 0, repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "a1"))))

        val cands = SqlScanSource(db).newCandidates()
        assertEquals(setOf("t1", "t2"), cands.map { it.yandexTrackId }.toSet())
    }

    @Test
    fun `сканированный трек (verdict не NULL) уходит из scan_delta`() {
        repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "a1"), DiscoveredTrack("t2", "a2")))
        db.update("UPDATE track SET verdict='clean' WHERE yandex_track_id='t1'")

        assertEquals(listOf("t2"), SqlScanSource(db).newCandidates().map { it.yandexTrackId })
    }

    // ── enrich artist_id паритет ────────────────────────────────────────────────

    @Test
    fun `setArtistId и tracksMissingArtist работают как на JVM`() {
        repo.upsertDiscovered(listOf(DiscoveredTrack("t1", null), DiscoveredTrack("t2", "already")))
        assertEquals("только без artist_id и несканированный", listOf("t1"), repo.tracksMissingArtist())

        repo.setArtistId("t1", "999001")
        assertTrue(repo.tracksMissingArtist().isEmpty())
        assertEquals("999001", db.query("SELECT artist_id AS v FROM track WHERE yandex_track_id='t1'") { it.string("v") }.first())
    }

    // ── verdict + audit_log (§12: без PII) ──────────────────────────────────────

    @Test
    fun `writeVerdict обновляет вердикт и пишет audit_log без PII`() {
        repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "a1")))
        val cand = TrackCandidate("t1", "a1", TrackMetaFeatures())
        val result = DetectionResult(TrackState.CLEAN, blacklistHit = false, metaScore = 0.1, audioScore = null, finalScore = 0.1, reason = "meta_low_clean")

        repo.writeVerdict(cand, result, TrackState.UNKNOWN, TrackState.CLEAN, "det-v1")

        assertEquals("clean", db.query("SELECT verdict AS v FROM track WHERE yandex_track_id='t1'") { it.string("v") }.first())
        val audit = db.query("SELECT action, from_state, to_state, detector_version FROM audit_log") {
            listOf(it.string("action"), it.string("from_state"), it.string("to_state"), it.string("detector_version"))
        }
        assertEquals(listOf(listOf("detect", "unknown", "clean", "det-v1")), audit)
        assertEquals(1, scalarLong("SELECT COUNT(*) AS v FROM audit_log"))
    }

    // ── реальный on-device путь открытия БД (SQLiteOpenHelper + WAL) ────────────

    @Test
    fun `CurationOpenHelper onCreate прогоняет initSchema и WAL на файловой БД`() {
        val helper = CurationOpenHelper(ApplicationProvider.getApplicationContext())
        val port = AndroidDb(helper.writableDatabase) // триггерит onConfigure(WAL)+onCreate(initSchema)

        val tables = port.query(
            "SELECT COUNT(*) AS v FROM sqlite_master WHERE type='table' AND name IN ('track','detect_cache','audit_log','meta')",
        ) { it.long("v")!! }.first()
        assertEquals(4L, tables)
        helper.close()
    }
}
