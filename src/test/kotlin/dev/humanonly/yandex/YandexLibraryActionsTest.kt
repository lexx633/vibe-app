package dev.humanonly.yandex

import dev.humanonly.pipeline.LibraryActions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Офлайн (без сети/акка, хард-правило 3): проверяем ФОРМУ мутирующих запросов (дизлайк/снятие) и что
 * [YandexLibraryActions] ходит в них через [YandexClient] + лимитер. Живых вызовов к ЯМ нет — fake-транспорт.
 */
class YandexLibraryActionsTest {

    private val config = YandexConfig(accessToken = "FAKE_TOKEN_not_real", baseUrl = "https://api.example.test")

    /** Fake: отдаёт account/status для uid и записывает все POST-и (url + form). */
    private class CapturingTransport : YandexTransport {
        val posts = mutableListOf<Triple<String, Map<String, String>, Map<String, String>>>()

        override fun getJson(url: String, params: Map<String, String>, headers: Map<String, String>): String =
            """{"result":{"account":{"uid":424242}}}"""

        override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): String {
            posts += Triple(url, form, headers)
            return "{}"
        }

        override fun getBytes(url: String, headers: Map<String, String>): ByteArray = ByteArray(0)
        override fun getRange(url: String, from: Long, to: Long?, headers: Map<String, String>): ByteArray = ByteArray(0)
    }

    private fun client(transport: YandexTransport): YandexClient {
        val limiter = RateLimiter(nowNanos = { 0L }, sleeper = {})
        return YandexClient(config, transport, limiter)
    }

    @Test
    fun `dislikeAdd и dislikeRemove строят ожидаемые url и form`() {
        assertEquals(
            "https://api.example.test/users/u1/dislikes/tracks/add-multiple",
            Endpoints.dislikeAdd(config, "u1", "555").url,
        )
        assertEquals(mapOf("track-ids" to "555"), Endpoints.dislikeAdd(config, "u1", "555").params)
        assertEquals(
            "https://api.example.test/users/u1/dislikes/tracks/remove",
            Endpoints.dislikeRemove(config, "u1", "555").url,
        )
    }

    @Test
    fun `dislike через адаптер шлёт POST с track-ids и OAuth-заголовком`() {
        val t = CapturingTransport()
        val lib: LibraryActions = YandexLibraryActions(client(t), userId = "u1")

        assertTrue(lib.dislike("555"))

        assertEquals(1, t.posts.size)
        val (url, form, headers) = t.posts.first()
        assertEquals("https://api.example.test/users/u1/dislikes/tracks/add-multiple", url)
        assertEquals("555", form["track-ids"])
        assertTrue(headers["Authorization"]!!.startsWith("OAuth "), "запрос авторизован, токен не в url")
    }

    @Test
    fun `undislike шлёт POST на remove`() {
        val t = CapturingTransport()
        val lib = YandexLibraryActions(client(t), userId = "u1")

        assertTrue(lib.undislike("555"))
        assertEquals("https://api.example.test/users/u1/dislikes/tracks/remove", t.posts.first().first)
    }

    @Test
    fun `create резолвит uid аккаунта и использует его в пути`() {
        val t = CapturingTransport()
        val lib = YandexLibraryActions.create(client(t))

        lib.dislike("777")
        assertEquals("https://api.example.test/users/424242/dislikes/tracks/add-multiple", t.posts.first().first)
    }

    @Test
    fun `плейлист-операции в MVP явно не поддержаны (не молчат)`() {
        val lib = YandexLibraryActions(client(CapturingTransport()), userId = "u1")
        assertThrows(UnsupportedOperationException::class.java) { lib.addToPlaylist("1", "ai") }
        assertThrows(UnsupportedOperationException::class.java) { lib.removeFromPlaylist("1", "ai") }
    }
}
