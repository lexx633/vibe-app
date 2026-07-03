package dev.humanonly.yandex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Тесты классификации челленджей/троттлинга ЯМ (§6.3). */
class ChallengeClassifierTest {

    private fun kind(status: Int, url: String = "https://api.music.yandex.net/x", body: String = "{\"ok\":1}", json: Boolean = true) =
        ChallengeClassifier.classify(status, url, body, json).kind

    @Test
    fun `нормальный 200 с JSON → OK`() {
        assertEquals(ChallengeKind.OK, kind(200, body = "{\"tracks\":[]}"))
    }

    @Test
    fun `капча по redirect на passport auth с retpath`() {
        val url = "https://passport.yandex.ru/auth?retpath=https%3A%2F%2Fmusic.yandex.ru"
        assertEquals(ChallengeKind.CAPTCHA, kind(200, url = url, body = "<html>login</html>"))
    }

    @Test
    fun `капча приоритетнее кода 403 если URL passport`() {
        val url = "https://passport.yandex.ru/auth?retpath=x"
        assertEquals(ChallengeKind.CAPTCHA, kind(403, url = url, body = ""))
    }

    @Test
    fun `капча по маркеру SmartCaptcha в теле`() {
        assertEquals(ChallengeKind.CAPTCHA, kind(200, body = "<div class=SmartCaptcha></div>"))
    }

    @Test
    fun `HTTP 429 → RATE_LIMIT`() {
        assertEquals(ChallengeKind.RATE_LIMIT, kind(429, body = ""))
    }

    @Test
    fun `HTTP 403 без passport → RATE_LIMIT`() {
        assertEquals(ChallengeKind.RATE_LIMIT, kind(403, body = "forbidden"))
    }

    @Test
    fun `200 с пустым телом при JSON → SILENT_THROTTLE`() {
        assertEquals(ChallengeKind.SILENT_THROTTLE, kind(200, body = "   "))
    }

    @Test
    fun `200 с урезанным JSON → SILENT_THROTTLE`() {
        assertEquals(ChallengeKind.SILENT_THROTTLE, kind(200, body = "{\"tracks\":[{\"id\":1"))
    }

    @Test
    fun `200 с не-JSON телом при ожидании JSON → SILENT_THROTTLE`() {
        assertEquals(ChallengeKind.SILENT_THROTTLE, kind(200, body = "service unavailable"))
    }

    @Test
    fun `пустое тело для байтового запроса (не JSON) не троттлинг`() {
        assertEquals(ChallengeKind.OK, kind(200, body = "", json = false))
    }

    @Test
    fun `isThrottleSignal истинен для всех троттл-видов`() {
        assertTrue(ChallengeResult(ChallengeKind.CAPTCHA, "").isThrottleSignal)
        assertTrue(ChallengeResult(ChallengeKind.RATE_LIMIT, "").isThrottleSignal)
        assertTrue(ChallengeResult(ChallengeKind.SILENT_THROTTLE, "").isThrottleSignal)
        assertTrue(!ChallengeResult(ChallengeKind.OK, "").isThrottleSignal)
    }
}
