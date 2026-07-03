package dev.humanonly.detector

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Бинарный формат весов линейной модели детектора (`deezer/ismir25-ai-music-detector`).
 *
 * Веса маппятся в FloatArray напрямую из бинарника, без JSON на каждый прогон (см. architecture.md,
 * «Детектор-инференс — Kotlin»). Формат версионируемый: смена layout → рост [VERSION].
 *
 * Layout (little-endian):
 *   4 байта  magic  = "HMDW" (ASCII)
 *   uint32   version
 *   uint32   nFeatures
 *   float32 × nFeatures  веса
 *   float32  bias
 *
 * Генератор эталона: app/tools/gen_detector_golden.py (тот же байтовый layout).
 */
data class LinearWeights(val weights: FloatArray, val bias: Float) {
    val nFeatures: Int get() = weights.size

    companion object {
        val MAGIC = byteArrayOf('H'.code.toByte(), 'M'.code.toByte(), 'D'.code.toByte(), 'W'.code.toByte())
        const val VERSION = 1

        /** Читает веса из бинарника. Валидирует magic/version/размер — при несоответствии кидает исключение. */
        fun read(bytes: ByteArray): LinearWeights {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val magic = ByteArray(4)
            require(buf.remaining() >= 4) { "файл короче заголовка (нет magic)" }
            buf.get(magic)
            require(magic.contentEquals(MAGIC)) {
                "неверный magic: ожидалось HMDW, получено ${String(magic, Charsets.ISO_8859_1)}"
            }

            val version = buf.int
            require(version == VERSION) { "неподдерживаемая версия формата: $version (ожидалось $VERSION)" }

            val nFeatures = buf.int
            require(nFeatures in 1..1_000_000) { "неправдоподобное nFeatures: $nFeatures" }

            val expected = nFeatures.toLong() * 4 + 4 // веса + bias
            require(buf.remaining().toLong() == expected) {
                "размер данных не совпадает: осталось ${buf.remaining()} байт, ожидалось $expected"
            }

            val weights = FloatArray(nFeatures) { buf.float }
            val bias = buf.float
            return LinearWeights(weights, bias)
        }
    }

    /** Сериализация в тот же байтовый формат — для тестов/генерации. */
    fun toBytes(): ByteArray {
        val buf = ByteBuffer.allocate(4 + 4 + 4 + weights.size * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.putInt(VERSION)
        buf.putInt(weights.size)
        for (w in weights) buf.putFloat(w)
        buf.putFloat(bias)
        return buf.array()
    }

    // data class с FloatArray: явные equals/hashCode по содержимому.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LinearWeights) return false
        return bias == other.bias && weights.contentEquals(other.weights)
    }

    override fun hashCode(): Int = 31 * weights.contentHashCode() + bias.hashCode()
}
