package dev.humanonly.db

import dev.humanonly.archive.ArchiveEntry
import dev.humanonly.archive.ArchiveManifest
import dev.humanonly.archive.Archiver
import dev.humanonly.archive.BlobStore
import dev.humanonly.archive.LocalStore
import dev.humanonly.archive.ManifestStore
import dev.humanonly.config.FeatureFlags
import dev.humanonly.detector.DetectionCascade
import dev.humanonly.detector.DetectionResult
import dev.humanonly.detector.MetadataScorer
import dev.humanonly.detector.SloplessGate
import dev.humanonly.detector.TrackMetaFeatures
import dev.humanonly.pipeline.ActionDispatcher
import dev.humanonly.pipeline.ActionMode
import dev.humanonly.pipeline.ActionOp
import dev.humanonly.pipeline.BackupGuard
import dev.humanonly.pipeline.LibraryActions
import dev.humanonly.pipeline.ScanConveyor
import dev.humanonly.pipeline.TrackCandidate
import dev.humanonly.schedule.CurationRun
import dev.humanonly.schedule.RunOutcome
import dev.humanonly.schedule.RunSchedule
import dev.humanonly.state.TrackState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Живые SQL-адаптеры (§F3) против РЕАЛЬНОГО SQLite (sqlite-jdbc, in-memory). Проверяют, что Schema.DDL
 * и SQL адаптеров исполняются движком (ловля опечаток), корректно кодируют scan_delta/цепочку §5/архив
 * в колонки и пишут audit_log без PII (§12). Финальный тест — весь [CurationRun] поверх живой БД.
 */
class SqlAdaptersTest {

    private lateinit var db: JdbcDb
    private lateinit var repo: TrackRepository
    private val clock = { 1000L }

    @BeforeEach
    fun setup() {
        db = JdbcDb()
        db.initSchema()
        repo = TrackRepository(db, clock)
    }

    @AfterEach
    fun teardown() = db.close()

    private fun scalarLong(sql: String, args: List<Any?> = emptyList()): Long =
        db.query(sql, args) { it.long("v")!! }.first()

    // ── scan_delta ────────────────────────────────────────────────────────────

    @Test
    fun `upsertDiscovered вставляет новые и дедупит по yandex_track_id`() {
        val n = repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "a1"), DiscoveredTrack("t2", "a2")))
        assertEquals(2, n)
        val again = repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "a1")))
        assertEquals(0, again, "повтор того же yandex_track_id не создаёт дубль")
        assertEquals(2, scalarLong("SELECT COUNT(*) AS v FROM track"))
    }

    @Test
    fun `SqlScanSource отдаёт только несканированные (verdict IS NULL), с техполями`() {
        repo.upsertDiscovered(
            listOf(DiscoveredTrack("t1", "100", audioHash = "h1", codec = "flac", quality = "lossless")),
        )
        val cands = SqlScanSource(db).newCandidates()
        assertEquals(1, cands.size)
        val c = cands.first()
        assertEquals("t1", c.yandexTrackId)
        assertEquals("100", c.artistId)
        assertEquals("h1", c.audioHash)
        assertEquals("flac", c.codec)
    }

    // ── вердикт + audit ───────────────────────────────────────────────────────

    @Test
    fun `SqlVerdictSink пишет вердикт-скоринги, убирает из delta и логирует переход без PII`() {
        repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "100")))
        val c = TrackCandidate("t1", "100", TrackMetaFeatures())
        val result = DetectionResult(TrackState.CLEAN, blacklistHit = false, metaScore = 0.1, audioScore = null, finalScore = 0.1, reason = "meta_low_clean")

        SqlVerdictSink(repo, "det-v1").commit(c, result, TrackState.UNKNOWN, TrackState.CLEAN)

        assertTrue(SqlScanSource(db).newCandidates().isEmpty(), "после вердикта трек выходит из scan_delta")
        assertEquals("clean", db.query("SELECT verdict AS v FROM track WHERE yandex_track_id='t1'") { it.string("v") }.first())

        val audit = db.query(
            "SELECT action, from_state, to_state, detector_version FROM audit_log",
        ) { Quad(it.string("action")!!, it.string("from_state"), it.string("to_state"), it.string("detector_version")) }
        assertEquals(1, audit.size)
        assertEquals("detect", audit[0].a)
        assertEquals("unknown", audit[0].b)
        assertEquals("clean", audit[0].c)
        assertEquals("det-v1", audit[0].d)
    }

    // ── цепочка действий §5 ───────────────────────────────────────────────────

    @Test
    fun `ActionQueue отдаёт ai_confirmed, ActionSink продвигает и учитывает resume`() {
        repo.upsertDiscovered(listOf(DiscoveredTrack("t9", "100")))
        db.update("UPDATE track SET verdict='ai_confirmed' WHERE yandex_track_id='t9'")

        val q = SqlActionQueue(db)
        val sink = SqlActionSink(repo)

        val p0 = q.pending()
        assertEquals(1, p0.size)
        assertEquals(TrackState.AI_CONFIRMED, p0[0].currentState)

        sink.commit("t9", ActionOp.DISLIKE, TrackState.AI_CONFIRMED, TrackState.DISLIKED, changed = true)
        val p1 = q.pending()
        assertEquals(TrackState.DISLIKED, p1[0].currentState, "resume: уже дизлайкнут, currentState=DISLIKED")

        sink.commit("t9", ActionOp.ADD_TO_PLAYLIST, TrackState.DISLIKED, TrackState.MOVED_TO_PLAYLIST, changed = true)
        assertTrue(q.pending().isEmpty(), "moved_to_playlist — конец цепочки, из очереди уходит")
        assertEquals(2, scalarLong("SELECT COUNT(*) AS v FROM audit_log WHERE track_id=(SELECT id FROM track WHERE yandex_track_id='t9')"))
    }

    @Test
    fun `WHITELIST-откат ставит human_confirmed и чистит action_taken`() {
        repo.upsertDiscovered(listOf(DiscoveredTrack("t9", "100")))
        db.update("UPDATE track SET verdict='ai_confirmed', action_taken='disliked' WHERE yandex_track_id='t9'")

        SqlActionSink(repo).commit("t9", ActionOp.WHITELIST, TrackState.DISLIKED, TrackState.HUMAN_CONFIRMED, changed = true)

        val row = db.query("SELECT verdict, action_taken FROM track WHERE yandex_track_id='t9'") {
            it.string("verdict") to it.string("action_taken")
        }.first()
        assertEquals("human_confirmed", row.first)
        assertNull(row.second)
    }

    // ── архив ─────────────────────────────────────────────────────────────────

    @Test
    fun `ArchiveQueue отдаёт скачанные чистые, ArchiveSink отмечает archived`() {
        repo.upsertDiscovered(
            listOf(DiscoveredTrack("t5", "100", audioHash = "hh", codec = "flac", quality = "lossless")),
        )
        db.update("UPDATE track SET verdict='clean', phone_dl_status='downloaded', detector_version='det-v1' WHERE yandex_track_id='t5'")

        val aq = SqlArchiveQueue(db)
        val ap = aq.pending()
        assertEquals(1, ap.size)
        assertEquals("hh", ap[0].hash)
        assertEquals("flac", ap[0].codec)
        assertEquals("clean", ap[0].verdict)
        assertEquals(TrackState.DOWNLOADED, ap[0].currentState)

        val entry = ArchiveEntry("t5", "hh", "flac", "lossless", "flac/hh/hh.flac", null, "clean", "det-v1")
        SqlArchiveSink(repo).onArchived(entry, TrackState.DOWNLOADED)

        assertTrue(aq.pending().isEmpty(), "archive_status='archived' исключает из очереди")
        assertEquals("archived", db.query("SELECT archive_status AS v FROM track WHERE yandex_track_id='t5'") { it.string("v") }.first())
    }

    @Test
    fun `ArchiveSink pending оставляет трек в очереди для ретрая`() {
        repo.upsertDiscovered(
            listOf(DiscoveredTrack("t5", "100", audioHash = "hh", codec = "flac", quality = "lossless")),
        )
        db.update("UPDATE track SET verdict='clean', phone_dl_status='downloaded' WHERE yandex_track_id='t5'")

        SqlArchiveSink(repo).onPending("t5", "upload_unconfirmed")
        assertEquals(1, SqlArchiveQueue(db).pending().size, "pending → всё ещё в очереди (данные целы, §F6)")
    }

    // ── сквозной прогон поверх живой БД ───────────────────────────────────────

    @Test
    fun `полный CurationRun поверх реального SQLite — scan+действие+архив персистятся`() {
        // t1 — новый (сканируется → clean, meta пустой); t9 — ai_confirmed (дизлайк); t5 — downloaded (архив).
        repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "100")))
        repo.upsertDiscovered(listOf(DiscoveredTrack("t9", "100")))
        repo.upsertDiscovered(listOf(DiscoveredTrack("t5", "100", audioHash = "hh", codec = "flac", quality = "lossless")))
        db.update("UPDATE track SET verdict='ai_confirmed' WHERE yandex_track_id='t9'")
        db.update("UPDATE track SET verdict='clean', phone_dl_status='downloaded', detector_version='det-v1' WHERE yandex_track_id='t5'")

        val gate = SloplessGate.fromJson("""{"timestamp":"t","artists":[999]}""")
        val cascade = DetectionCascade(gate, MetadataScorer())
        val lib = RecordingLibrary()

        val run = CurationRun(
            flags = FeatureFlags(autoDislike = true, archive = true),
            scanSource = SqlScanSource(db),
            conveyor = ScanConveyor(cascade, SqlVerdictSink(repo, "det-v1")),
            schedule = RunSchedule(intervalMs = 6 * 60 * 60 * 1000L),
            actionQueue = SqlActionQueue(db),
            dispatcher = ActionDispatcher(ActionMode.DISLIKE_ONLY, lib, sink = SqlActionSink(repo), backup = BackupGuard { "b1" }),
            archiveQueue = SqlArchiveQueue(db),
            archiver = Archiver(MemBlob(), MemLocal(mutableMapOf("t5" to byteArrayOf(1, 2))), MemManifest(), sink = SqlArchiveSink(repo)),
        )

        val r = run.execute(dev.humanonly.schedule.DeviceState(metered = false, batteryLow = false))

        assertEquals(RunOutcome.SUCCESS, r.outcome)
        // scan: t1 стал clean и вышел из delta
        assertEquals("clean", db.query("SELECT verdict AS v FROM track WHERE yandex_track_id='t1'") { it.string("v") }.first())
        // action: t9 дизлайкнут реально (fake lib) и action_taken='disliked'
        assertTrue("t9" in lib.disliked)
        assertEquals("disliked", db.query("SELECT action_taken AS v FROM track WHERE yandex_track_id='t9'") { it.string("v") }.first())
        // archive: t5 заархивирован
        assertEquals("archived", db.query("SELECT archive_status AS v FROM track WHERE yandex_track_id='t5'") { it.string("v") }.first())
        // audit_log без PII-колонок — гарантировано схемой; проверим, что переходы записались
        assertTrue(scalarLong("SELECT COUNT(*) AS v FROM audit_log") >= 3)
    }

    // ── fakes для сквозного теста ─────────────────────────────────────────────

    private data class Quad(val a: String, val b: String?, val c: String?, val d: String?)

    private class RecordingLibrary : LibraryActions {
        val disliked = HashSet<String>()
        val liked = HashSet<String>()
        override fun dislike(trackId: String) = disliked.add(trackId)
        override fun undislike(trackId: String) = disliked.remove(trackId)
        override fun like(trackId: String) = liked.add(trackId)
        override fun addToPlaylist(trackId: String, playlistKind: String) = true
        override fun removeFromPlaylist(trackId: String, playlistKind: String) = true
    }

    private class MemBlob : BlobStore {
        val store = HashMap<String, ByteArray>()
        override fun exists(path: String) = store.containsKey(path)
        override fun put(path: String, content: ByteArray): Boolean { store[path] = content; return true }
        override fun get(path: String) = store[path]
    }

    private class MemLocal(val files: MutableMap<String, ByteArray>) : LocalStore {
        override fun read(trackId: String) = files[trackId]
        override fun delete(trackId: String) { files.remove(trackId) }
    }

    private class MemManifest : ManifestStore {
        var m = ArchiveManifest()
        override fun load() = m
        override fun save(manifest: ArchiveManifest) { m = manifest }
    }
}
