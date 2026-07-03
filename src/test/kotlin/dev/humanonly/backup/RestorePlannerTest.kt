package dev.humanonly.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RestorePlannerTest {

    private fun backupOf(vararg ids: String) = BackupManifest(
        createdAt = 1_700_000_000_000L,
        likes = ids.map { LikedTrackEntry(trackId = it) },
    )

    @Test
    fun `splits into reAdd unchanged and remove`() {
        // бэкап: 1,2,3 ; сейчас: 2,3,4  → reAdd=1 ; unchanged=2,3 ; remove(exactly)=4
        val backup = backupOf("100000001", "100000002", "100000003")
        val current = listOf("100000002", "100000003", "100000004")

        val plan = RestorePlanner.plan(current, backup, restoreExactly = true)

        assertEquals(listOf("100000001"), plan.toReAdd.map { it.trackId })
        assertEquals(listOf("100000002", "100000003"), plan.unchanged)
        assertEquals(listOf("100000004"), plan.toRemove.map { it.trackId })
        assertFalse(plan.truncated)
    }

    @Test
    fun `without restoreExactly nothing is removed`() {
        val backup = backupOf("100000001")
        val current = listOf("100000002")

        val plan = RestorePlanner.plan(current, backup, restoreExactly = false)

        assertEquals(listOf("100000001"), plan.toReAdd.map { it.trackId })
        assertTrue(plan.toRemove.isEmpty())
    }

    /** Малый restore: план урезается до N шагов, флаг truncated взводится. */
    @Test
    fun `small restore caps plan to limit`() {
        val backup = backupOf("100000001", "100000002", "100000003", "100000004")
        val current = emptyList<String>() // все 4 надо вернуть

        val plan = RestorePlanner.plan(current, backup, limit = 2)

        assertEquals(2, plan.stepCount)
        assertTrue(plan.truncated)
    }

    @Test
    fun `limit not exceeded is not truncated`() {
        val backup = backupOf("100000001", "100000002")
        val plan = RestorePlanner.plan(emptyList(), backup, limit = 5)

        assertEquals(2, plan.stepCount)
        assertFalse(plan.truncated)
    }

    /** Идемпотентность плана: тот же вход → тот же результат. */
    @Test
    fun `planning is deterministic`() {
        val backup = backupOf("100000003", "100000001", "100000002")
        val current = listOf("100000002")

        val a = RestorePlanner.plan(current, backup, restoreExactly = true)
        val b = RestorePlanner.plan(current, backup, restoreExactly = true)

        assertEquals(a, b)
    }
}
