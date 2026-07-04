package dev.humanonly.db

import dev.humanonly.review.ReviewDecision
import dev.humanonly.review.ReviewQueue
import dev.humanonly.state.TrackState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [SqlReviewSource]/[SqlReviewSink] против РЕАЛЬНОГО SQLite (sqlite-jdbc, in-memory): очередь = треки
 * `verdict='review_required'`; решение человека («ИИ»/«не ИИ») персистится в verdict + audit без PII (§12).
 * Прогоняем через живой [ReviewQueue], чтобы покрыть весь путь UI → БД тем же кодом, что поедет на Android.
 */
class SqlReviewAdaptersTest {

    private lateinit var db: JdbcDb
    private lateinit var repo: TrackRepository
    private val clock = { 7000L }

    @BeforeEach
    fun setup() {
        db = JdbcDb()
        db.initSchema()
        repo = TrackRepository(db, clock)
    }

    @AfterEach
    fun teardown() = db.close()

    /** Кладёт трек в очередь ревью с display-полями (title/artist из индекса). */
    private fun review(id: String, title: String?, artist: String?, score: Double?) {
        repo.upsertDiscovered(listOf(DiscoveredTrack(id, "a")))
        db.update(
            "UPDATE track SET verdict='review_required', title=?, artist=?, meta_score=? WHERE yandex_track_id=?",
            listOf(title, artist, score, id),
        )
    }

    @Test
    fun `источник отдаёт только review_required с display-полями`() {
        review("t1", "Song", "Band", 0.55)
        repo.upsertDiscovered(listOf(DiscoveredTrack("t2", "a"))) // verdict NULL — не в очереди
        db.update("UPDATE track SET verdict='clean' WHERE yandex_track_id='t2'")

        val src = SqlReviewSource(db)
        assertEquals(1, src.total())
        val item = src.pending(limit = 10, offset = 0).single()
        assertEquals("t1", item.trackId)
        assertEquals("Song", item.title)
        assertEquals("Band", item.artist)
        assertEquals(0.55, item.metaScore)
        assertEquals(TrackState.REVIEW_REQUIRED, item.currentState)
    }

    @Test
    fun `мёртвые треки в очередь ревью не попадают`() {
        review("t1", null, null, null)
        db.update("UPDATE track SET is_dead=1 WHERE yandex_track_id='t1'")
        assertEquals(0, SqlReviewSource(db).total())
        assertTrue(SqlReviewSource(db).pending(10, 0).isEmpty())
    }

    @Test
    fun `пагинация стабильна по id`() {
        (1..5).forEach { review("t$it", null, null, null) }
        val q = ReviewQueue(SqlReviewSource(db))
        val p0 = q.page(limit = 2, offset = 0)
        assertEquals(listOf("t1", "t2"), p0.items.map { it.trackId })
        assertEquals(5, p0.total)
        assertTrue(p0.hasMore)
        val p1 = q.page(limit = 2, offset = 4)
        assertEquals(listOf("t5"), p1.items.map { it.trackId })
        assertFalse(p1.hasMore)
    }

    @Test
    fun `кнопка ИИ через ReviewQueue → verdict ai_confirmed + audit review без PII`() {
        review("t1", "Song", "Band", 0.6)
        val q = ReviewQueue(SqlReviewSource(db), SqlReviewSink(repo), nowMs = clock)

        val outcome = q.decide("t1", TrackState.REVIEW_REQUIRED, ReviewDecision.AI)
        assertTrue(outcome.accepted)
        assertEquals(TrackState.AI_CONFIRMED, outcome.to)

        assertEquals("ai_confirmed", db.query("SELECT verdict AS v FROM track WHERE yandex_track_id='t1'") { it.string("v") }.first())
        assertEquals(7000L, db.query("SELECT last_review AS v FROM track WHERE yandex_track_id='t1'") { it.long("v") }.first())
        // ушёл из очереди ревью
        assertEquals(0, SqlReviewSource(db).total())

        val audit = db.query(
            "SELECT action, from_state, to_state FROM audit_log WHERE track_id=(SELECT id FROM track WHERE yandex_track_id='t1')",
        ) { Triple(it.string("action"), it.string("from_state"), it.string("to_state")) }
        // detect-строки нет (verdict проставлен UPDATE'ом), только review-переход
        val reviewRow = audit.single { it.first == "review" }
        assertEquals("review_required", reviewRow.second)
        assertEquals("ai_confirmed", reviewRow.third)
    }

    @Test
    fun `кнопка не ИИ → verdict human_confirmed (whitelist)`() {
        review("t1", null, null, null)
        val q = ReviewQueue(SqlReviewSource(db), SqlReviewSink(repo), nowMs = clock)

        val outcome = q.decide("t1", TrackState.REVIEW_REQUIRED, ReviewDecision.NOT_AI)
        assertTrue(outcome.accepted)
        assertEquals("human_confirmed", db.query("SELECT verdict AS v FROM track WHERE yandex_track_id='t1'") { it.string("v") }.first())
    }

    @Test
    fun `запрещённое решение не мутирует индекс и не пишет audit`() {
        review("t1", null, null, null)
        db.update("UPDATE track SET verdict='clean' WHERE yandex_track_id='t1'") // уже clean
        val q = ReviewQueue(SqlReviewSource(db), SqlReviewSink(repo), nowMs = clock)

        // clean → ai_confirmed нет ребра §5 → отказ
        val outcome = q.decide("t1", TrackState.CLEAN, ReviewDecision.AI)
        assertFalse(outcome.accepted)
        assertEquals("clean", db.query("SELECT verdict AS v FROM track WHERE yandex_track_id='t1'") { it.string("v") }.first())
        assertNull(
            db.query("SELECT action AS v FROM audit_log WHERE track_id=(SELECT id FROM track WHERE yandex_track_id='t1')") { it.string("v") }
                .firstOrNull(),
        )
    }
}
