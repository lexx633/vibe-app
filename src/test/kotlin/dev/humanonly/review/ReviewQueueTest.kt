package dev.humanonly.review

import dev.humanonly.state.TrackState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Тесты ревью-очереди (§F4, §5). Кнопки «ИИ»/«не ИИ» = переходы review_required→ai_confirmed /
 * →human_confirmed; запрещённые решения отклоняются без мутации; повтор идемпотентен; audit без PII.
 */
class ReviewQueueTest {

    private class RecordingSink : ReviewSink {
        data class C(val id: String, val d: ReviewDecision, val from: TrackState, val to: TrackState, val ts: Long)
        val commits = mutableListOf<C>()
        override fun commit(trackId: String, decision: ReviewDecision, from: TrackState, to: TrackState, decidedAtMs: Long) {
            commits += C(trackId, decision, from, to, decidedAtMs)
        }
    }

    /** Источник поверх фиксированного списка — честная пагинация limit/offset. */
    private class FakeSource(private val all: List<ReviewItem>) : ReviewSource {
        override fun total(): Int = all.size
        override fun pending(limit: Int, offset: Int): List<ReviewItem> =
            all.drop(offset).take(limit)
    }

    private fun items(n: Int) = (1..n).map {
        ReviewItem("t$it", reason = "grey_no_audio_review", metaScore = 0.6)
    }

    // ── Пагинация ──────────────────────────────────────────────────────────────

    @Test
    fun `первая страница — total и hasMore`() {
        val q = ReviewQueue(FakeSource(items(25)))
        val p = q.page(limit = 10, offset = 0)
        assertEquals(10, p.items.size)
        assertEquals(25, p.total)
        assertTrue(p.hasMore)
        assertEquals("t1", p.items.first().trackId)
    }

    @Test
    fun `последняя страница — hasMore false`() {
        val q = ReviewQueue(FakeSource(items(25)))
        val p = q.page(limit = 10, offset = 20)
        assertEquals(5, p.items.size)
        assertFalse(p.hasMore)
    }

    @Test
    fun `невалидная пагинация → исключение`() {
        val q = ReviewQueue(FakeSource(items(5)))
        try { q.page(limit = 0); throw AssertionError("ожидалось") } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("limit"))
        }
        try { q.page(limit = 5, offset = -1); throw AssertionError("ожидалось") } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("offset"))
        }
    }

    // ── Решения ────────────────────────────────────────────────────────────────

    @Test
    fun `кнопка ИИ — review_required→ai_confirmed, коммит с ts`() {
        val sink = RecordingSink()
        val q = ReviewQueue(FakeSource(emptyList()), sink, nowMs = { 12345L })
        val o = q.decide("t1", TrackState.REVIEW_REQUIRED, ReviewDecision.AI)
        assertTrue(o.accepted)
        assertFalse(o.noop)
        assertEquals(TrackState.AI_CONFIRMED, o.to)
        assertEquals(1, sink.commits.size)
        assertEquals(12345L, sink.commits[0].ts)
        assertEquals(TrackState.REVIEW_REQUIRED, sink.commits[0].from)
    }

    @Test
    fun `кнопка не ИИ — review_required→human_confirmed`() {
        val sink = RecordingSink()
        val o = ReviewQueue(FakeSource(emptyList()), sink).decide("t1", TrackState.REVIEW_REQUIRED, ReviewDecision.NOT_AI)
        assertTrue(o.accepted)
        assertEquals(TrackState.HUMAN_CONFIRMED, o.to)
        assertEquals(TrackState.HUMAN_CONFIRMED, sink.commits[0].to)
    }

    @Test
    fun `не ИИ обеляет и из ai-состояния (whitelist §5)`() {
        // suspected → human_confirmed разрешено («любое ai → human_confirmed»).
        val sink = RecordingSink()
        val o = ReviewQueue(FakeSource(emptyList()), sink).decide("t1", TrackState.SUSPECTED, ReviewDecision.NOT_AI)
        assertTrue(o.accepted)
        assertEquals(TrackState.HUMAN_CONFIRMED, o.to)
    }

    @Test
    fun `запрещённый переход отклоняется без мутации`() {
        val sink = RecordingSink()
        // clean → ai_confirmed нет ребра §5.
        val o = ReviewQueue(FakeSource(emptyList()), sink).decide("t1", TrackState.CLEAN, ReviewDecision.AI)
        assertFalse(o.accepted)
        assertEquals(ReviewQueue.REFUSED_ILLEGAL_TRANSITION, o.refusedReason)
        assertTrue(sink.commits.isEmpty(), "отклонённое решение не коммитится")
    }

    @Test
    fun `повтор того же решения идемпотентен (двойной клик)`() {
        val sink = RecordingSink()
        // трек уже ai_confirmed, снова жмём «ИИ» → no-op success, без коммита.
        val o = ReviewQueue(FakeSource(emptyList()), sink).decide("t1", TrackState.AI_CONFIRMED, ReviewDecision.AI)
        assertTrue(o.accepted)
        assertTrue(o.noop)
        assertNull(o.refusedReason)
        assertTrue(sink.commits.isEmpty())
    }

    // ── Пакет ──────────────────────────────────────────────────────────────────

    @Test
    fun `пакетное решение сохраняет порядок и агрегирует исходы`() {
        val sink = RecordingSink()
        val q = ReviewQueue(FakeSource(emptyList()), sink)
        val outcomes = q.decideBatch(
            listOf(
                ReviewCommand("t1", TrackState.REVIEW_REQUIRED, ReviewDecision.NOT_AI),
                ReviewCommand("t2", TrackState.REVIEW_REQUIRED, ReviewDecision.AI),
                ReviewCommand("t3", TrackState.CLEAN, ReviewDecision.AI), // отклонится
            ),
        )
        assertEquals(listOf("t1", "t2", "t3"), outcomes.map { it.trackId })
        assertTrue(outcomes[0].accepted && outcomes[1].accepted)
        assertFalse(outcomes[2].accepted)
        assertEquals(2, sink.commits.size, "отклонённый в audit не пишется")
    }
}
