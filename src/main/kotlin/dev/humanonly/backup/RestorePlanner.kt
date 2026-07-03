package dev.humanonly.backup

/**
 * Планировщик восстановления лайков (F7). Чистая функция: (текущие лайки, бэкап) → [RestorePlan].
 * НИЧЕГО не исполняет и не мутирует — только раскладка намерений (хард-правило 5: dry-run → план).
 */

/** Действие плана над одним trackId. */
enum class RestoreAction { RE_ADD, REMOVE }

/** Одна запись плана: что и над каким треком предполагается сделать. */
data class RestoreStep(
    val action: RestoreAction,
    val trackId: String,
)

/**
 * План восстановления. [toReAdd] — было в бэкапе, отсутствует сейчас (вернуть лайк).
 * [toRemove] — появилось после бэкапа (снять лайк; только при restoreExactly). [unchanged] — совпадает.
 * [truncated] = true, если план урезан до лимита «малого restore».
 */
data class RestorePlan(
    val toReAdd: List<RestoreStep>,
    val toRemove: List<RestoreStep>,
    val unchanged: List<String>,
    val truncated: Boolean,
) {
    /** Полный упорядоченный список шагов (сначала re-add, затем remove) — то, что исполнит executor. */
    val steps: List<RestoreStep> get() = toReAdd + toRemove

    val stepCount: Int get() = toReAdd.size + toRemove.size
}

object RestorePlanner {

    /**
     * Строит план восстановления.
     *
     * @param currentLikes текущие лайкнутые trackId (снимок сейчас).
     * @param backup манифест бэкапа (желаемое состояние).
     * @param restoreExactly true → «вернуть точно как было»: лишние лайки (появившиеся после бэкапа)
     *   попадут в [RestorePlan.toRemove]. false (дефолт) → только доливаем недостающее, ничего не снимаем.
     * @param limit «малый restore» (§F7, хард-правило 5): максимум шагов в плане; null — без ограничения.
     *   Сначала откатывают малую партию, проверяют, потом остальное.
     */
    fun plan(
        currentLikes: Collection<String>,
        backup: BackupManifest,
        restoreExactly: Boolean = false,
        limit: Int? = null,
    ): RestorePlan {
        require(limit == null || limit >= 0) { "limit не может быть отрицательным: $limit" }

        val current = currentLikes.toSet()
        val backedUp = LinkedHashSet(backup.likes.map { it.trackId }) // порядок бэкапа детерминирован

        val reAdd = backedUp.asSequence()
            .filter { it !in current }
            .map { RestoreStep(RestoreAction.RE_ADD, it) }
            .toList()

        val unchanged = backedUp.filter { it in current }

        val remove = if (restoreExactly) {
            current.asSequence()
                .filter { it !in backedUp }
                .sorted() // детерминизм: current — Set без гарантий порядка
                .map { RestoreStep(RestoreAction.REMOVE, it) }
                .toList()
        } else {
            emptyList()
        }

        if (limit == null) {
            return RestorePlan(reAdd, remove, unchanged, truncated = false)
        }

        // Малый restore: урезаем сквозной список шагов (re-add приоритетнее remove).
        val total = reAdd.size + remove.size
        val cappedReAdd = reAdd.take(limit)
        val remainingBudget = (limit - cappedReAdd.size).coerceAtLeast(0)
        val cappedRemove = remove.take(remainingBudget)
        val truncated = (cappedReAdd.size + cappedRemove.size) < total
        return RestorePlan(cappedReAdd, cappedRemove, unchanged, truncated)
    }
}
