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

    // ── обогащение artist_id (для slopless-гейта) ──────────────────────────────

    @Test
    fun `enrich дозаполняет artist_id и он доходит до кандидата (гейт увидит артиста)`() {
        val reader = LibraryReader { listOf(DiscoveredTrack("t1", null), DiscoveredTrack("t2", null)) }
        val lookup = MetaLookup { id -> if (id == "t1") "999001" else "888002" }
        val source = LiveScanSource(reader, repo, SqlScanSource(db), ArtistEnricher(repo, lookup))

        val cands = source.newCandidates().associateBy { it.yandexTrackId }

        assertEquals("999001", cands.getValue("t1").artistId, "artist_id обогащён и попал в кандидата")
        assertEquals("888002", cands.getValue("t2").artistId)
    }

    @Test
    fun `enrich идемпотентен - не трогает уже заполненные и уже сканированные`() {
        repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "already"), DiscoveredTrack("t2", null)))
        db.update("UPDATE track SET verdict='clean' WHERE yandex_track_id='t1'") // сканирован → пропускается
        repo.upsertDiscovered(listOf(DiscoveredTrack("t3", null)))

        var calls = 0
        val lookup = MetaLookup { id -> calls++; "resolved-$id" }
        val n = ArtistEnricher(repo, lookup).enrich()

        assertEquals(2, calls, "резолвим только t2 и t3 (t1 сканирован и с artist_id — мимо)")
        assertEquals(2, n)
        assertEquals("resolved-t2", db.query("SELECT artist_id AS v FROM track WHERE yandex_track_id='t2'") { it.string("v") }.first())
    }

    @Test
    fun `enrich пропускает трек без артистов (lookup вернул null) - не падает`() {
        repo.upsertDiscovered(listOf(DiscoveredTrack("t1", null)))
        val n = ArtistEnricher(repo, MetaLookup { null }).enrich()

        assertEquals(0, n, "null-артист не проставляется")
        assertEquals(1, SqlScanSource(db).newCandidates().size, "трек остаётся в scan_delta (детект по метаданным)")
    }

    @Test
    fun `троттлинг при обогащении тоже уходит в TransientException`() {
        val reader = LibraryReader { listOf(DiscoveredTrack("t1", null)) }
        val enricher = ArtistEnricher(repo, MetaLookup { throw YandexThrottleException(429, "throttled") })
        val source = LiveScanSource(reader, repo, SqlScanSource(db), enricher)

        assertThrows<TransientException> { source.newCandidates() }
        // лайк уже зарегистрирован до обогащения — при ретрае обогатится (инкрементально)
        assertEquals(1, scalarLong("SELECT COUNT(*) AS v FROM track"))
    }
}
