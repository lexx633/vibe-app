package dev.humanonly.yandex

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Golden-байты сборки `.flac` (RFC 9639 + xiph isoflac.txt, офлайн, без Media3).
 * Проверяем ТОЧНУЮ раскладку: маркер + METADATA_BLOCK_HEADER(STREAMINFO, last=1, len=34) + тело + фреймы.
 */
class FlacRemuxTest {

    /** 34-байтный STREAMINFO с распознаваемым паттерном (значения не важны — важна раскладка). */
    private fun streamInfo(): ByteArray = ByteArray(FlacRemux.STREAMINFO_SIZE) { (it + 1).toByte() }

    @Test
    fun `assemble кладёт маркер, заголовок STREAMINFO и фреймы в правильном порядке`() {
        val si = streamInfo()
        val frames = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())

        val flac = FlacRemux.assemble(si, frames)

        // маркер fLaC = 0x66 0x4C 0x61 0x43
        assertArrayEquals(byteArrayOf(0x66, 0x4C, 0x61, 0x43), flac.copyOfRange(0, 4))
        // METADATA_BLOCK_HEADER: last=1 + type=0 => 0x80; длина 34 = 0x00 0x00 0x22
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22), flac.copyOfRange(4, 8))
        // тело STREAMINFO (34 байта) — байт-в-байт
        assertArrayEquals(si, flac.copyOfRange(8, 8 + 34))
        // фреймы — сразу после STREAMINFO, без изменений
        assertArrayEquals(frames, flac.copyOfRange(8 + 34, flac.size))
        assertEquals(4 + 4 + 34 + frames.size, flac.size)
    }

    @Test
    fun `результат сборки детектится как RAW_FLAC`() {
        val flac = FlacRemux.assemble(streamInfo(), byteArrayOf(1, 2, 3))
        assertEquals(ContainerFormat.RAW_FLAC, ContainerDetect.detect(flac))
    }

    @Test
    fun `сборка без фреймов даёт только маркер + метаблок STREAMINFO`() {
        val flac = FlacRemux.assemble(streamInfo(), ByteArray(0))
        assertEquals(4 + 4 + 34, flac.size)
    }

    @Test
    fun `STREAMINFO не 34 байта отвергается`() {
        assertThrows(IllegalArgumentException::class.java) {
            FlacRemux.assemble(ByteArray(33), ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlacRemux.assemble(ByteArray(35), ByteArray(0))
        }
    }
}
