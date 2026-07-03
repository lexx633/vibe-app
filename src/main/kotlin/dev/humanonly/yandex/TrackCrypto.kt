package dev.humanonly.yandex

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Дешифровка аудио-блоба ЯМ: AES-128-CTR, IV = 16 нулевых байт.
 *
 * key — 16 байт (32 hex-символа) → AES-128 (НЕ 256). IV нулевой (nonce=12×0 + counter=0),
 * эквивалент pycryptodome `AES.new(key, MODE_CTR, nonce=bytes(12))`.
 *
 * CTR seekable: counter = offset/16 → дешифровка срезов Range-запросов и докачка возможны
 * (проверено в фазе 0, см. docs/lessons-learned.md). Проверено байт-в-байт против эталона.
 */
object TrackCrypto {
    private const val BLOCK = 16

    fun decrypt(enc: ByteArray, keyHex: String): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(keyHex), IvParameterSpec(ByteArray(BLOCK)))
        return cipher.doFinal(enc)
    }

    /**
     * Дешифрует срез, начинающийся с байтового смещения [offset] (кратного 16 — границе блока CTR).
     * Позволяет обрабатывать докачанные Range-срезы без дешифровки всего файла.
     */
    fun decryptFrom(offset: Long, enc: ByteArray, keyHex: String): ByteArray {
        require(offset % BLOCK == 0L) { "offset должен быть кратен размеру блока $BLOCK, получено $offset" }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(keyHex), IvParameterSpec(counterIv(offset / BLOCK)))
        return cipher.doFinal(enc)
    }

    private fun key(keyHex: String) = SecretKeySpec(hexToBytes(keyHex), "AES")

    /** Big-endian 128-битный счётчик CTR из номера блока. */
    private fun counterIv(block: Long): ByteArray {
        val iv = ByteArray(BLOCK)
        var v = block
        for (i in BLOCK - 1 downTo 0) {
            iv[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return iv
    }

    fun hexToBytes(s: String): ByteArray {
        require(s.length % 2 == 0) { "hex-строка нечётной длины" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
