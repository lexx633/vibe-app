package dev.humanonly.yandex

/**
 * Детект контейнера расшифрованного аудио-блоба.
 *
 * ЯМ отдаёт lossless как `flac-mp4` (FLAC-in-MP4): байты начинаются с ISO-BMFF `ftyp`-box на
 * смещении 4 (magic `66 74 79 70` = "ftyp"), а НЕ с сырой `fLaC`-магии (lessons-learned 2026-07-03).
 * Возможен и вариант сырого `.flac` (`fLaC` на смещении 0).
 *
 * ЗДЕСЬ ТОЛЬКО ДЕТЕКТ. Демукс FLAC-стрима из MP4 — за интерфейсом в Android-слое
 * (Media3 Mp4Extractor/FlacExtractor), не в этой задаче.
 */
enum class ContainerFormat { FLAC_MP4, RAW_FLAC, UNKNOWN }

object ContainerDetect {
    private val FTYP = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
    private val FLAC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())

    fun detect(decoded: ByteArray): ContainerFormat = when {
        decoded.size >= 8 && regionEquals(decoded, 4, FTYP) -> ContainerFormat.FLAC_MP4
        decoded.size >= 4 && regionEquals(decoded, 0, FLAC) -> ContainerFormat.RAW_FLAC
        else -> ContainerFormat.UNKNOWN
    }

    private fun regionEquals(data: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        for (i in pattern.indices) if (data[offset + i] != pattern[i]) return false
        return true
    }
}
