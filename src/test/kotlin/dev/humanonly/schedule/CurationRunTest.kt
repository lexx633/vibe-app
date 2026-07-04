package dev.humanonly.schedule

import dev.humanonly.archive.ArchiveCandidate
import dev.humanonly.archive.ArchiveManifest
import dev.humanonly.archive.Archiver
import dev.humanonly.archive.BlobStore
import dev.humanonly.archive.LocalStore
import dev.humanonly.archive.ManifestStore
import dev.humanonly.config.FeatureFlags
import dev.humanonly.detector.DetectionCascade
import dev.humanonly.detector.MetadataScorer
import dev.humanonly.detector.SloplessGate
import dev.humanonly.detector.TrackMetaFeatures
import dev.humanonly.archive.WritableLocalStore
import dev.humanonly.pipeline.ActionCandidate
import dev.humanonly.pipeline.ActionDispatcher
import dev.humanonly.pipeline.ActionMode
import dev.humanonly.pipeline.BackupGuard
import dev.humanonly.pipeline.DownloadCandidate
import dev.humanonly.pipeline.DownloadStage
import dev.humanonly.pipeline.FetchedTrack
import dev.humanonly.pipeline.LibraryActions
import dev.humanonly.pipeline.ScanConveyor
import dev.humanonly.pipeline.TrackCandidate
import dev.humanonly.pipeline.TrackFetcher
import dev.humanonly.pipeline.VerdictSink
import dev.humanonly.yandex.FlacArchivePreparer
import dev.humanonly.yandex.FlacRemux
import dev.humanonly.yandex.Mp4FlacDemuxer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Тесты оркестратора планового прогона (§4.1, §7). Прогоняют РЕАЛЬНЫЕ стадии (конвейер/диспетчер/архиватор)
 * над in-memory fakes: композиция и гейтинг флагами/бэкапом/ограничениями — без Android/сети/акка.
 */
class CurationRunTest {

    // ── fakes стадий ─────────────────────────────────────────────────────────

    private fun gate() = SloplessGate.fromJson("""{"timestamp":"t","artists":[100]}""")
    private fun cascade() = DetectionCascade(gate(), MetadataScorer())
    private fun conveyor() = ScanConveyor(cascade(), VerdictSink { _, _, _, _ -> })

    private val schedule = RunSchedule(intervalMs = 6 * 60 * 60 * 1000L)

    private class FakeLibrary : LibraryActions {
        val disliked = HashSet<String>()
        val liked = HashSet<String>()
        override fun dislike(trackId: String) = disliked.add(trackId)
        override fun undislike(trackId: String) = disliked.remove(trackId)
        override fun like(trackId: String) = liked.add(trackId)
        override fun unlike(trackId: String) = liked.remove(trackId)
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
    /** Writable local, общий для DownloadStage (write) и Archiver (read/delete). */
    private class MemWritableLocal : WritableLocalStore {
        val files = HashMap<String, ByteArray>()
        override fun read(trackId: String) = files[trackId]
        override fun delete(trackId: String) { files.remove(trackId) }
        override fun write(trackId: String, content: ByteArray) { files[trackId] = content }
    }

    private fun scanOf(vararg ids: String) = ScanSource {
        ids.map { TrackCandidate(it, artistId = "999", meta = TrackMetaFeatures()) }
    }

    // ── happy path: все стадии ───────────────────────────────────────────────

    @Test
    fun `полный прогон — scan+действия+архив, SUCCESS со сводками`() {
        val lib = FakeLibrary()
        val dispatcher = ActionDispatcher(ActionMode.DISLIKE_ONLY, lib, backup = BackupGuard { "b1" })
        val archiver = Archiver(MemBlob(), MemLocal(mutableMapOf("t9" to byteArrayOf(1, 2))), MemManifest())

        val run = CurationRun(
            flags = FeatureFlags(autoDislike = true, archive = true),
            scanSource = scanOf("t1", "t2"),
            conveyor = conveyor(),
            schedule = schedule,
            actionQueue = { listOf(ActionCandidate("t9")) },
            dispatcher = dispatcher,
            archiveQueue = { listOf(ArchiveCandidate("t9", "hh", "flac", "lossless", "clean", "det-v1")) },
            archiver = archiver,
        )
        val r = run.execute(DeviceState(metered = false, batteryLow = false))

        assertEquals(RunOutcome.SUCCESS, r.outcome)
        assertEquals(2, r.conveyor!!.scanned)
        assertTrue(r.action!!.executed)
        assertEquals(1, r.action!!.applied)
        assertTrue("t9" in lib.disliked)
        assertEquals(1, r.archive!!.uploaded)
    }

    // ── проводка download→prepare→archive ────────────────────────────────────

    @Test
    fun `download стадия скачивает чистый трек и он архивируется в тот же прогон`() {
        val raw = FlacRemux.assemble(ByteArray(34) { 5 }, byteArrayOf(1, 2, 3))
        val fetcher = TrackFetcher { FetchedTrack(raw, quality = "lossless") }
        val preparer = FlacArchivePreparer(object : Mp4FlacDemuxer { override fun demux(flacMp4: ByteArray) = Mp4FlacDemuxer.Demuxed(ByteArray(34), ByteArray(0)) })
        val local = MemWritableLocal()
        val downloadStage = DownloadStage(fetcher, preparer, local)
        val blobs = MemBlob()
        val archiver = Archiver(blobs, local, MemManifest())

        val run = CurationRun(
            flags = FeatureFlags(archive = true),
            scanSource = scanOf(),
            conveyor = conveyor(),
            schedule = schedule,
            downloadQueue = { listOf(DownloadCandidate("t1", verdict = "clean", detectorVersion = "det-v1")) },
            downloadStage = downloadStage,
            archiver = archiver,
        )
        val r = run.execute(DeviceState(metered = false, batteryLow = false))

        assertEquals(RunOutcome.SUCCESS, r.outcome)
        assertEquals(1, r.download!!.downloaded)
        assertEquals(1, r.archive!!.uploaded)         // свежескачанный трек заархивирован
        assertTrue(local.files.isEmpty(), "локальный блоб удалён после подтверждённого архива")
        assertEquals(1, blobs.store.size)              // блоб реально лёг в архив
    }

    @Test
    fun `archive выключен — download стадия не запускается`() {
        val fetcher = TrackFetcher { error("не должно вызываться") }
        val preparer = FlacArchivePreparer(object : Mp4FlacDemuxer { override fun demux(flacMp4: ByteArray) = Mp4FlacDemuxer.Demuxed(ByteArray(34), ByteArray(0)) })
        val run = CurationRun(
            flags = FeatureFlags(archive = false),
            scanSource = scanOf(),
            conveyor = conveyor(),
            schedule = schedule,
            downloadQueue = { listOf(DownloadCandidate("t1", verdict = "clean", detectorVersion = "v1")) },
            downloadStage = DownloadStage(fetcher, preparer, MemWritableLocal()),
        )
        val r = run.execute()
        assertEquals(RunOutcome.SUCCESS, r.outcome)
        assertNull(r.download)
    }

    // ── ограничения устройства ───────────────────────────────────────────────

    @Test
    fun `лимитная сеть → RETRY без побочек (стадии не запускались)`() {
        val lib = FakeLibrary()
        val run = CurationRun(
            flags = FeatureFlags(autoDislike = true),
            scanSource = scanOf("t1"),
            conveyor = conveyor(),
            schedule = schedule, // requiresUnmetered по умолчанию
            actionQueue = { listOf(ActionCandidate("t9")) },
            dispatcher = ActionDispatcher(ActionMode.DISLIKE_ONLY, lib, backup = BackupGuard { "b1" }),
        )
        val r = run.execute(DeviceState(metered = true))

        assertEquals(RunOutcome.RETRY, r.outcome)
        assertEquals(CurationRun.REASON_CONSTRAINTS_NOT_MET, r.skippedReason)
        assertNull(r.conveyor)
        assertTrue(lib.disliked.isEmpty(), "деструктив не выполнялся")
    }

    // ── классификация ошибок ─────────────────────────────────────────────────

    @Test
    fun `TransientException стадии → RETRY`() {
        val run = CurationRun(
            flags = FeatureFlags(),
            scanSource = { throw TransientException("ЯМ 503") },
            conveyor = conveyor(),
            schedule = schedule,
        )
        val r = run.execute()
        assertEquals(RunOutcome.RETRY, r.outcome)
        assertTrue(r.error!!.contains("503"))
    }

    @Test
    fun `прочее исключение → FAILURE (не долбим)`() {
        val run = CurationRun(
            flags = FeatureFlags(),
            scanSource = { throw IllegalStateException("битый конфиг") },
            conveyor = conveyor(),
            schedule = schedule,
        )
        assertEquals(RunOutcome.FAILURE, run.execute().outcome)
    }

    // ── гейтинг флагами / бэкапом ────────────────────────────────────────────

    @Test
    fun `autoDislike выключен — действия не выполняются даже при очереди`() {
        val lib = FakeLibrary()
        val run = CurationRun(
            flags = FeatureFlags(autoDislike = false),
            scanSource = scanOf("t1"),
            conveyor = conveyor(),
            schedule = schedule,
            actionQueue = { listOf(ActionCandidate("t9")) },
            dispatcher = ActionDispatcher(ActionMode.DISLIKE_ONLY, lib, backup = BackupGuard { "b1" }),
        )
        val r = run.execute()
        assertEquals(RunOutcome.SUCCESS, r.outcome)
        assertNull(r.action)
        assertTrue(lib.disliked.isEmpty())
    }

    @Test
    fun `autoDislike включён, но нет бэкапа — диспетчер отказывает, деструктива нет, прогон SUCCESS`() {
        val lib = FakeLibrary()
        val run = CurationRun(
            flags = FeatureFlags(autoDislike = true),
            scanSource = scanOf("t1"),
            conveyor = conveyor(),
            schedule = schedule,
            actionQueue = { listOf(ActionCandidate("t9")) },
            dispatcher = ActionDispatcher(ActionMode.DISLIKE_ONLY, lib, backup = BackupGuard.None),
        )
        val r = run.execute()
        assertEquals(RunOutcome.SUCCESS, r.outcome)
        assertNotNull(r.action)
        assertTrue(!r.action!!.executed)
        assertEquals(ActionDispatcher.REFUSED_NO_BACKUP, r.action!!.refusedReason)
        assertTrue(lib.disliked.isEmpty(), "без бэкапа дизлайк не ставится (хард-правило 5)")
    }

    @Test
    fun `детектор-флаги выключены — scan пропущен`() {
        val run = CurationRun(
            flags = FeatureFlags(detectorMetadata = false, detectorAudio = false, archive = false),
            scanSource = scanOf("t1", "t2"),
            conveyor = conveyor(),
            schedule = schedule,
        )
        val r = run.execute()
        assertEquals(RunOutcome.SUCCESS, r.outcome)
        assertNull(r.conveyor)
    }

    @Test
    fun `пустые очереди — стадии null, но SUCCESS`() {
        val run = CurationRun(
            flags = FeatureFlags(autoDislike = true, archive = true),
            scanSource = scanOf(),
            conveyor = conveyor(),
            schedule = schedule,
            actionQueue = ActionQueue.Empty,
            dispatcher = ActionDispatcher(ActionMode.DISLIKE_ONLY, FakeLibrary(), backup = BackupGuard { "b1" }),
            archiveQueue = ArchiveQueue.Empty,
            archiver = Archiver(MemBlob(), MemLocal(mutableMapOf()), MemManifest()),
        )
        val r = run.execute()
        assertEquals(RunOutcome.SUCCESS, r.outcome)
        assertEquals(0, r.conveyor!!.scanned)
        assertNull(r.action)
        assertNull(r.archive)
    }
}
