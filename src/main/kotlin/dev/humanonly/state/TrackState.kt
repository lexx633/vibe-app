package dev.humanonly.state

/**
 * Узлы графа состояний трека (§5 ТЗ). Имена — snake-case вердикта/действия из графа
 * (`unknown`, `clean`, ... `is_dead`); enum-константы — их UPPER_CASE эквиваленты.
 */
enum class TrackState(val code: String) {
    UNKNOWN("unknown"),
    CLEAN("clean"),
    SUSPECTED("suspected"),
    REVIEW_REQUIRED("review_required"),
    HUMAN_CONFIRMED("human_confirmed"),
    AI_CONFIRMED("ai_confirmed"),
    DISLIKED("disliked"),
    MOVED_TO_PLAYLIST("moved_to_playlist"),
    REMOVAL_PENDING("removal_pending"),
    REMOVED_FROM_LIKES("removed_from_likes"),
    DOWNLOADED("downloaded"),
    ARCHIVED("archived"),
    IS_DEAD("is_dead");

    companion object {
        private val byCode = entries.associateBy(TrackState::code)
        fun fromCode(code: String): TrackState =
            byCode[code] ?: throw IllegalArgumentException("Неизвестное состояние трека: $code")
    }
}

/** Внутритрековый прогресс для resume после крэша (§6.1, ADR-0003). Не участвует в валидации графа §5. */
enum class ProcessingStage(val code: String) {
    SCAN("scan"),
    DOWNLOADING("downloading"),
    DETECTING("detecting"),
    ACTING("acting"),
    ARCHIVING("archiving"),
    DONE("done");
}
