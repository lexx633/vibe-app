package dev.humanonly.db

import dev.humanonly.schedule.TransientException
import dev.humanonly.yandex.YandexThrottleException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [LiveScanSource] против РЕАЛЬНОГО SQLite (sqlite-jdbc, in-memory): читает лайки через fake
 * [LibraryReader] (без сети/токена), регистрирует их в индексе и отдаёт scan_delta. Проверяет
 * связку сеть→БД→delta, идемпотентность повторного прогона и маппинг троттлинга в [TransientException].
 */
class LiveScanSourceTest {

    private lateinit var db: JdbcDb
    private lateinit var repo: TrackRepository

    @BeforeEach
    fun setup() {
        db = JdbcDb()
        db.initSchema()
        repo = TrackRepository(db) { 1000L }
    }

    @AfterEach
    fun teardown() = db.close()

    private fun scalarLong(sql: String): Long = db.query(sql) { it.long("v")!! }.first()

    @Test
    fun `лайки регистрируются в индексе и выходят в scan_delta`() {
        val reader = LibraryReader { listOf(DiscoveredTrack("t1", null), DiscoveredTrack("t2", null)) }
        val source = LiveScanSource(reader, repo, SqlScanSource(db))

        val cands = source.newCandidates()

        assertEquals(setOf("t1", "t2"), cands.map { it.yandexTrackId }.toSet())
        assertEquals(2, scalarLong("SELECT COUNT(*) AS v FROM track"), "оба лайка попали в индекс")
    }

    @Test
    fun `повторный прогон идемпотентен и не возвращает уже сканированные`() {
        val reader = LibraryReader { listOf(DiscoveredTrack("t1", null), DiscoveredTrack("t2", null)) }
        val source = LiveScanSource(reader, repo, SqlScanSource(db))

        source.newCandidates() // первый проход регистрирует t1, t2
        // t1 «просканирован» → verdict проставлен → уходит из delta
        db.update("UPDATE track SET verdict='clean' WHERE yandex_track_id='t1'")

        val second = source.newCandidates()

        assertEquals(listOf("t2"), second.map { it.yandexTrackId }, "clean-трек в delta не возвращается")
        assertEquals(2, scalarLong("SELECT COUNT(*) AS v FROM track"), "повтор лайков не создаёт дублей")
    }

    @Test
    fun `троттлинг ЯМ маппится в TransientException (прогон уйдёт в RETRY)`() {
        val reader = LibraryReader { throw YandexThrottleException(429, "throttled") }
        val source = LiveScanSource(reader, repo, SqlScanSource(db))

        val ex = assertThrows<TransientException> { source.newCandidates() }
        assertTrue(ex.cause is YandexThrottleException, "исходный троттлинг сохранён в cause для диагностики")
        assertEquals(0, scalarLong("SELECT COUNT(*) AS v FROM track"), "при троттлинге индекс не трогаем")
    }
}
