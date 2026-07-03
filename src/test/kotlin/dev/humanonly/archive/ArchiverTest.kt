package dev.humanonly.archive

import dev.humanonly.pipeline.StageListener
import dev.humanonly.state.ProcessingStage
import dev.humanonly.state.TrackState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Тесты архиватора (§F6, §9). Ключевые инварианты: idempotent upload по хэшу; локальный файл
 * удаляется ТОЛЬКО после подтверждённых upload+manifest; сбой → pending без потери данных;
 * переход downloaded→archived строго по §5. Всё на in-memory fakes — ни сети, ни диска.
 */
class ArchiverTest {

    /** In-memory S3: map path→bytes. putConfirmed=false → эмуляция неподтверждённой заливки. */
    private class FakeBlobStore(private val putConfirmed: Boolean = true) : BlobStore {
        val store = HashMap<String, ByteArray>()
        var putCalls = 0
        override fun exists(path: String): Boolean = store.containsKey(path)
        override fun put(path: String, content: ByteArray): Boolean {
            putCalls++
            if (!putConfirmed) return false
            store[path] = content; return true
        }
        override fun get(path: String): ByteArray? = store[path]
    }

    private class FakeLocalStore(seed: Map<String, ByteArray> = emptyMap()) : LocalStore {
        val files = HashMap<String, ByteArray>(seed)
        val deleted = mutableListOf<String>()
        override fun read(trackId: String): ByteArray? = files[trackId]
        override fun delete(trackId: String) { files.remove(trackId); deleted += trackId }
    }

    /** In-memory manifest. failSaveTimes раз бросает на save (эмуляция сбоя записи). */
    private class FakeManifestStore(private var failSaveTimes: Int = 0) : ManifestStore {
        var manifest = ArchiveManifest()
        var saves = 0
        override fun load(): ArchiveManifest = manifest
        override fun save(manifest: ArchiveManifest) {
            if (failSaveTimes > 0) { failSaveTimes--; throw RuntimeException("save failed") }
            this.manifest = manifest; saves++
        }
    }

    private class RecordingSink : ArchiveSink {
        data class Arch(val trackId: String, val from: TrackState)
        val archived = mutableListOf<Arch>()
        val pending = mutableListOf<Pair<String, String>>()
        override fun onArchived(entry: ArchiveEntry, from: TrackState) { archived += Arch(entry.trackId, from) }
        override fun onPending(trackId: String, reason: String) { pending += trackId to reason }
    }

    private fun candidate(
        id: String = "t1",
        hash: String = "abcdef0123456789",
        codec: String = "flac",
        quality: String = "lossless",
        verdict: String = "clean",
        detectorVersion: String = "det-v1",
        backupId: String? = "bkp-1",
        currentState: TrackState = TrackState.DOWNLOADED,
    ) = ArchiveCandidate(id, hash, codec, quality, verdict, detectorVersion, backupId, currentState)

    private val flac = byteArrayOf(0x66, 0x4C, 0x61, 0x43, 1, 2, 3) // "fLaC" + payload

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    fun `чистый FLAC заливается, manifest обновляется, локальный удаляется`() {
        val blobs = FakeBlobStore()
        val local = FakeLocalStore(mapOf("t1" to flac))
        val manifest = FakeManifestStore()
        val sink = RecordingSink()
        val summary = Archiver(blobs, local, manifest, sink).run(listOf(candidate()))

        assertEquals(1, summary.uploaded)
        assertEquals(1, summary.archived)
        val path = Archiver.archivePath("abcdef0123456789", "flac")
        assertTrue(blobs.store.containsKey(path))
        assertArrayEquals(flac, blobs.store[path])
        // manifest-запись с полями §F6
        val entry = manifest.manifest.get("t1")!!
        assertEquals("abcdef0123456789", entry.hash)
        assertEquals("clean", entry.verdict)
        assertEquals("det-v1", entry.detectorVersion)
        assertEquals("bkp-1", entry.backupId)
        assertEquals(path, entry.archivePath)
        // локальный удалён ПОСЛЕ подтверждения
        assertTrue(local.files["t1"] == null)
        assertEquals(listOf("t1"), local.deleted)
        // переход downloaded→archived зафиксирован
        assertEquals(listOf(RecordingSink.Arch("t1", TrackState.DOWNLOADED)), sink.archived)
    }

    // ── Идемпотентность по хэшу (§6.2) ─────────────────────────────────────────

    @Test
    fun `блоб уже в S3 → upload пропускается, но трек архивируется (dedup)`() {
        val path = Archiver.archivePath("abcdef0123456789", "flac")
        val blobs = FakeBlobStore().apply { store[path] = flac }
        val local = FakeLocalStore(mapOf("t1" to flac))
        val manifest = FakeManifestStore()
        val summary = Archiver(blobs, local, manifest).run(listOf(candidate()))

        assertEquals(0, summary.uploaded)
        assertEquals(1, summary.deduped)
        assertEquals(0, blobs.putCalls, "повторно не заливаем")
        assertTrue(manifest.manifest.get("t1") != null, "manifest всё равно фиксируется")
        assertTrue(local.files["t1"] == null, "локальный удаляется и при dedup")
    }

    // ── Сбои → pending, без потери данных ──────────────────────────────────────

    @Test
    fun `неподтверждённый upload → pending, локальный НЕ удаляется, manifest НЕ пишется`() {
        val blobs = FakeBlobStore(putConfirmed = false)
        val local = FakeLocalStore(mapOf("t1" to flac))
        val manifest = FakeManifestStore()
        val sink = RecordingSink()
        val summary = Archiver(blobs, local, manifest, sink).run(listOf(candidate()))

        assertEquals(1, summary.pending)
        assertEquals(0, summary.archived)
        assertArrayEquals(flac, local.files["t1"], "файл цел для ретрая")
        assertTrue(local.deleted.isEmpty())
        assertNull(manifest.manifest.get("t1"))
        assertEquals(listOf("t1" to Archiver.REASON_UPLOAD_UNCONFIRMED), sink.pending)
    }

    @Test
    fun `нет локального источника и нет блоба → pending no_local_source`() {
        val blobs = FakeBlobStore()
        val local = FakeLocalStore() // пусто
        val manifest = FakeManifestStore()
        val summary = Archiver(blobs, local, manifest).run(listOf(candidate()))
        assertEquals(1, summary.pending)
        assertEquals(0, blobs.putCalls)
        assertNull(manifest.manifest.get("t1"))
    }

    @Test
    fun `сбой записи manifest → pending, локальный НЕ удаляется (блоб залит)`() {
        val blobs = FakeBlobStore()
        val local = FakeLocalStore(mapOf("t1" to flac))
        val manifest = FakeManifestStore(failSaveTimes = 1)
        val sink = RecordingSink()
        val summary = Archiver(blobs, local, manifest, sink).run(listOf(candidate()))

        assertEquals(1, summary.pending)
        assertTrue(local.files["t1"] != null, "локальный цел — manifest не записан")
        assertTrue(local.deleted.isEmpty())
        assertEquals(listOf("t1" to Archiver.REASON_MANIFEST_ERROR), sink.pending)
        // блоб уже в S3 → ретрай пойдёт по dedup-ветке
        val path = Archiver.archivePath("abcdef0123456789", "flac")
        assertTrue(blobs.store.containsKey(path))
    }

    @Test
    fun `crash-safety — повторный прогон после сбоя manifest дозаписывает и удаляет локальный`() {
        val blobs = FakeBlobStore()
        val local = FakeLocalStore(mapOf("t1" to flac))
        val manifest = FakeManifestStore(failSaveTimes = 1)
        val archiver = Archiver(blobs, local, manifest)

        val first = archiver.run(listOf(candidate()))
        assertEquals(1, first.pending)

        // Ретрай: блоб на месте (dedup), save теперь работает.
        val second = archiver.run(listOf(candidate()))
        assertEquals(1, second.deduped)
        assertTrue(manifest.manifest.get("t1") != null)
        assertTrue(local.files["t1"] == null, "теперь локальный удалён")
    }

    // ── Хард-правило 10: недопустимое состояние ────────────────────────────────

    @Test
    fun `архивация из недопустимого состояния → pending invalid_state, без побочек`() {
        val blobs = FakeBlobStore()
        val local = FakeLocalStore(mapOf("t1" to flac))
        val manifest = FakeManifestStore()
        // suspected→archived нет ребра §5.
        val summary = Archiver(blobs, local, manifest)
            .run(listOf(candidate(currentState = TrackState.SUSPECTED)))
        assertEquals(1, summary.pending)
        assertEquals(0, blobs.putCalls)
        assertTrue(local.deleted.isEmpty())
        assertNull(manifest.manifest.get("t1"))
        assertEquals(Archiver.REASON_INVALID_STATE, summary.items[0].reason)
    }

    @Test
    fun `human_confirmed→downloaded→archived — из downloaded архивируем`() {
        // Проверяем именно ребро downloaded→archived (после clean|human_confirmed→downloaded).
        val blobs = FakeBlobStore()
        val local = FakeLocalStore(mapOf("t1" to flac))
        val summary = Archiver(blobs, local, FakeManifestStore())
            .run(listOf(candidate(currentState = TrackState.DOWNLOADED)))
        assertEquals(1, summary.archived)
    }

    // ── 404-fallback ───────────────────────────────────────────────────────────

    @Test
    fun `retrieve отдаёт блоб из архива по manifest`() {
        val blobs = FakeBlobStore()
        val local = FakeLocalStore(mapOf("t1" to flac))
        val manifest = FakeManifestStore()
        val archiver = Archiver(blobs, local, manifest)
        archiver.run(listOf(candidate()))

        assertArrayEquals(flac, archiver.retrieve("t1"))
        assertNull(archiver.retrieve("unknown"), "нет в архиве → null")
    }

    // ── Чекпоинты / агрегаты / путь / сериализация ─────────────────────────────

    @Test
    fun `чекпоинты ARCHIVING и DONE на успешный трек`() {
        val stages = object : StageListener {
            val events = mutableListOf<Pair<String, ProcessingStage>>()
            override fun onStage(trackId: String, stage: ProcessingStage) { events += trackId to stage }
        }
        Archiver(FakeBlobStore(), FakeLocalStore(mapOf("t1" to flac)), FakeManifestStore(),
            stageListener = stages).run(listOf(candidate()))
        assertEquals(ProcessingStage.ARCHIVING, stages.events.first().second)
        assertEquals(ProcessingStage.DONE, stages.events.last().second)
    }

    @Test
    fun `батч — счётчики суммируются, pending не роняет остальные`() {
        val path = Archiver.archivePath("aaaa1111", "flac")
        val blobs = FakeBlobStore().apply { store[path] = flac } // t-dedup уже в S3
        val local = FakeLocalStore(mapOf("t-up" to flac)) // t-pending без локального
        val manifest = FakeManifestStore()
        val summary = Archiver(blobs, local, manifest).run(
            listOf(
                candidate(id = "t-up", hash = "bbbb2222"),      // зальётся
                candidate(id = "t-dedup", hash = "aaaa1111"),   // dedup
                candidate(id = "t-pending", hash = "cccc3333"),  // нет локального → pending
            ),
        )
        assertEquals(1, summary.uploaded)
        assertEquals(1, summary.deduped)
        assertEquals(1, summary.pending)
        assertEquals(2, summary.archived)
    }

    @Test
    fun `archivePath детерминирован и шардирован по первым 2 hex`() {
        assertEquals("flac/ab/abcdef.flac", Archiver.archivePath("abcdef", "flac"))
        assertEquals("flac/ab/abcdef.flac", Archiver.archivePath("abcdef", "FLAC")) // регистр кодека нормализуется
    }

    @Test
    fun `manifest round-trip сохраняет поля и версию`() {
        val m = ArchiveManifest().upsert(
            ArchiveEntry("t1", "h", "flac", "lossless", "flac/h/h.flac", "bkp", "clean", "det-v1"),
        )
        val decoded = ArchiveSerialization.decode(ArchiveSerialization.encode(m))
        assertEquals(MANIFEST_VERSION, decoded.manifestVersion)
        assertEquals(m.get("t1"), decoded.get("t1"))
        assertFalse(ArchiveSerialization.encode(m).contains("title"), "PII не сериализуется")
    }
}
