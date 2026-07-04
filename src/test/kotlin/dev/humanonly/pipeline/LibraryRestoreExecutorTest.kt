package dev.humanonly.pipeline

import dev.humanonly.backup.RestoreAction
import dev.humanonly.backup.RestorePlan
import dev.humanonly.backup.RestoreStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Тесты живого F7-отката ([LibraryRestoreExecutor]). Инварианты: RE_ADD снимает возможный дизлайк и
 * возвращает лайк (живой дизлайк ЯМ снимает лайк — откат обязан вернуть лайк); REMOVE снимает лайк;
 * идемпотентность (§6.2) — при no-op (changed=false) шаг уходит в noop, ничего не «возвращено/снято».
 */
class LibraryRestoreExecutorTest {

    /** Записывающие акк-операции. changed = что вернуть; call-лог по (op, trackId). */
    private class RecordingLibrary(private val changed: Boolean = true) : LibraryActions {
        val calls = mutableListOf<Pair<String, String>>()
        override fun dislike(trackId: String): Boolean { calls += "dislike" to trackId; return changed }
        override fun undislike(trackId: String): Boolean { calls += "undislike" to trackId; return changed }
        override fun like(trackId: String): Boolean { calls += "like" to trackId; return changed }
        override fun unlike(trackId: String): Boolean { calls += "unlike" to trackId; return changed }
        override fun addToPlaylist(trackId: String, playlistKind: String): Boolean { calls += "addToPlaylist" to trackId; return changed }
        override fun removeFromPlaylist(trackId: String, playlistKind: String): Boolean { calls += "removeFromPlaylist" to trackId; return changed }
    }

    private fun planOf(vararg steps: RestoreStep): RestorePlan {
        val reAdd = steps.filter { it.action == RestoreAction.RE_ADD }
        val remove = steps.filter { it.action == RestoreAction.REMOVE }
        return RestorePlan(reAdd, remove, unchanged = emptyList(), truncated = false)
    }

    @Test
    fun `re-add снимает возможный дизлайк и возвращает лайк`() {
        val lib = RecordingLibrary()
        val out = LibraryRestoreExecutor(lib).execute(planOf(RestoreStep(RestoreAction.RE_ADD, "t1")))

        assertEquals(listOf("undislike" to "t1", "like" to "t1"), lib.calls)
        assertEquals(listOf("t1"), out.reAdded)
        assertEquals(emptyList<String>(), out.removed)
        assertEquals(emptyList<String>(), out.noop)
    }

    @Test
    fun `remove снимает лайк`() {
        val lib = RecordingLibrary()
        val out = LibraryRestoreExecutor(lib).execute(planOf(RestoreStep(RestoreAction.REMOVE, "t9")))

        assertEquals(listOf("unlike" to "t9"), lib.calls)
        assertEquals(listOf("t9"), out.removed)
        assertEquals(emptyList<String>(), out.reAdded)
        assertEquals(emptyList<String>(), out.noop)
    }

    @Test
    fun `идемпотентность — при changed=false шаги уходят в noop`() {
        val lib = RecordingLibrary(changed = false)
        val out = LibraryRestoreExecutor(lib).execute(
            planOf(RestoreStep(RestoreAction.RE_ADD, "t1"), RestoreStep(RestoreAction.REMOVE, "t2")),
        )

        // undislike зовётся всегда (вспомогательная зачистка), но re-add=noop, т.к. like вернул false.
        assertEquals(listOf("undislike" to "t1", "like" to "t1", "unlike" to "t2"), lib.calls)
        assertEquals(emptyList<String>(), out.reAdded)
        assertEquals(emptyList<String>(), out.removed)
        assertEquals(listOf("t1", "t2"), out.noop)
    }
}
