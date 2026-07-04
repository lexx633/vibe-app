package dev.humanonly.db

import dev.humanonly.archive.ArchiveEntry
import dev.humanonly.archive.ArchiveManifest
import dev.humanonly.archive.Archiver
import dev.humanonly.archive.BlobStore
import dev.humanonly.archive.LocalStore
import dev.humanonly.archive.ManifestStore
import dev.humanonly.archive.WritableLocalStore
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
import dev.humanonly.pipeline.DownloadCandidate
import dev.humanonly.pipeline.DownloadStage
import dev.humanonly.pipeline.FetchedTrack
import dev.humanonly.pipeline.LibraryActions
import dev.humanonly.pipeline.ScanConveyor
import dev.humanonly.pipeline.TrackCandidate
import dev.humanonly.pipeline.TrackFetcher
import dev.humanonly.yandex.FlacArchivePreparer
import dev.humanonly.yandex.Mp4FlacDemuxer
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

    // ── скачивание §F6 (стадия 3) ─────────────────────────────────────────────

    @Test
    fun `SqlDownloadQueue отдаёт чистые нескачанные, SqlDownloadSink помечает downloaded`() {
        repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "100")))
        repo.upsertDiscovered(listOf(DiscoveredTrack("t2", "100")))
        db.update("UPDATE track SET verdict='clean', detector_version='det-v1' WHERE yandex_track_id='t1'")
        db.update("UPDATE track SET verdict='suspected' WHERE yandex_track_id='t2'") // не чистый → не качаем

        val q = SqlDownloadQueue(db)
        val p0 = q.pending()
        assertEquals(1, p0.size, "в очередь скачивания идут только clean/human_confirmed")
        assertEquals("t1", p0[0].trackId)
        assertEquals(TrackState.CLEAN, p0[0].currentState)
        assertEquals("det-v1", p0[0].detectorVersion)

        SqlDownloadSink(repo).onDownloaded("t1", from = TrackState.CLEAN, sha256 = "abc123", remuxed = false)

        assertTrue(q.pending().isEmpty(), "после downloaded трек уходит из очереди скачивания")
        val row = db.query(
            "SELECT phone_dl_status, audio_hash, codec FROM track WHERE yandex_track_id='t1'",
        ) { Triple(it.string("phone_dl_status"), it.string("audio_hash"), it.string("codec")) }.first()
        assertEquals("downloaded", row.first)
        assertEquals("abc123", row.second, "audio_hash = sha256 финального .flac (ключ дедупа архива)")
        assertEquals("flac", row.third)
        // и теперь он виден архив-очереди (тот же индекс, следующая стадия)
        assertEquals(1, SqlArchiveQueue(db).pending().size)
    }

    @Test
    fun `SqlDownloadSink onFailed не трогает phone_dl_status (ретрай) и пишет audit`() {
        repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "100")))
        db.update("UPDATE track SET verdict='clean' WHERE yandex_track_id='t1'")

        SqlDownloadSink(repo).onFailed("t1", "prepare_error")

        assertNull(db.query("SELECT phone_dl_status AS v FROM track WHERE yandex_track_id='t1'") { it.string("v") }.first())
        assertEquals(1, SqlDownloadQueue(db).pending().size, "провал → трек остаётся в очереди на ретрай (§6.1)")
        assertEquals(
            "download_failed:prepare_error",
            db.query("SELECT action AS v FROM audit_log") { it.string("v") }.first(),
        )
    }

    @Test
    fun `DownloadStage поверх живой БД скачивает чистый трек и переводит в downloaded`() {
        repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "100")))
        db.update("UPDATE track SET verdict='clean', detector_version='det-v1' WHERE yandex_track_id='t1'")

        val rawFlac = "fLaC".toByteArray() + ByteArray(64) { it.toByte() } // RAW_FLAC → passthrough, демукс не зовётся
        val stage = DownloadStage(
            fetcher = FakeFetcher(mapOf("t1" to rawFlac)),
            preparer = FlacArchivePreparer(ThrowingDemuxer),
            local = MemWritableLocal(),
            sink = SqlDownloadSink(repo),
        )

        val summary = stage.run(SqlDownloadQueue(db).pending())

        assertEquals(1, summary.downloaded)
        assertEquals(0, summary.failed)
        assertEquals("downloaded", db.query("SELECT phone_dl_status AS v FROM track WHERE yandex_track_id='t1'") { it.string("v") }.first())
        // audio_hash проставлен и не пуст → архив-очередь его увидит
        assertEquals(1, SqlArchiveQueue(db).pending().size)
        assertEquals(1, scalarLong("SELECT COUNT(*) AS v FROM audit_log WHERE action='download'"))
    }

    // ── сквозной прогон поверх живой БД ───────────────────────────────────────

    @Test
    fun `полный CurationRun поверх реального SQLite — scan+действие+скачивание+архив персистятся`() {
        // t1 — новый (сканируется → clean); t9 — ai_confirmed (дизлайк); t3 — clean, ещё НЕ скачан
        // (пройдёт стадию 3: download→downloaded→архив); t5 — уже downloaded (resume архивации).
        repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "100")))
        repo.upsertDiscovered(listOf(DiscoveredTrack("t9", "100")))
        repo.upsertDiscovered(listOf(DiscoveredTrack("t3", "100")))
        repo.upsertDiscovered(listOf(DiscoveredTrack("t5", "100", audioHash = "hh", codec = "flac", quality = "lossless")))
        db.update("UPDATE track SET verdict='ai_confirmed' WHERE yandex_track_id='t9'")
        db.update("UPDATE track SET verdict='clean', detector_version='det-v1' WHERE yandex_track_id='t3'")
        db.update("UPDATE track SET verdict='clean', phone_dl_status='downloaded', detector_version='det-v1' WHERE yandex_track_id='t5'")

        val gate = SloplessGate.fromJson("""{"timestamp":"t","artists":[999]}""")
        val cascade = DetectionCascade(gate, MetadataScorer())
        val lib = RecordingLibrary()
        // Общий локальный стор: t5 уже лежит (resume), t3 запишет DownloadStage → Archiver прочитает обоих.
        val local = MemWritableLocal(mutableMapOf("t5" to byteArrayOf(1, 2)))
        val rawFlac = "fLaC".toByteArray() + ByteArray(32) { it.toByte() }

        val run = CurationRun(
            flags = FeatureFlags(autoDislike = true, archive = true),
            scanSource = SqlScanSource(db),
            conveyor = ScanConveyor(cascade, SqlVerdictSink(repo, "det-v1")),
            schedule = RunSchedule(intervalMs = 6 * 60 * 60 * 1000L),
            actionQueue = SqlActionQueue(db),
            dispatcher = ActionDispatcher(ActionMode.DISLIKE_ONLY, lib, sink = SqlActionSink(repo), backup = BackupGuard { "b1" }),
            downloadQueue = SqlDownloadQueue(db),
            downloadStage = DownloadStage(
                fetcher = FakeFetcher(mapOf("t3" to rawFlac)),
                preparer = FlacArchivePreparer(ThrowingDemuxer),
                local = local,
                sink = SqlDownloadSink(repo),
            ),
            archiveQueue = SqlArchiveQueue(db),
            archiver = Archiver(MemBlob(), local, MemManifest(), sink = SqlArchiveSink(repo)),
        )

        val r = run.execute(dev.humanonly.schedule.DeviceState(metered = false, batteryLow = false))

        assertEquals(RunOutcome.SUCCESS, r.outcome)
        // scan: t1 стал clean и вышел из delta
        assertEquals("clean", db.query("SELECT verdict AS v FROM track WHERE yandex_track_id='t1'") { it.string("v") }.first())
        // action: t9 дизлайкнут реально (fake lib) и action_taken='disliked'
        assertTrue("t9" in lib.disliked)
        assertEquals("disliked", db.query("SELECT action_taken AS v FROM track WHERE yandex_track_id='t9'") { it.string("v") }.first())
        // download: t3 скачан (стадия 3) и затем заархивирован (стадия 4) в одном прогоне
        assertEquals("downloaded", db.query("SELECT phone_dl_status AS v FROM track WHERE yandex_track_id='t3'") { it.string("v") }.first())
        assertEquals("archived", db.query("SELECT archive_status AS v FROM track WHERE yandex_track_id='t3'") { it.string("v") }.first())
        // archive resume: t5 заархивирован
        assertEquals("archived", db.query("SELECT archive_status AS v FROM track WHERE yandex_track_id='t5'") { it.string("v") }.first())
        // audit_log без PII-колонок — гарантировано схемой; проверим, что переходы записались
        assertTrue(scalarLong("SELECT COUNT(*) AS v FROM audit_log") >= 4)
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

    /** Записываемый локальный стор в памяти: DownloadStage пишет, Archiver читает/удаляет. */
    private class MemWritableLocal(val files: MutableMap<String, ByteArray> = mutableMapOf()) : WritableLocalStore {
        override fun read(trackId: String) = files[trackId]
        override fun write(trackId: String, content: ByteArray) { files[trackId] = content }
        override fun delete(trackId: String) { files.remove(trackId) }
    }

    /** Fake-качалка: по trackId отдаёт заранее заготовленный расшифрованный блоб (без сети/акка). */
    private class FakeFetcher(private val blobs: Map<String, ByteArray>) : TrackFetcher {
        override fun fetch(trackId: String): FetchedTrack =
            FetchedTrack(decrypted = blobs[trackId] ?: error("нет блоба для $trackId"), quality = "lossless")
    }

    /** Демуксер, который не должен вызываться на RAW_FLAC пути (passthrough). Вызов = провал теста. */
    private object ThrowingDemuxer : Mp4FlacDemuxer {
        override fun demux(flacMp4: ByteArray) = error("демукс не должен вызываться на сыром .flac")
    }

    private class MemManifest : ManifestStore {
        var m = ArchiveManifest()
        override fun load() = m
        override fun save(manifest: ArchiveManifest) { m = manifest }
    }
}
