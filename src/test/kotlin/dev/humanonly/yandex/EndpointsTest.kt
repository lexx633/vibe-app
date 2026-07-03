package dev.humanonly.yandex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Офлайн: проверяем форму подписанного get-file-info и что подпись согласована с [Signing]
 * (детерминированные ts/trackId, без сети/токена ЯМ).
 */
class EndpointsTest {

    private val config = YandexConfig(accessToken = "FAKE_TOKEN_not_real", baseUrl = "https://api.example.test")

    @Test
    fun `get-file-info params в правильном порядке и с ожидаемыми значениями`() {
        val req = Endpoints.getFileInfo(config, trackId = "12345", ts = 1_700_000_000L)
        assertEquals("https://api.example.test/get-file-info", req.url)
        val p = req.params
        assertEquals("1700000000", p["ts"])
        assertEquals("12345", p["trackId"])
        assertEquals("lossless", p["quality"])
        assertEquals(Endpoints.CODECS, p["codecs"])
        assertEquals("encraw", p["transports"])
        assertTrue(p.containsKey("sign"))
        // порядок значимых для подписи ключей
        assertEquals(Signing.SIGN_PARAM_ORDER, p.keys.filter { it != "sign" }.toList())
    }

    @Test
    fun `sign в запросе совпадает с независимым вычислением через Signing`() {
        val ts = 1_700_000_000L
        val trackId = "12345"
        val req = Endpoints.getFileInfo(config, trackId, ts)

        val message = Signing.buildMessage(
            mapOf(
                "ts" to ts.toString(),
                "trackId" to trackId,
                "quality" to "lossless",
                "codecs" to Endpoints.CODECS,
                "transports" to "encraw",
            )
        )
        assertEquals(Signing.sign(message), req.params["sign"])
    }

    @Test
    fun `likes и metadata собирают корректный url`() {
        assertEquals(
            "https://api.example.test/users/u123/likes/tracks",
            Endpoints.likes(config, "u123").url,
        )
        assertEquals(
            "https://api.example.test/tracks/999",
            Endpoints.trackMetadata(config, "999").url,
        )
    }
}
