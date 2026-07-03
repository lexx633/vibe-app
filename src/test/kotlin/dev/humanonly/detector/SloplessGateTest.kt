package dev.humanonly.detector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Тесты hard gate каскада 0 (ADR-0005). Фикстура СИНТЕТИЧЕСКАЯ (никаких GPL-данных slopless в репо):
 * произвольные id, повторяющие только *форму* реального снапшота `{"timestamp","artists"}`.
 */
class SloplessGateTest {

    /** Синтетический снапшот в формате slopless: намеренно не отсортирован и с дублем 30. */
    private val fixture = """
        {"timestamp":"2026-05-17T21:03:23.084707Z","artists":[30,10,50,30,20,40]}
    """.trimIndent()

    @Test
    fun `hit — артист в базе → true`() {
        val gate = SloplessGate.fromJson(fixture)
        assertTrue(gate.isAiArtist(10))
        assertTrue(gate.isAiArtist(30))
        assertTrue(gate.isAiArtist(50))
    }

    @Test
    fun `miss — артиста нет в базе → false`() {
        val gate = SloplessGate.fromJson(fixture)
        assertFalse(gate.isAiArtist(5))
        assertFalse(gate.isAiArtist(35))
        assertFalse(gate.isAiArtist(999))
    }

    @Test
    fun `границы — min и max находятся`() {
        val gate = SloplessGate.fromJson(fixture)
        assertTrue(gate.isAiArtist(10)) // min
        assertTrue(gate.isAiArtist(50)) // max
        assertFalse(gate.isAiArtist(9))
        assertFalse(gate.isAiArtist(51))
    }

    @Test
    fun `вход дедуплится и сортируется — size без дублей`() {
        val gate = SloplessGate.fromJson(fixture)
        assertEquals(5, gate.size) // {10,20,30,40,50} — дубль 30 схлопнут
    }

    @Test
    fun `версия — из timestamp снапшота`() {
        val gate = SloplessGate.fromJson(fixture)
        assertEquals("2026-05-17T21:03:23.084707Z", gate.version)
    }

    @Test
    fun `пустая база — всё false, size 0`() {
        val gate = SloplessGate.fromJson("""{"timestamp":"t","artists":[]}""")
        assertEquals(0, gate.size)
        assertFalse(gate.isAiArtist(1))
        assertFalse(gate.isAiArtist(0))
    }

    @Test
    fun `лишние поля в JSON игнорируются`() {
        val gate = SloplessGate.fromJson(
            """{"timestamp":"t","artists":[7],"extra":{"x":1},"count":1}""",
        )
        assertTrue(gate.isAiArtist(7))
    }

    @Test
    fun `строковая перегрузка — числовой id как Int`() {
        val gate = SloplessGate.fromJson(fixture)
        assertTrue(gate.isAiArtist("30"))
        assertFalse(gate.isAiArtist("31"))
    }

    @Test
    fun `строковая перегрузка — мусорный id не роняет и даёт false`() {
        val gate = SloplessGate.fromJson(fixture)
        assertFalse(gate.isAiArtist(""))
        assertFalse(gate.isAiArtist("abc"))
        assertFalse(gate.isAiArtist("30x"))
    }
}
