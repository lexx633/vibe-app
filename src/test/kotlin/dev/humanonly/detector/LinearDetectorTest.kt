package dev.humanonly.detector

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinearDetectorTest {

    @Serializable
    private data class GoldenCase(val features: FloatArray, val expected_score: Double)

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/detector/$name")) { "нет ресурса /detector/$name" }
            .use { it.readBytes() }

    private fun loadWeights(): LinearWeights = LinearWeights.read(resource("weights_v1.bin"))

    @Test
    fun `reader парсит заголовок и веса`() {
        val w = loadWeights()
        assertEquals(32, w.nFeatures)
        // round-trip: сериализация обратно даёт байт-в-байт исходник.
        assertTrue(w.toBytes().contentEquals(resource("weights_v1.bin")))
    }

    @Test
    fun `golden Kotlin==Python tolerance 1e-4`() {
        val detector = LinearDetector(loadWeights())
        val cases = Json.decodeFromString<List<GoldenCase>>(String(resource("golden.json"), Charsets.UTF_8))
        assertEquals(20, cases.size)
        for ((i, c) in cases.withIndex()) {
            val got = detector.score(c.features)
            assertEquals(c.expected_score, got.toDouble(), 1e-4, "вектор #$i расходится с эталоном")
        }
    }

    @Test
    fun `несовпадение длины features кидает исключение`() {
        val detector = LinearDetector(loadWeights())
        assertThrows(IllegalArgumentException::class.java) {
            detector.score(FloatArray(10))
        }
    }

    @Test
    fun `битый magic отвергается`() {
        val bad = resource("weights_v1.bin").copyOf()
        bad[0] = 'X'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) { LinearWeights.read(bad) }
    }

    @Test
    fun `неверная версия отвергается`() {
        val bad = resource("weights_v1.bin").copyOf()
        bad[4] = 9 // little-endian младший байт version
        assertThrows(IllegalArgumentException::class.java) { LinearWeights.read(bad) }
    }
}
