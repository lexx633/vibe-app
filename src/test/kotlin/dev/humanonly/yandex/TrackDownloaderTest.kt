package dev.humanonly.yandex

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * End-to-end на fake-транспорте, БЕЗ сети и токена. Приём как в TrackCryptoTest: шифруем известный
 * plaintext эталонным AES-128-CTR IV=0, fake-транспорт отдаёт зашифрованные байты, downloader
 * скачивает+дешифрует → должно равняться plaintext. Проверяем и путь докачки (Range + decryptFrom).
 * Rate-limiter НЕ выключен — прогоняется на виртуальном времени.
 */
class TrackDownloaderTest {

    private val keyHex = "000102030405060708090a0b0c0d0e0f" // фейковый ключ, 16 байт → AES-128
    private val url = "https://strm-fake.example.test/blob"

    private fun encryptFull(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(TrackCrypto.hexToBytes(keyHex), "AES"), IvParameterSpec(ByteArray(16)))
        return c.doFinal(plain)
    }

    /** Fake-транспорт: держит зашифрованный блоб, отдаёт целиком или Range-срез. JSON не нужен. */
    private inner class FakeTransport(private val enc: ByteArray) : YandexTransport {
        override fun getJson(url: String, params: Map<String, String>, headers: Map<String, String>): String =
            error("не используется в этом тесте")

        override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): String =
            error("не используется в этом тесте")

        override fun getBytes(url: String, headers: Map<String, String>): ByteArray = enc.copyOf()

        override fun getRange(url: String, from: Long, to: Long?, headers: Map<String, String>): ByteArray {
            val end = (to?.plus(1))?.toInt() ?: enc.size
            return enc.copyOfRange(from.toInt(), end)
        }
    }

    private fun downloader(enc: ByteArray): TrackDownloader {
        val transport = FakeTransport(enc)
        var clockNow = 0L
        val rl = RateLimiter(nowNanos = { clockNow }, sleeper = { clockNow += it })
        val config = YandexConfig("FAKE_TOKEN", baseUrl = "https://api.example.test")
        val client = YandexClient(config, transport, rl)
        return TrackDownloader(client, transport, rl)
    }

    private fun info() = DownloadInfo(codec = "flac-mp4", key = keyHex, urls = listOf(url))

    @Test
    fun `download скачивает и дешифрует в исходный plaintext`() {
        val plain = Random(1).nextBytes(8192)
        val enc = encryptFull(plain)
        val result = downloader(enc).download(info())
        assertArrayEquals(plain, result.decrypted)
    }

    @Test
    fun `resume докачивает хвост и склеивает в полный plaintext`() {
        val plain = Random(2).nextBytes(8192)
        val enc = encryptFull(plain)
        val dl = downloader(enc)

        // Симулируем частично скачанный (расшифрованный) префикс, кратный блоку.
        val offset = 4096
        val partial = plain.copyOfRange(0, offset)

        val result = dl.resume(info(), partial, expectedTotalSize = plain.size.toLong())
        assertArrayEquals(plain, result.decrypted)
    }

    @Test
    fun `sha256 результата стабилен и корректен`() {
        val plain = Random(3).nextBytes(1024)
        val enc = encryptFull(plain)
        val result = downloader(enc).download(info())
        // sha256 от plaintext, независимо посчитанный
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val expected = md.digest(plain).joinToString("") { "%02x".format(it) }
        assertEquals(expected, result.sha256)
    }
}
