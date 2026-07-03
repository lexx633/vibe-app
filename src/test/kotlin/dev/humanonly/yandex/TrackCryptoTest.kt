package dev.humanonly.yandex

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Self-contained: шифруем эталонным AES-128-CTR IV=0 и проверяем, что [TrackCrypto]
 * даёт тот же plaintext — включая дешифровку срезов с произвольного блочного смещения
 * (seekability CTR, нужна для докачки Range-запросов). Секретов и сети не требует.
 */
class TrackCryptoTest {

    private val keyHex = "000102030405060708090a0b0c0d0e0f" // 16 байт → AES-128
    private val block = 16

    private fun encryptFull(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(TrackCrypto.hexToBytes(keyHex), "AES"), IvParameterSpec(ByteArray(block)))
        return c.doFinal(plain)
    }

    @Test
    fun `decrypt восстанавливает исходные байты`() {
        val plain = Random(42).nextBytes(5000)
        assertArrayEquals(plain, TrackCrypto.decrypt(encryptFull(plain), keyHex))
    }

    @Test
    fun `decryptFrom дешифрует срез с блочного смещения`() {
        val plain = Random(7).nextBytes(4096)
        val cipher = encryptFull(plain)
        val offset = 1024L // кратно 16
        val slice = cipher.copyOfRange(offset.toInt(), cipher.size)
        val decoded = TrackCrypto.decryptFrom(offset, slice, keyHex)
        assertArrayEquals(plain.copyOfRange(offset.toInt(), plain.size), decoded)
    }

    @Test
    fun `decryptFrom требует кратности смещения размеру блока`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackCrypto.decryptFrom(1000, ByteArray(16), keyHex)
        }
    }
}
