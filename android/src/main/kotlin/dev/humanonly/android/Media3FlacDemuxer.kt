package dev.humanonly.android

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import dev.humanonly.yandex.FlacRemux
import dev.humanonly.yandex.Mp4FlacDemuxer
import java.io.ByteArrayOutputStream

/**
 * Android-реализация [Mp4FlacDemuxer] поверх Media3 `media3-extractor` (§F6-скачивание).
 * ffmpeg-kit МЁРТВ (retired, CLAUDE.md) → Media3 — единственный поддерживаемый путь демукса на Android.
 *
 * Разбирает in-memory `flac-mp4` через [Mp4Extractor]: читает FLAC-[Format] (→ STREAMINFO) и
 * покадровые сэмплы (→ конкатенация FLAC-фреймов). Транскода НЕТ — байты FLAC не трогаются, дальше
 * [FlacRemux.assemble] оборачивает их в сырой `.flac`.
 *
 * ХАРД-ПРАВИЛО 9: точная упаковка STREAMINFO в `Format.initializationData` Media3 НЕ утверждается по
 * памяти — нормализуется защитно ([normalizeStreamInfo]) и подтверждается ЖИВЫМ smoke на устройстве
 * перед первым реальным использованием (Robolectric с нативными декодерами ненадёжен, поэтому
 * юнит-тестом не покрываем — покрыта только чистая сборка [FlacRemux] на JVM golden-байтами).
 * Живой smoke 2026-07-04 показал форму «fLaC»+HEADER+тело (42 байта) — учтено в [normalizeStreamInfo].
 */
@OptIn(UnstableApi::class)
class Media3FlacDemuxer : Mp4FlacDemuxer {

    override fun demux(flacMp4: ByteArray): Mp4FlacDemuxer.Demuxed {
        val sink = FlacTrackSink()
        val output = SingleFlacExtractorOutput(sink)

        val dataSource = ByteArrayDataSource(flacMp4)
        val extractor = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED)
        extractor.init(output)

        try {
            var position = openAt(dataSource, 0L, flacMp4.size.toLong())
            var input = DefaultExtractorInput(dataSource, position, C.LENGTH_UNSET.toLong())
            val seekPosition = PositionHolder()
            loop@ while (true) {
                when (extractor.read(input, seekPosition)) {
                    Extractor.RESULT_END_OF_INPUT -> break@loop
                    Extractor.RESULT_SEEK -> {
                        dataSource.close()
                        position = openAt(dataSource, seekPosition.position, flacMp4.size.toLong())
                        input = DefaultExtractorInput(dataSource, position, C.LENGTH_UNSET.toLong())
                    }
                    else -> Unit // RESULT_CONTINUE — читаем дальше
                }
            }
        } finally {
            extractor.release()
            dataSource.close()
        }

        val format = sink.format ?: error("FLAC-дорожка не найдена в flac-mp4")
        val streamInfo = normalizeStreamInfo(format)
        return Mp4FlacDemuxer.Demuxed(streamInfo = streamInfo, frames = sink.frames())
    }

    private fun openAt(dataSource: ByteArrayDataSource, position: Long, length: Long): Long {
        dataSource.open(DataSpec.Builder().setUri(Uri.EMPTY).setPosition(position).build())
        return position
    }

    /**
     * Привести STREAMINFO из [Format.initializationData] к 34-байтному телу (RFC 9639). Media3 может
     * отдавать его как чистое тело (34 байта) или с 4-байтным METADATA_BLOCK_HEADER (38 байт) — оба
     * варианта нормализуем. Точная форма верифицируется живым smoke на устройстве (см. классдок).
     */
    private fun normalizeStreamInfo(format: Format): ByteArray {
        val data = format.initializationData.firstOrNull()
            ?: error("STREAMINFO отсутствует в Format.initializationData")
        when (data.size) {
            FlacRemux.STREAMINFO_SIZE -> Unit
            FlacRemux.STREAMINFO_SIZE + 4 -> validateBlockHeader(data, offset = 0)
            FlacRemux.STREAMINFO_SIZE + 8 -> {
                validateFlacMarker(data)
                validateBlockHeader(data, offset = 4)
            }
            else -> error("неожиданный размер STREAMINFO из Media3: ${data.size} байт")
        }
        return data.copyOfRange(data.size - FlacRemux.STREAMINFO_SIZE, data.size)
    }

    /** METADATA_BLOCK_HEADER на [offset]: тип блока 0 (STREAMINFO), длина тела = 34 (RFC 9639). */
    private fun validateBlockHeader(data: ByteArray, offset: Int) {
        val blockType = data[offset].toInt() and 0x7F // старший бит — last-metadata-block, игнорируем
        check(blockType == 0) { "ожидался STREAMINFO (тип 0), получен тип $blockType" }
        val len = ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
        check(len == FlacRemux.STREAMINFO_SIZE) { "длина STREAMINFO в заголовке = $len, ожидалось ${FlacRemux.STREAMINFO_SIZE}" }
    }

    /** «fLaC»-магия (0x66 0x4C 0x61 0x43) в начале блока. */
    private fun validateFlacMarker(data: ByteArray) {
        val ok = data[0].toInt() == 0x66 && data[1].toInt() == 0x4C &&
            data[2].toInt() == 0x61 && data[3].toInt() == 0x43
        check(ok) { "ожидалась fLaC-магия перед STREAMINFO" }
    }
}

/** [ExtractorOutput], отдающий все треки в один [FlacTrackSink]; берём только FLAC-аудио. */
@OptIn(UnstableApi::class)
private class SingleFlacExtractorOutput(private val sink: FlacTrackSink) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput = sink
    override fun endTracks() = Unit
    override fun seekMap(seekMap: SeekMap) = Unit
}

/**
 * [TrackOutput], накапливающий сырые FLAC-фреймы (конкатенация всех sampleData по порядку == все фреймы
 * дорожки) и запоминающий FLAC-[Format]. Не-FLAC форматы игнорируются (data игнорится, если не FLAC).
 */
@OptIn(UnstableApi::class)
private class FlacTrackSink : TrackOutput {
    var format: Format? = null
        private set
    private val buffer = ByteArrayOutputStream()

    fun frames(): ByteArray = buffer.toByteArray()

    override fun format(format: Format) {
        if (MimeTypes.AUDIO_FLAC.equals(format.sampleMimeType, ignoreCase = true)) {
            this.format = format
        }
    }

    override fun sampleData(
        input: androidx.media3.common.DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        val tmp = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(tmp, read, length - read)
            if (n == C.RESULT_END_OF_INPUT) {
                if (read == 0 && allowEndOfInput) return C.RESULT_END_OF_INPUT
                break
            }
            read += n
        }
        if (format != null) buffer.write(tmp, 0, read)
        return read
    }

    override fun sampleData(data: androidx.media3.common.util.ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (format == null) { data.skipBytes(length); return }
        val start = data.position
        val bytes = ByteArray(length)
        data.readBytes(bytes, 0, length)
        buffer.write(bytes, 0, length)
        // readBytes уже продвинул позицию на length от start
        check(data.position == start + length)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) = Unit // границы сэмплов не нужны: концы фреймов уже в порядке в buffer
}
