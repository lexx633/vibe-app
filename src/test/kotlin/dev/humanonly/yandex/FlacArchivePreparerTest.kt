package dev.humanonly.yandex

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/** Оркестрация подготовки к архивации: passthrough RAW_FLAC / ремукс FLAC_MP4 / отказ на UNKNOWN. */
class FlacArchivePreparerTest {

    /** Fake-демуксер: отдаёт заранее заданные STREAMINFO+фреймы (реальный Media3 — на устройстве). */
    private class FakeDemuxer(val streamInfo: ByteArray, val frames: ByteArray) : Mp4FlacDemuxer {
        var calls = 0
        override fun demux(flacMp4: ByteArray): Mp4FlacDemuxer.Demuxed {
            calls++
            return Mp4FlacDemuxer.Demuxed(streamInfo, frames)
        }
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Валидный сырой .flac: маркер + STREAMINFO-метаблок + немного «фреймов». */
    private fun rawFlac(): ByteArray = FlacRemux.assemble(ByteArray(34) { 7 }, byteArrayOf(9, 9, 9))

    /** Минимальный flac-mp4: `ftyp` на смещении 4 (детектится как FLAC_MP4). Содержимое не парсится fake'ом. */
    private fun flacMp4(): ByteArray = byteArrayOf(0, 0, 0, 0x18) + "ftypM4A ".toByteArray() + ByteArray(16)

    @Test
    fun `RAW_FLAC отдаётся passthrough, демукс не вызывается`() {
        val demuxer = FakeDemuxer(ByteArray(34), ByteArray(0))
        val raw = rawFlac()

        val prepared = FlacArchivePreparer(demuxer).prepare(raw)

        assertFalse(prepared.remuxed)
        assertArrayEquals(raw, prepared.flac)
        assertEquals(sha256(raw), prepared.sha256)
        assertEquals(0, demuxer.calls)
    }

    @Test
    fun `FLAC_MP4 ремуксится через демукс + сборку, хэш от финального flac`() {
        val si = ByteArray(34) { (it).toByte() }
        val frames = byteArrayOf(1, 2, 3, 4, 5)
        val demuxer = FakeDemuxer(si, frames)

        val prepared = FlacArchivePreparer(demuxer).prepare(flacMp4())

        assertTrue(prepared.remuxed)
        assertEquals(1, demuxer.calls)
        // финальный артефакт — сырой .flac, собранный из STREAMINFO+фреймов
        assertArrayEquals(FlacRemux.assemble(si, frames), prepared.flac)
        assertEquals(ContainerFormat.RAW_FLAC, ContainerDetect.detect(prepared.flac))
        // хэш посчитан ИМЕННО от .flac (а не от исходного flac-mp4)
        assertEquals(sha256(prepared.flac), prepared.sha256)
    }

    @Test
    fun `UNKNOWN контейнер отвергается (не архивируем вслепую)`() {
        val demuxer = FakeDemuxer(ByteArray(34), ByteArray(0))
        val garbage = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertThrows(IllegalStateException::class.java) {
            FlacArchivePreparer(demuxer).prepare(garbage)
        }
    }
}
