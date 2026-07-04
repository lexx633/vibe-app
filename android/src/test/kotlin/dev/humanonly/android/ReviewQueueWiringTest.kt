package dev.humanonly.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.humanonly.db.DiscoveredTrack
import dev.humanonly.db.TrackRepository
import dev.humanonly.review.ReviewDecision
import dev.humanonly.state.TrackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Проводка ревью-очереди в android на РЕАЛЬНОМ framework SQLite (Robolectric) + реальном on-disk файле
 * индекса через [CurationOpenHelper] (хард-правило 5: тот же код, что поедет в APK). Проверяем сквозь
 * [ServiceLocator.reviewQueue]: серую зону (`review_required`) видно в очереди с display-полями, кнопка
 * «ИИ» переводит трек в `ai_confirmed` (штатное рождение — забирает потом SqlActionQueue), «не ИИ» —
 * в `human_confirmed`; всё персистится в общий файл, audit без PII (§12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewQueueWiringTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** Кладёт трек в очередь ревью прямо в файловый индекс, тот же, что читает ServiceLocator. */
    private fun seedReview(id: String, title: String?, artist: String?, score: Double?) {
        val db = AndroidDb(CurationOpenHelper(ctx).writableDatabase)
        TrackRepository(db) { 1000L }.upsertDiscovered(listOf(DiscoveredTrack(id, "a")))
        db.update(
            "UPDATE track SET verdict='review_required', title=?, artist=?, meta_score=? WHERE yandex_track_id=?",
            listOf(title, artist, score, id),
        )
    }

    private fun verdictOf(id: String): String? =
        AndroidDb(CurationOpenHelper(ctx).writableDatabase)
            .query("SELECT verdict AS v FROM track WHERE yandex_track_id=?", listOf(id)) { it.string("v") }
            .first()

    @Before
    fun setup() = ctx.deleteDatabase(CurationOpenHelper.DB_NAME).let {}

    @After
    fun teardown() = ctx.deleteDatabase(CurationOpenHelper.DB_NAME).let {}

    @Test
    fun `очередь показывает серую зону с display-полями`() {
        seedReview("t1", "Song", "Band", 0.55)

        val page = ServiceLocator.reviewQueue(ctx).page(limit = 10)
        assertEquals(1, page.total)
        val item = page.items.single()
        assertEquals("t1", item.trackId)
        assertEquals("Song", item.title)
        assertEquals("Band", item.artist)
        assertEquals(TrackState.REVIEW_REQUIRED, item.currentState)
    }

    @Test
    fun `кнопка ИИ переводит в ai_confirmed и убирает из очереди`() {
        seedReview("t1", "Song", "Band", 0.6)
        val queue = ServiceLocator.reviewQueue(ctx)

        val outcome = queue.decide("t1", TrackState.REVIEW_REQUIRED, ReviewDecision.AI)
        assertTrue(outcome.accepted)
        assertEquals(TrackState.AI_CONFIRMED, outcome.to)
        assertEquals("ai_confirmed", verdictOf("t1"))
        assertEquals("ушёл из очереди", 0, ServiceLocator.reviewQueue(ctx).page(limit = 10).total)
    }

    @Test
    fun `кнопка не ИИ переводит в human_confirmed`() {
        seedReview("t2", null, null, null)

        val outcome = ServiceLocator.reviewQueue(ctx).decide("t2", TrackState.REVIEW_REQUIRED, ReviewDecision.NOT_AI)
        assertTrue(outcome.accepted)
        assertEquals("human_confirmed", verdictOf("t2"))
    }

    @Test
    fun `запрещённое решение не мутирует индекс`() {
        seedReview("t3", null, null, null)
        AndroidDb(CurationOpenHelper(ctx).writableDatabase)
            .update("UPDATE track SET verdict='clean' WHERE yandex_track_id='t3'")

        // clean → ai_confirmed нет ребра §5
        val outcome = ServiceLocator.reviewQueue(ctx).decide("t3", TrackState.CLEAN, ReviewDecision.AI)
        assertFalse(outcome.accepted)
        assertEquals("clean", verdictOf("t3"))
    }
}
