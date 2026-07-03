package dev.humanonly.yandex

import java.security.MessageDigest

/**
 * Оркестрация скачивания трека: get-file-info → скачивание блоба → AES-CTR дешифровка
 * ([TrackCrypto], уже проверен) → детект контейнера.
 *
 * Докачка/resume (lessons-learned 2026-07-03): CDN ЯМ отдаёт Range 206 byte-exact, а поток
 * AES-CTR seekable (counter = offset/16). Поэтому частично скачанное можно дополнить
 * `Range: bytes=offset-` и расшифровать хвост через [TrackCrypto.decryptFrom] — без повторной
 * загрузки всего файла. offset обязан быть кратен 16 (границе блока CTR).
 *
 * Byte-exact поведение подтверждено в фазе 0; здесь — оркестрация поверх верифицированных узлов.
 */
class TrackDownloader(
    private val client: YandexClient,
    private val transport: YandexTransport,
    private val rateLimiter: RateLimiter,
) {
    private companion object { const val BLOCK = 16 }

    data class Result(
        val decrypted: ByteArray,
        val container: ContainerFormat,
        val sha256: String,
    )

    /**
     * Полное скачивание: [DownloadInfo] уже получен (get-file-info дёргается вызывающим или через [client]).
     * Скачивает первый url, дешифрует целиком, детектит контейнер.
     */
    fun download(info: DownloadInfo): Result {
        require(info.urls.isNotEmpty()) { "downloadInfo.urls пуст" }
        rateLimiter.acquire()
        val enc = fetchBytes(info.urls.first())
        val dec = TrackCrypto.decrypt(enc, info.key)
        return Result(dec, ContainerDetect.detect(dec), sha256Hex(dec))
    }

    /**
     * Докачка: [partialDecrypted] — уже расшифрованный префикс (его длина = offset, кратен 16).
     * Тянем остаток `Range: bytes=offset-`, дешифруем хвост с блочного смещения и склеиваем.
     * expectedTotalSize (если известен) проверяется по факту.
     */
    fun resume(info: DownloadInfo, partialDecrypted: ByteArray, expectedTotalSize: Long? = null): Result {
        require(info.urls.isNotEmpty()) { "downloadInfo.urls пуст" }
        val offset = partialDecrypted.size.toLong()
        require(offset % BLOCK == 0L) { "offset докачки должен быть кратен $BLOCK, получено $offset" }

        rateLimiter.acquire()
        val encTail = fetchRange(info.urls.first(), offset, null)
        val decTail = TrackCrypto.decryptFrom(offset, encTail, info.key)

        val full = ByteArray(partialDecrypted.size + decTail.size)
        partialDecrypted.copyInto(full, 0)
        decTail.copyInto(full, partialDecrypted.size)

        if (expectedTotalSize != null) {
            require(full.size.toLong() == expectedTotalSize) {
                "размер после докачки ${full.size} != ожидаемого $expectedTotalSize"
            }
        }
        return Result(full, ContainerDetect.detect(full), sha256Hex(full))
    }

    private fun fetchBytes(url: String): ByteArray = try {
        transport.getBytes(url).also { rateLimiter.onSuccess() }
    } catch (e: YandexThrottleException) {
        rateLimiter.onThrottled(); throw e
    }

    private fun fetchRange(url: String, from: Long, to: Long?): ByteArray = try {
        transport.getRange(url, from, to).also { rateLimiter.onSuccess() }
    } catch (e: YandexThrottleException) {
        rateLimiter.onThrottled(); throw e
    }

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
