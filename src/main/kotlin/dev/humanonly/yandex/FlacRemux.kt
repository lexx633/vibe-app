package dev.humanonly.yandex

/**
 * Ремукс FLAC-in-MP4 → сырой `.flac` (§F6-скачивание). ЯМ отдаёт lossless как `flac-mp4`
 * (FLAC-стрим внутри ISO-BMFF, [ContainerDetect] это детектит), а в архив нужен стандартный `.flac`.
 *
 * ЧИСТАЯ сборка контейнера — байты FLAC (STREAMINFO и фреймы) НЕ трогаем, только оборачиваем в
 * нативный FLAC-контейнер. Никакого транскода/перекодирования → бит-в-бит тот же звук.
 *
 * Формат выверен по эталону (хард-правило 9):
 *  - RFC 9639 (FLAC): маркер `fLaC` = 0x66 0x4C 0x61 0x43; METADATA_BLOCK_HEADER = 4 байта
 *    (бит0 — last-metadata-block flag, биты1..7 — BLOCK_TYPE, биты8..31 — big-endian длина тела
 *    без заголовка); STREAMINFO = BLOCK_TYPE 0, тело ровно 34 байта.
 *  - xiph doc/isoflac.txt (FLAC-in-ISOBMFF): сэмплы в `mdat` — сырые FLAC-фреймы БЕЗ маркера и
 *    метаблоков; чтобы восстановить `.flac`, демуксер обязан приписать `fLaC` + метаблоки (STREAMINFO
 *    первым) + конкатенацию фреймов.
 *
 * Демукс (разбор ISO-BMFF, извлечение STREAMINFO+фреймов) — за [Mp4FlacDemuxer] (в Android-слое через
 * Media3, устройство-зависимо). Здесь — только spec-точная сборка, покрытая golden-байтами на JVM.
 */
object FlacRemux {

    /** Маркер потока `fLaC` (RFC 9639 §5). */
    private val FLAC_MARKER = byteArrayOf(0x66, 0x4C, 0x61, 0x43)

    /** Размер тела STREAMINFO в байтах (RFC 9639). */
    const val STREAMINFO_SIZE = 34

    /** BLOCK_TYPE STREAMINFO (RFC 9639): 0. */
    private const val BLOCK_TYPE_STREAMINFO = 0

    /** Флаг «последний метаблок» в старшем бите первого байта METADATA_BLOCK_HEADER. */
    private const val LAST_BLOCK_FLAG = 0x80

    /**
     * Собрать сырой `.flac` из 34-байтного тела STREAMINFO и конкатенации FLAC-фреймов.
     *
     * Выход: `fLaC` + METADATA_BLOCK_HEADER(last=1, type=STREAMINFO, len=34) + streamInfo + frames.
     * Единственный метаблок — STREAMINFO (last-flag=1), достаточный для валидного декодируемого файла
     * (VORBIS_COMMENT/SEEKTABLE не обязательны). MD5 внутри STREAMINFO может быть нулевым («unknown»,
     * RFC 9639) — мы его не пересчитываем, отдаём как есть из исходного контейнера.
     *
     * @param streamInfo ровно 34 байта тела STREAMINFO (без 4-байтного METADATA_BLOCK_HEADER).
     * @param frames     конкатенация FLAC-аудио-фреймов из `mdat` (self-delimiting, порядок сохранён).
     */
    fun assemble(streamInfo: ByteArray, frames: ByteArray): ByteArray {
        require(streamInfo.size == STREAMINFO_SIZE) {
            "STREAMINFO должен быть ровно $STREAMINFO_SIZE байт, получено ${streamInfo.size}"
        }
        val header = metadataBlockHeader(BLOCK_TYPE_STREAMINFO, last = true, length = STREAMINFO_SIZE)
        val out = ByteArray(FLAC_MARKER.size + header.size + streamInfo.size + frames.size)
        var pos = 0
        FLAC_MARKER.copyInto(out, pos); pos += FLAC_MARKER.size
        header.copyInto(out, pos); pos += header.size
        streamInfo.copyInto(out, pos); pos += streamInfo.size
        frames.copyInto(out, pos)
        return out
    }

    /**
     * 4-байтный METADATA_BLOCK_HEADER (RFC 9639): [last(1 бит)][type(7 бит)][length(24 бита, BE)].
     * length — размер тела метаблока БЕЗ этого заголовка.
     */
    private fun metadataBlockHeader(type: Int, last: Boolean, length: Int): ByteArray {
        require(type in 0..126) { "BLOCK_TYPE вне диапазона 0..126: $type" }
        require(length in 0..0xFF_FF_FF) { "длина метаблока не влезает в 24 бита: $length" }
        val firstByte = (if (last) LAST_BLOCK_FLAG else 0) or (type and 0x7F)
        return byteArrayOf(
            firstByte.toByte(),
            ((length ushr 16) and 0xFF).toByte(),
            ((length ushr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte(),
        )
    }
}
