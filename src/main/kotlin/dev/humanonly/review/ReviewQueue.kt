package dev.humanonly.review

import dev.humanonly.state.TrackState
import dev.humanonly.state.TrackStateMachine

/**
 * Ревью-очередь human-in-the-loop (ТЗ §F4, §5). Серая зона без аудио уходит в `review_required`
 * (см. DetectionCascade) — человек решает две кнопки: **«ИИ»** и **«не ИИ»**.
 *   - «ИИ»    → `ai_confirmed` (дальше — действия по режиму F4, ActionDispatcher);
 *   - «не ИИ» → `human_confirmed` (whitelist; §5: «любое ai-состояние → human_confirmed»).
 *
 * Чистый презентер: источник очереди и персист решения — за [ReviewSource] / [ReviewSink],
 * поэтому тестируется на fakes без БД/UI. Каждый переход валидируется единым [TrackStateMachine]
 * (хард-правило 10) — запрещённое решение отклоняется, а не мутирует состояние.
 *
 * PII (§12): [ReviewItem] несёт title/artist ТОЛЬКО для рендера строки человеку; в [ReviewSink]
 * (audit) уходят лишь id/decision/состояния/ts — никаких имён. Идемпотентность: повторное то же
 * решение (двойной клик) — no-op success, не ошибка.
 */
class ReviewQueue(
    private val source: ReviewSource,
    private val sink: ReviewSink = ReviewSink.None,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Страница очереди для UI. Валидирует пагинацию; `hasMore` — есть ли ещё за пределами окна. */
    fun page(limit: Int, offset: Int = 0): ReviewPage {
        require(limit > 0) { "limit должен быть > 0" }
        require(offset >= 0) { "offset должен быть ≥ 0" }
        val total = source.total()
        val items = source.pending(limit, offset)
        return ReviewPage(items = items, total = total, offset = offset, hasMore = offset + items.size < total)
    }

    /** Решение по одному треку. Валидирует переход §5; идемпотентно при повторе того же решения. */
    fun decide(trackId: String, currentState: TrackState, decision: ReviewDecision): ReviewOutcome {
        val to = decision.target
        // Идемпотентность: трек уже в целевом состоянии решения (двойной клик) → no-op success.
        if (currentState == to) {
            return ReviewOutcome(trackId, decision, from = currentState, to = to, accepted = true, noop = true)
        }
        if (!TrackStateMachine.canTransition(currentState, to)) {
            return ReviewOutcome(
                trackId, decision, from = currentState, to = to,
                accepted = false, refusedReason = REFUSED_ILLEGAL_TRANSITION,
            )
        }
        TrackStateMachine.validateTransition(currentState, to) // страховка инварианта (хард-правило 10)
        sink.commit(trackId, decision, from = currentState, to = to, decidedAtMs = nowMs())
        return ReviewOutcome(trackId, decision, from = currentState, to = to, accepted = true)
    }

    /** Пакетное решение (например «отметить всё видимое как не ИИ»). Порядок сохраняется. */
    fun decideBatch(decisions: Iterable<ReviewCommand>): List<ReviewOutcome> =
        decisions.map { decide(it.trackId, it.currentState, it.decision) }

    companion object {
        const val REFUSED_ILLEGAL_TRANSITION = "illegal_transition"
    }
}

/** Кнопка ревью → узел §5, в который переводим. */
enum class ReviewDecision(val target: TrackState) {
    /** «ИИ»: подтвердить как AI. */
    AI(TrackState.AI_CONFIRMED),

    /** «не ИИ»: обелить (whitelist). */
    NOT_AI(TrackState.HUMAN_CONFIRMED),
}

/**
 * Строка очереди для человека. title/artist — display-only (PII, §12): для рендера, НЕ для audit.
 * score/reason — почему трек попал на ревью (машинные, помогают решению).
 */
data class ReviewItem(
    val trackId: String,
    val currentState: TrackState = TrackState.REVIEW_REQUIRED,
    val title: String? = null,
    val artist: String? = null,
    val metaScore: Double? = null,
    val reason: String? = null,
)

/** Команда пакетного решения. */
data class ReviewCommand(
    val trackId: String,
    val currentState: TrackState,
    val decision: ReviewDecision,
)

/** Страница очереди для UI. */
data class ReviewPage(
    val items: List<ReviewItem>,
    val total: Int,
    val offset: Int,
    val hasMore: Boolean,
)

/** Итог решения. При отказе `accepted=false` + [refusedReason]; `noop` — повтор того же решения. */
data class ReviewOutcome(
    val trackId: String,
    val decision: ReviewDecision,
    val from: TrackState,
    val to: TrackState,
    val accepted: Boolean,
    val noop: Boolean = false,
    val refusedReason: String? = null,
)

/** Источник очереди review_required (реализация — БД-слой). Пагинация limit/offset. */
interface ReviewSource {
    fun pending(limit: Int, offset: Int): List<ReviewItem>
    fun total(): Int
}

/** Приёмник решения человека: персист перехода + audit (§12, только id/decision/состояния/ts). */
fun interface ReviewSink {
    fun commit(trackId: String, decision: ReviewDecision, from: TrackState, to: TrackState, decidedAtMs: Long)

    companion object {
        val None = ReviewSink { _, _, _, _, _ -> }
    }
}
