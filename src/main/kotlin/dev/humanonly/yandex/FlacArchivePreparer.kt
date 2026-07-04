package dev.humanonly.yandex

import java.security.MessageDigest

/**
 * Готовит расшифрованный блоб трека к архивации (§F6): приводит к сырому `.flac` и считает sha256
 * ИМЕННО от финального артефакта (ключ дедупа/путь в архиве — по хэшу того, что реально ляжет в S3).
 *
 *  - [ContainerFormat.RAW_FLAC] — блоб уже сырой `.flac`, отдаём как есть (passthrough), хэш от него.
 *  - [ContainerFormat.FLAC_MP4] — демукс ([Mp4FlacDemuxer]) + сборка ([FlacRemux]); байты FLAC не
 *    трогаются (без транскода), меняется только контейнер → хэш финального `.flac` иной, чем у `flac-mp4`.
 *  - [ContainerFormat.UNKNOWN] — не архивируем вслепую (нет уверенности в формате) → исключение,
 *    вызывающий трактует как pending (ретрай/ручной разбор), данные не портим.
 *
 * Чистая оркестрация поверх [FlacRemux] (spec-точная сборка) и [Mp4FlacDemuxer] (демукс за интерфейсом).
 * Тестируется на JVM с fake-демуксером; реальный Media3-демукс — устройство-зависимо, живой smoke позже.
 */
class FlacArchivePreparer(private val demuxer: Mp4FlacDemuxer) {

    /** Готовый к архивации `.flac` + его sha256. [remuxed]=true, если контейнер пересобран из flac-mp4. */
    data class Prepared(val flac: ByteArray, val sha256: String, val remuxed: Boolean)

    /**
     * Привести расшифрованный [decrypted] к `.flac`. Форма контейнера детектится [ContainerDetect];
     * UNKNOWN → [IllegalStateException] (вызывающий → pending, без записи в архив).
     */
    fun prepare(decrypted: ByteArray): Prepared = when (ContainerDetect.detect(decrypted)) {
        ContainerFormat.RAW_FLAC ->
            Prepared(decrypted, sha256Hex(decrypted), remuxed = false)

        ContainerFormat.FLAC_MP4 -> {
            val d = demuxer.demux(decrypted)
            val flac = FlacRemux.assemble(d.streamInfo, d.frames)
            Prepared(flac, sha256Hex(flac), remuxed = true)
        }

        ContainerFormat.UNKNOWN ->
            error("неизвестный контейнер блоба — не архивируем вслепую (${decrypted.size} байт)")
    }

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
