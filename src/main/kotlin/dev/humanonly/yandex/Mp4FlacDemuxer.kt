package dev.humanonly.yandex

/**
 * Извлечение FLAC-полезной нагрузки из контейнера `flac-mp4` (ISO-BMFF). Возвращает то, что нужно
 * [FlacRemux.assemble] для сборки сырого `.flac`: тело STREAMINFO (34 байта) + конкатенацию FLAC-фреймов.
 *
 * Реализация — в Android-слое поверх Media3 (`media3-extractor`): разбор боксов, `dfLa`/STREAMINFO и
 * покадровая нарезка сэмплов по sample-table (устройство/эмулятор-зависимо). Контракт (по xiph
 * doc/isoflac.txt, хард-правило 9):
 *  - [Demuxed.streamInfo] — РОВНО 34 байта тела METADATA_BLOCK_STREAMINFO (без 4-байтного заголовка),
 *    как лежит в `FLACSpecificBox('dfLa')`.
 *  - [Demuxed.frames] — сырые FLAC-аудио-фреймы из `mdat` в исходном порядке, без маркера/метаблоков.
 *
 * Границы валидируются в [FlacArchivePreparer]/[FlacRemux]; здесь — только форма контракта.
 */
interface Mp4FlacDemuxer {

    /** STREAMINFO (34 байта) + конкатенация FLAC-фреймов, извлечённые из `flac-mp4`. */
    data class Demuxed(val streamInfo: ByteArray, val frames: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Demuxed) return false
            return streamInfo.contentEquals(other.streamInfo) && frames.contentEquals(other.frames)
        }

        override fun hashCode(): Int = 31 * streamInfo.contentHashCode() + frames.contentHashCode()
    }

    /** Разобрать блоб `flac-mp4` (расшифрованный) в STREAMINFO + фреймы. */
    fun demux(flacMp4: ByteArray): Demuxed
}
