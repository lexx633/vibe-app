package dev.humanonly.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DryRunExecutorTest {

    private fun backupOf(vararg ids: String) = BackupManifest(
        createdAt = 1_700_000_000_000L,
        likes = ids.map { LikedTrackEntry(trackId = it) },
    )

    @Test
    fun `dry run counts match plan and mutate nothing`() {
        val backup = backupOf("100000001", "100000002", "100000003")
        val current = listOf("100000003", "100000004")
        val plan = RestorePlanner.plan(current, backup, restoreExactly = true)

        val outcome = DryRunExecutor(liveLikes = current).execute(plan)

        // reAdd: 1,2 (нет сейчас) ; remove: 4 (лишний) ; noop: нет
        assertEquals(listOf("100000001", "100000002"), outcome.reAdded)
        assertEquals(listOf("100000004"), outcome.removed)
        assertEquals(2, outcome.reAddedCount)
        assertEquals(1, outcome.removedCount)
        // Число обработанных шагов == размер плана: ничего не потеряно.
        assertEquals(plan.stepCount, outcome.reAddedCount + outcome.removedCount + outcome.noopCount)
    }

    /** Идемпотентность: повторный прогон того же плана → тот же результат (executor ничего не мутирует). */
    @Test
    fun `repeated dry run is idempotent`() {
        val backup = backupOf("100000001", "100000002")
        val current = listOf("100000002")
        val plan = RestorePlanner.plan(current, backup, restoreExactly = true)
        val executor = DryRunExecutor(liveLikes = current)

        val first = executor.execute(plan)
        val second = executor.execute(plan)

        assertEquals(first, second)
    }

    /** re-add уже присутствующего лайка = no-op (идемпотентность §6.2). */
    @Test
    fun `re-add of already present like is noop`() {
        // План содержит шаг RE_ADD для трека, который на самом деле уже лайкнут вживую.
        val plan = RestorePlan(
            toReAdd = listOf(RestoreStep(RestoreAction.RE_ADD, "100000001")),
            toRemove = emptyList(),
            unchanged = emptyList(),
            truncated = false,
        )
        val outcome = DryRunExecutor(liveLikes = listOf("100000001")).execute(plan)

        assertEquals(emptyList<String>(), outcome.reAdded)
        assertEquals(listOf("100000001"), outcome.noop)
    }
}
