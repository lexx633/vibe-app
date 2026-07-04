package dev.humanonly.pipeline

import dev.humanonly.backup.RestoreAction
import dev.humanonly.backup.RestoreExecutor
import dev.humanonly.backup.RestoreOutcome
import dev.humanonly.backup.RestorePlan

/**
 * Живой исполнитель плана восстановления лайков (F7, §6.3) поверх [LibraryActions] — ОТКАТ чистки прямо на
 * устройстве, без adb/ПК. Реализует [RestoreExecutor], так что план строится тем же [dev.humanonly.backup.RestorePlanner]
 * и сначала гоняется через [dev.humanonly.backup.DryRunExecutor] (dry-run → бэкап → подтверждение, хард-правило 5).
 *
 * Семантика шагов (важно: живой дизлайк ЯМ СНИМАЕТ лайк — проверено на тест-акке 2026-07-04):
 *   - [RestoreAction.RE_ADD] — вернуть лайк, которого сейчас нет. Трек мог уйти из лайков двумя путями:
 *     чистка мёртвых/серых сделала `unlike` (лайка нет, дизлайка нет) ИЛИ гейт-ИИ сделал `dislike`
 *     (лайка нет И висит дизлайк). Чтобы откат был корректен в обоих случаях, сначала снимаем возможный
 *     дизлайк ([LibraryActions.undislike] — no-op, если его не было), затем ставим лайк ([LibraryActions.like]).
 *     Изменением считаем факт возврата лайка (`like`==true); undislike — вспомогательная зачистка.
 *   - [RestoreAction.REMOVE] — снять лайк, появившийся ПОСЛЕ бэкапа (только при restoreExactly): [LibraryActions.unlike].
 *
 * Идемпотентность (§6.2): повтор безопасен. Re-add уже лайкнутого → `like` вернёт false → noop; remove
 * уже снятого → `unlike` вернёт false → noop. Без PII: наружу только trackId/счётчики ([RestoreOutcome]).
 */
class LibraryRestoreExecutor(
    private val library: LibraryActions,
) : RestoreExecutor {

    override fun execute(plan: RestorePlan): RestoreOutcome {
        val reAdded = ArrayList<String>()
        val removed = ArrayList<String>()
        val noop = ArrayList<String>()

        for (step in plan.steps) {
            when (step.action) {
                RestoreAction.RE_ADD -> {
                    // Снять возможный дизлайк (гейт-ИИ дизлайкал → лайк не встанет, пока дизлайк висит),
                    // затем вернуть лайк. Изменение = фактический возврат лайка.
                    library.undislike(step.trackId)
                    if (library.like(step.trackId)) reAdded += step.trackId else noop += step.trackId
                }
                RestoreAction.REMOVE ->
                    if (library.unlike(step.trackId)) removed += step.trackId else noop += step.trackId
            }
        }
        return RestoreOutcome(reAdded, removed, noop)
    }
}
