package dev.humanonly.state

import dev.humanonly.state.TrackState.*

/**
 * Валидатор переходов состояния трека — единственный источник разрешённых рёбер графа §5 ТЗ.
 * Хард-правило 10: переходы вне списка запрещены на уровне кода.
 *
 * Граф §5:
 *   unknown → clean | suspected | review_required
 *   review_required → human_confirmed | ai_confirmed
 *   suspected → ai_confirmed | human_confirmed
 *   ai_confirmed → disliked → moved_to_playlist → removal_pending → removed_from_likes
 *   любое ai-состояние → human_confirmed  (кнопка «не ИИ», §F4)
 *   clean | human_confirmed → downloaded → archived
 *   любое → is_dead
 */
object TrackStateMachine {

    /**
     * ai-состояния — узлы, порождённые детекцией как ИИ; из них разрешён откат в HUMAN_CONFIRMED
     * («не ИИ»). REVIEW_REQUIRED сюда НЕ входит: его человеческий вердикт задан явными рёбрами.
     */
    val AI_STATES: Set<TrackState> = setOf(
        SUSPECTED, AI_CONFIRMED, DISLIKED, MOVED_TO_PLAYLIST, REMOVAL_PENDING, REMOVED_FROM_LIKES,
    )

    /** Явные рёбра графа §5 (без спец-правил «любое → …», они добавляются в [allowed]). */
    private val explicit: Map<TrackState, Set<TrackState>> = mapOf(
        UNKNOWN to setOf(CLEAN, SUSPECTED, REVIEW_REQUIRED),
        REVIEW_REQUIRED to setOf(HUMAN_CONFIRMED, AI_CONFIRMED),
        SUSPECTED to setOf(AI_CONFIRMED, HUMAN_CONFIRMED),
        AI_CONFIRMED to setOf(DISLIKED),
        DISLIKED to setOf(MOVED_TO_PLAYLIST),
        MOVED_TO_PLAYLIST to setOf(REMOVAL_PENDING),
        REMOVAL_PENDING to setOf(REMOVED_FROM_LIKES),
        CLEAN to setOf(DOWNLOADED),
        HUMAN_CONFIRMED to setOf(DOWNLOADED),
        DOWNLOADED to setOf(ARCHIVED),
    )

    /** Полный набор целей перехода из [from] с учётом спец-правил «любое ai → human_confirmed» и «любое → is_dead». */
    fun allowed(from: TrackState): Set<TrackState> {
        val targets = LinkedHashSet(explicit[from].orEmpty())
        if (from in AI_STATES) targets += HUMAN_CONFIRMED
        if (from != IS_DEAD) targets += IS_DEAD // is_dead терминален: из него переходов нет
        return targets
    }

    /**
     * Само-переход разрешён только для идемпотентных action-узлов цепочки чистки (§6.2:
     * дизлайк/плейлист/удаление повторно безопасны — ответ «уже так» = success).
     */
    private val IDEMPOTENT_SELF: Set<TrackState> = setOf(
        DISLIKED, MOVED_TO_PLAYLIST, REMOVAL_PENDING, REMOVED_FROM_LIKES,
    )

    fun canTransition(from: TrackState, to: TrackState): Boolean {
        if (from == to) return from in IDEMPOTENT_SELF
        return to in allowed(from)
    }

    /** Бросает [IllegalStateException] с понятным сообщением при запрещённом переходе. */
    fun validateTransition(from: TrackState, to: TrackState) {
        check(canTransition(from, to)) { "Запрещённый переход состояния: ${from.code} → ${to.code}" }
    }
}
