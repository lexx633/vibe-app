package dev.humanonly.yandex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Офлайн: детект контейнера по синтетическим заголовкам (без реального аудио).
 * flac-mp4 = `ftyp` на смещении 4 (lessons-learned 2026-07-03), raw = `fLaC` на 0.
 */
class ContainerDetectTest {

    @Test
    fun `flac-mp4 распознан по ftyp box`() {
        // size(4) + "ftyp" + brand
        val bytes = byteArrayOf(0, 0, 0, 0x1c.toByte()) +
            "ftyp".toByteArray(Charsets.US_ASCII) +
            "isom".toByteArray(Charsets.US_ASCII)
        assertEquals(ContainerFormat.FLAC_MP4, ContainerDetect.detect(bytes))
    }

    @Test
    fun `сырой fLaC распознан`() {
        val bytes = "fLaC".toByteArray(Charsets.US_ASCII) + ByteArray(16)
        assertEquals(ContainerFormat.RAW_FLAC, ContainerDetect.detect(bytes))
    }

    @Test
    fun `неизвестное содержимое — UNKNOWN`() {
        assertEquals(ContainerFormat.UNKNOWN, ContainerDetect.detect(ByteArray(8)))
        assertEquals(ContainerFormat.UNKNOWN, ContainerDetect.detect(byteArrayOf(1, 2)))
    }
}
