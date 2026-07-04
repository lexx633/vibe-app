package dev.humanonly.detector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Тесты извлечения признаков каскада 1 из строковых полей ЯМ. Проверяем: срабатывание шаблонного
 * нейминга по title/имени артиста (без регистра), подозрительный лейбл, отсутствие ложных срабатываний
 * на «живых» именах, null-каденцию (отложенный сигнал), и что вход без данных → пустые признаки.
 */
class MetaFeatureExtractorTest {

    private val extractor = MetaFeatureExtractor()

    @Test
    fun `пустой вход → нет признаков`() {
        val f = extractor.extract(title = null)
        assertFalse(f.templateNameHit)
        assertFalse(f.suspiciousLabel)
        assertNull(f.releasesInWindow)
    }

    @Test
    fun `живое имя и название не срабатывают`() {
        // Реальный трек из smoke: артист в slopless ловится гейтом, но метапризнаки по имени — чистые.
        val f = extractor.extract(title = "Fading Echoes", artistNames = listOf("Ingvarr King"))
        assertFalse(f.templateNameHit)
        assertFalse(f.suspiciousLabel)
    }

    @Test
    fun `шаблон в названии срабатывает без регистра`() {
        assertTrue(extractor.extract(title = "Chill Lofi Type Beat").templateNameHit)
        assertTrue(extractor.extract(title = "SUNO test").templateNameHit)
        assertTrue(extractor.extract(title = "My AI-generated song").templateNameHit)
    }

    @Test
    fun `шаблон в имени артиста тоже срабатывает`() {
        val f = extractor.extract(title = "Nice Title", artistNames = listOf("Made with Udio"))
        assertTrue(f.templateNameHit)
    }

    @Test
    fun `подозрительный лейбл срабатывает без регистра, обрезая пробелы`() {
        val cfg = MetaSignalConfig(suspiciousLabels = setOf("AI Farm Records"))
        val ext = MetaFeatureExtractor(cfg)
        assertTrue(ext.extract(title = "x", labelNames = listOf("  ai farm records ")).suspiciousLabel)
        assertFalse(ext.extract(title = "x", labelNames = listOf("Real Label")).suspiciousLabel)
    }

    @Test
    fun `лейбл по умолчанию не срабатывает (список пуст)`() {
        assertFalse(extractor.extract(title = "x", labelNames = listOf("SFEROOM DISTRIBUTION")).suspiciousLabel)
    }

    @Test
    fun `каденция релизов пока не считается (отложенный сигнал)`() {
        assertNull(extractor.extract(title = "Suno").releasesInWindow)
    }

    @Test
    fun `частичное слово не даёт ложного срабатывания`() {
        // \b-границы: "sunoweb" не должно матчить "suno".
        assertFalse(extractor.extract(title = "sunoweb radio").templateNameHit)
    }
}
