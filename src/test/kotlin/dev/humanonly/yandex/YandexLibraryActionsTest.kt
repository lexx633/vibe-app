package dev.humanonly.yandex

import dev.humanonly.pipeline.LibraryActions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `likeAdd и likeRemove строят ожидаемые url и form (референс-репо)`() {
        assertEquals(
            "https://api.example.test/users/u1/likes/tracks/add-multiple",
            Endpoints.likeAdd(config, "u1", "555").url,
        )
        assertEquals(mapOf("track-ids" to "555"), Endpoints.likeAdd(config, "u1", "555").params)
        assertEquals(
            "https://api.example.test/users/u1/likes/tracks/remove",
            Endpoints.likeRemove(config, "u1", "555").url,
        )
    }

    @Test
    fun `like через адаптер шлёт POST на likes add-multiple с track-ids`() {
        val t = CapturingTransport()
        val lib: LibraryActions = YandexLibraryActions(client(t), userId = "u1")

        assertTrue(lib.like("555"))

        assertEquals(1, t.posts.size)
        val (url, form, headers) = t.posts.first()
        assertEquals("https://api.example.test/users/u1/likes/tracks/add-multiple", url)
        assertEquals("555", form["track-ids"])
        assertTrue(headers["Authorization"]!!.startsWith("OAuth "), "запрос авторизован, токен не в url")
    }

    @Test
    fun `create резолвит uid аккаунта и использует его в пути`() {
        val t = CapturingTransport()
        val lib = YandexLibraryActions.create(client(t))

        lib.dislike("777")
        assertEquals("https://api.example.test/users/424242/dislikes/tracks/add-multiple", t.posts.first().first)
    }

    @Test
    fun `dislikes строит read-only url по референс-репо`() {
        assertEquals(
            "https://api.example.test/users/u1/dislikes/tracks",
            Endpoints.dislikes(config, "u1").url,
        )
        assertTrue(Endpoints.dislikes(config, "u1").params.isEmpty())
    }

    @Test
    fun `dislikedTrackIds парсит library-tracks (тот же shape, что likes)`() {
        val transport = object : YandexTransport {
            override fun getJson(url: String, params: Map<String, String>, headers: Map<String, String>): String {
                assertEquals("https://api.example.test/users/u1/dislikes/tracks", url)
                return """{"result":{"library":{"uid":424242,"revision":7,"tracks":[{"id":"555"},{"id":"777"}]}}}"""
            }
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): String = "{}"
            override fun getBytes(url: String, headers: Map<String, String>): ByteArray = ByteArray(0)
            override fun getRange(url: String, from: Long, to: Long?, headers: Map<String, String>): ByteArray = ByteArray(0)
        }
        assertEquals(listOf("555", "777"), client(transport).dislikedTrackIds("u1"))
    }

    // ── MOVE_TO_PLAYLIST: album-aware change-relative (референс-репо) ─────────────

    /**
     * Fake, маршрутизирующий GET по url: снимок плейлиста (revision+состав) и метаданные трека (albumId).
     * POST-и (change/create) записывает. Живых вызовов к ЯМ нет.
     */
    private class PlaylistTransport(
        private val playlistJson: String,
        private val trackMetaJson: String = """{"result":[{"id":"42","available":true,"albums":[{"id":"777"}]}]}""",
    ) : YandexTransport {
        val posts = mutableListOf<Triple<String, Map<String, String>, Map<String, String>>>()

        override fun getJson(url: String, params: Map<String, String>, headers: Map<String, String>): String = when {
            url.endsWith("/playlists/1000") -> playlistJson
            url.contains("/tracks/") -> trackMetaJson
            else -> error("неожиданный GET: $url")
        }

        override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): String {
            posts += Triple(url, form, headers)
            return """{"result":{"kind":2001,"revision":1,"trackCount":0,"tracks":[]}}"""
        }

        override fun getBytes(url: String, headers: Map<String, String>): ByteArray = ByteArray(0)
        override fun getRange(url: String, from: Long, to: Long?, headers: Map<String, String>): ByteArray = ByteArray(0)
    }

    private val playlistWith999 =
        """{"result":{"kind":1000,"revision":5,"trackCount":1,"tracks":[{"id":"999","albumId":"aa"}]}}"""

    @Test
    fun `addToPlaylist резолвит albumId и шлёт change с insert-diff по текущей ревизии`() {
        val t = PlaylistTransport(playlistWith999)
        val lib = YandexLibraryActions(client(t), userId = "u1")

        assertTrue(lib.addToPlaylist("42", "1000"))

        assertEquals(1, t.posts.size)
        val (url, form, headers) = t.posts.first()
        assertEquals("https://api.example.test/users/u1/playlists/1000/change", url)
        assertEquals("1000", form["kind"])
        assertEquals("5", form["revision"], "ревизия из снимка плейлиста (оптимистичная блокировка)")
        // вставка в конец (at=1, т.к. один трек уже есть) с album-aware id
        assertEquals("""[{"op":"insert","at":1,"tracks":[{"id":"42","albumId":"777"}]}]""", form["diff"])
        assertTrue(headers["Authorization"]!!.startsWith("OAuth "))
    }

    @Test
    fun `addToPlaylist идемпотентен — трек уже в плейлисте → no-op без change`() {
        val t = PlaylistTransport(playlistWith999)
        val lib = YandexLibraryActions(client(t), userId = "u1")

        assertFalse(lib.addToPlaylist("999", "1000"))
        assertTrue(t.posts.isEmpty(), "уже в плейлисте — акк не трогаем")
    }

    @Test
    fun `removeFromPlaylist удаляет по индексу трека диапазоном от i до i+1`() {
        val t = PlaylistTransport(playlistWith999)
        val lib = YandexLibraryActions(client(t), userId = "u1")

        assertTrue(lib.removeFromPlaylist("999", "1000"))
        val (url, form, _) = t.posts.first()
        assertEquals("https://api.example.test/users/u1/playlists/1000/change", url)
        assertEquals("""[{"op":"delete","from":0,"to":1}]""", form["diff"])
    }

    @Test
    fun `removeFromPlaylist идемпотентен — трека нет → no-op без change`() {
        val t = PlaylistTransport(playlistWith999)
        val lib = YandexLibraryActions(client(t), userId = "u1")

        assertFalse(lib.removeFromPlaylist("42", "1000"))
        assertTrue(t.posts.isEmpty())
    }

    @Test
    fun `createPlaylist шлёт POST create с title и приватной видимостью, возвращает kind`() {
        val transport = object : YandexTransport {
            override fun getJson(url: String, params: Map<String, String>, headers: Map<String, String>) =
                """{"result":{"account":{"uid":424242}}}"""
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): String {
                assertEquals("https://api.example.test/users/u1/playlists/create", url)
                assertEquals("Определены как ИИ треки", form["title"])
                assertEquals("private", form["visibility"])
                return """{"result":{"kind":3003,"revision":1,"trackCount":0,"tracks":[]}}"""
            }
            override fun getBytes(url: String, headers: Map<String, String>) = ByteArray(0)
            override fun getRange(url: String, from: Long, to: Long?, headers: Map<String, String>) = ByteArray(0)
        }
        assertEquals("3003", client(transport).createPlaylist("u1", "Определены как ИИ треки"))
    }
}
