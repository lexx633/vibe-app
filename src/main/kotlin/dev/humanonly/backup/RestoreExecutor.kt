package dev.humanonly.backup

/**
 * Исполнитель плана восстановления (F7). Реальные вызовы like/unlike к ЯМ — за этим интерфейсом.
 * Идемпотентность (data-model §2, ТЗ §6.2): re-add уже лайкнутого и remove уже снятого = no-op.
 */

/** Итог прогона: намеренные действия и счётчики. Без PII — только id/коды. */
data class RestoreOutcome(
    val reAdded: List<String>,
    val removed: List<String>,
    /** шаги, оказавшиеся no-op (состояние уже целевое) — идемпотентность. */
    val noop: List<String>,
) {
    val reAddedCount: Int get() = reAdded.size
    val removedCount: Int get() = removed.size
    val noopCount: Int get() = noop.size
}

interface RestoreExecutor {
    fun execute(plan: RestorePlan): RestoreOutcome
}

/**
 * Dry-run исполнитель (§F7 «Dry Restore»): ЗАПИСЫВАЕТ намеренные действия и счётчики, но НИЧЕГО
 * не мутирует — нет ни сети, ни изменения [liveLikes]. [liveLikes] — read-only снимок «как сейчас»,
 * нужен лишь чтобы отметить шаги, которые в реальности стали бы no-op (цель уже достигнута).
 *
 * Реальный исполнитель через YandexClient (users_likes_tracks_add / remove) появится в подзадаче 1c;
 * он реализует тот же [RestoreExecutor], потому здесь фиксируется контракт вывода.
 */
class DryRunExecutor(
    liveLikes: Collection<String> = emptySet(),
) : RestoreExecutor {

    private val liveLikes: Set<String> = liveLikes.toSet()

    override fun execute(plan: RestorePlan): RestoreOutcome {
        val reAdded = ArrayList<String>()
        val removed = ArrayList<String>()
        val noop = ArrayList<String>()

        for (step in plan.steps) {
            when (step.action) {
                // re-add уже присутствующего лайка = no-op (идемпотентность §6.2).
                RestoreAction.RE_ADD ->
                    if (step.trackId in liveLikes) noop += step.trackId else reAdded += step.trackId
                // remove отсутствующего = no-op.
                RestoreAction.REMOVE ->
                    if (step.trackId in liveLikes) removed += step.trackId else noop += step.trackId
            }
        }
        // Ничего не мутируем: liveLikes остаётся неизменным → повторный прогон даёт тот же результат.
        return RestoreOutcome(reAdded, removed, noop)
    }
}
