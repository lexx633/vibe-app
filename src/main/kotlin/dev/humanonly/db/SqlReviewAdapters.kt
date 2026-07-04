package dev.humanonly.db

import dev.humanonly.review.ReviewDecision
import dev.humanonly.review.ReviewItem
import dev.humanonly.review.ReviewSink
import dev.humanonly.review.ReviewSource
import dev.humanonly.state.TrackState

/**
 * Живые SQL-адаптеры ревью-очереди (§F4-UI) над индексом SQLite (F3). Замыкают порты [ReviewSource]
 * / [ReviewSink], которые в [dev.humanonly.review.ReviewQueue] закрывались fake'ами. Работают через
 * тонкий [Db]-порт (JDBC на JVM-тесте, framework на Android — тот же код).
 *
 * Очередь = треки в узле `review_required` (серая зона детекции без аудио, см. DetectionCascade).
 * title/artist/meta_score берутся из локального индекса ТОЛЬКО для рендера строки человеку (display-only,
 * §12) — в audit они НЕ уходят: [SqlReviewSink] пишет через [TrackRepository.writeReview] лишь
 * id/переход/ts. Пагинация — стабильная (`ORDER BY id`), чтобы страницы не «прыгали» между запросами.
 */
class SqlReviewSource(private val db: Db) : ReviewSource {

    override fun total(): Int =
        db.query("SELECT COUNT(*) AS n FROM track WHERE verdict = 'review_required' AND is_dead = 0") {
            it.long("n")!!.toInt()
        }.first()

    override fun pending(limit: Int, offset: Int): List<ReviewItem> =
        db.query(
            """
            SELECT yandex_track_id, title, artist, meta_score FROM track
            WHERE verdict = 'review_required' AND is_dead = 0 ORDER BY id LIMIT ? OFFSET ?
            """.trimIndent(),
            listOf(limit, offset),
        ) { row ->
            ReviewItem(
                trackId = row.string("yandex_track_id")!!,
                currentState = TrackState.REVIEW_REQUIRED,
                title = row.string("title"),
                artist = row.string("artist"),
                metaScore = row.double("meta_score"),
                reason = "grey_no_audio_review",
            )
        }
}

/**
 * [ReviewSink] над индексом: персист решения человека (verdict → ai_confirmed/human_confirmed) + audit
 * (§12, без PII) через [TrackRepository.writeReview]. Переход уже провалидирован [ReviewQueue] (правило 10).
 */
class SqlReviewSink(private val repo: TrackRepository) : ReviewSink {
    override fun commit(
        trackId: String,
        decision: ReviewDecision,
        from: TrackState,
        to: TrackState,
        decidedAtMs: Long,
    ) {
        repo.writeReview(trackId, from, to)
    }
}
