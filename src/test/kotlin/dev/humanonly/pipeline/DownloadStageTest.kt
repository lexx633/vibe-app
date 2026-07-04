package dev.humanonly.pipeline

import dev.humanonly.archive.WritableLocalStore
import dev.humanonly.state.ProcessingStage
import dev.humanonly.state.TrackState
import dev.humanonly.yandex.FlacArchivePreparer
import dev.humanonly.yandex.FlacRemux
import dev.humanonly.yandex.Mp4FlacDemuxer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * Стадия скачивания [DownloadStage] на JVM-fakes: download→prepare→write local → [ArchiveCandidate].
 * Проверяем passthrough RAW_FLAC / ремукс FLAC_MP4, запись в локальный store, хэш от финального `.flac`,
 * гейт §5 (хард-правило 10), пофайловую изоляцию сбоев (fetch/prepare/write) и коммит в sink.
 */
class DownloadStageTest {

    /** Fake-демуксер: отдаёт заданные STREAMINFO+фреймы (реальный Media3 — на устройстве). */
    private class FakeDemuxer(val streamInfo: ByteArray, val frames: ByteArray) : Mp4FlacDemuxer {
        override fun demux(flacMp4: ByteArray) = Mp4FlacDemuxer.Demuxed(streamInfo, frames)
    }

    /** In-memory writable local store. */
    private class MemLocal : WritableLocalStore {
        val files = HashMap<String, ByteArray>()
        var failWriteFor: String? = null
        override fun read(trackId: String) = files[trackId]
        override fun delete(trackId: String) { files.remove(trackId) }
        override fun write(trackId: String, content: ByteArray) {
            if (trackId == failWriteFor) throw RuntimeException("disk full")
            files[trackId] = content
        }
    }

    /** Fake-fetcher: по trackId отдаёт заранее заданный расшифрованный блоб (или бросает). */
    private class FakeFetcher(val blobs: Map<String, ByteArray>, val throwFor: Set<String> = emptySet()) : TrackFetcher {
        var calls = 0
        override fun fetch(trackId: String): FetchedTrack {
            calls++
            if (trackId in throwFor) throw RuntimeException("ЯМ 503")
            return FetchedTrack(blobs.getValue(trackId), quality = "lossless")
        }
    }

    private class RecordingSink : DownloadSink {
        val downloaded = mutableListOf<String>()
        val failed = mutableMapOf<String, String>()
        override fun onDownloaded(trackId: String, from: TrackState, sha256: String, remuxed: Boolean) { downloaded += trackId }
        override fun onFailed(trackId: String, reason: String) { failed[trackId] = reason }
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Валидный сырой .flac. */
    private fun rawFlac(): ByteArray = FlacRemux.assemble(ByteArray(34) { 7 }, byteArrayOf(9, 9, 9))

    /** Минимальный flac-mp4 (ftyp на смещении 4 → детектится FLAC_MP4; содержимое парсит fake-демукс). */
    private fun flacMp4(): ByteArray = byteArrayOf(0, 0, 0, 0x18) + "ftypM4A ".toByteArray() + ByteArray(16)

    private fun preparer(demuxer: Mp4FlacDemuxer = FakeDemuxer(ByteArray(34), ByteArray(0))) =
        FlacArchivePreparer(demuxer)

    @Test
    fun `RAW_FLAC — скачан, записан локально, кандидат с хэшем финального flac`() {
        val raw = rawFlac()
        val fetcher = FakeFetcher(mapOf("t1" to raw))
        val local = MemLocal()
        val sink = RecordingSink()
        val stage = DownloadStage(fetcher, preparer(), local, sink)

        val summary = stage.run(listOf(DownloadCandidate("t1", verdict = "clean", detectorVersion = "v1")))

        assertEquals(1, summary.downloaded)
        assertEquals(0, summary.failed)
        val cand = summary.archiveCandidates.single()
        assertEquals("t1", cand.trackId)
        assertEquals(sha256(raw), cand.hash)
        assertEquals("flac", cand.codec)
        assertEquals(TrackState.DOWNLOADED, cand.currentState)
        assertArrayEquals(raw, local.files["t1"]) // локальный блоб записан
        assertTrue("t1" in sink.downloaded)
    }

    @Test
    fun `FLAC_MP4 — ремукс, локальный блоб = собранный flac, хэш от него`() {
        val si = ByteArray(34) { it.toByte() }
        val frames = byteArrayOf(1, 2, 3, 4, 5)
        val fetcher = FakeFetcher(mapOf("t1" to flacMp4()))
        val local = MemLocal()
        val stage = DownloadStage(fetcher, preparer(FakeDemuxer(si, frames)), local, DownloadSink.None)

        val summary = stage.run(listOf(DownloadCandidate("t1", verdict = "clean", detectorVersion = "v1")))

        val assembled = FlacRemux.assemble(si, frames)
        assertArrayEquals(assembled, local.files["t1"])
        assertEquals(sha256(assembled), summary.archiveCandidates.single().hash)
    }

    @Test
    fun `недопустимое состояние (ai_confirmed) — не качаем, не пишем (хард-правило 10)`() {
        val fetcher = FakeFetcher(mapOf("t1" to rawFlac()))
        val local = MemLocal()
        val stage = DownloadStage(fetcher, preparer(), local, DownloadSink.None)

        val summary = stage.run(listOf(
            DownloadCandidate("t1", verdict = "clean", detectorVersion = "v1", currentState = TrackState.AI_CONFIRMED),
        ))

        assertEquals(1, summary.failed)
        assertEquals(DownloadStage.REASON_INVALID_STATE, summary.items.single().reason)
        assertEquals(0, fetcher.calls) // до сети не дошли
        assertTrue(local.files.isEmpty())
    }

    @Test
    fun `human_confirmed → downloaded допустим (§5)`() {
        val fetcher = FakeFetcher(mapOf("t1" to rawFlac()))
        val stage = DownloadStage(fetcher, preparer(), MemLocal(), DownloadSink.None)
        val summary = stage.run(listOf(
            DownloadCandidate("t1", verdict = "clean", detectorVersion = "v1", currentState = TrackState.HUMAN_CONFIRMED),
        ))
        assertEquals(1, summary.downloaded)
    }

    @Test
    fun `сбой fetch — трек в failed, остальные не затронуты`() {
        val fetcher = FakeFetcher(mapOf("ok" to rawFlac()), throwFor = setOf("bad"))
        val local = MemLocal()
        val sink = RecordingSink()
        val stage = DownloadStage(fetcher, preparer(), local, sink)

        val summary = stage.run(listOf(
            DownloadCandidate("bad", verdict = "clean", detectorVersion = "v1"),
            DownloadCandidate("ok", verdict = "clean", detectorVersion = "v1"),
        ))

        assertEquals(1, summary.downloaded)
        assertEquals(1, summary.failed)
        assertEquals(DownloadStage.REASON_FETCH_ERROR, sink.failed["bad"])
        assertNotNull(local.files["ok"])
        assertNull(local.files["bad"])
    }

    @Test
    fun `неизвестный контейнер — prepare_error, локально не пишем`() {
        val garbage = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val fetcher = FakeFetcher(mapOf("t1" to garbage))
        val local = MemLocal()
        val stage = DownloadStage(fetcher, preparer(), local, DownloadSink.None)

        val summary = stage.run(listOf(DownloadCandidate("t1", verdict = "clean", detectorVersion = "v1")))

        assertEquals(1, summary.failed)
        assertEquals(DownloadStage.REASON_PREPARE_ERROR, summary.items.single().reason)
        assertTrue(local.files.isEmpty())
    }

    @Test
    fun `сбой записи локального — local_write_error, кандидат не эмитится`() {
        val fetcher = FakeFetcher(mapOf("t1" to rawFlac()))
        val local = MemLocal().apply { failWriteFor = "t1" }
        val stage = DownloadStage(fetcher, preparer(), local, DownloadSink.None)

        val summary = stage.run(listOf(DownloadCandidate("t1", verdict = "clean", detectorVersion = "v1")))

        assertEquals(1, summary.failed)
        assertEquals(DownloadStage.REASON_LOCAL_WRITE_ERROR, summary.items.single().reason)
        assertTrue(summary.archiveCandidates.isEmpty())
    }
}
