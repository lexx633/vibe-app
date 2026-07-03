package dev.humanonly.state

import dev.humanonly.state.TrackState.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackStateMachineTest {

    /** Все явные рёбра графа §5 проходят валидатор. */
    @Test
    fun `allowed transitions from graph pass`() {
        val edges = listOf(
            UNKNOWN to CLEAN,
            UNKNOWN to SUSPECTED,
            UNKNOWN to REVIEW_REQUIRED,
            REVIEW_REQUIRED to HUMAN_CONFIRMED,
            REVIEW_REQUIRED to AI_CONFIRMED,
            SUSPECTED to AI_CONFIRMED,
            SUSPECTED to HUMAN_CONFIRMED,
            AI_CONFIRMED to DISLIKED,
            DISLIKED to MOVED_TO_PLAYLIST,
            MOVED_TO_PLAYLIST to REMOVAL_PENDING,
            REMOVAL_PENDING to REMOVED_FROM_LIKES,
            CLEAN to DOWNLOADED,
            HUMAN_CONFIRMED to DOWNLOADED,
            DOWNLOADED to ARCHIVED,
        )
        edges.forEach { (from, to) ->
            assertTrue(TrackStateMachine.canTransition(from, to), "ожидался разрешённый: ${from.code} → ${to.code}")
        }
    }

    /** Показательные запрещённые переходы отклоняются. */
    @Test
    fun `forbidden transitions are rejected`() {
        val forbidden = listOf(
            CLEAN to REMOVED_FROM_LIKES,
            ARCHIVED to SUSPECTED,
            UNKNOWN to ARCHIVED,
            UNKNOWN to DOWNLOADED,
            CLEAN to SUSPECTED,
            DOWNLOADED to CLEAN,
            AI_CONFIRMED to MOVED_TO_PLAYLIST, // только через disliked
            REVIEW_REQUIRED to DISLIKED,
            CLEAN to HUMAN_CONFIRMED, // clean не ai-состояние
            REVIEW_REQUIRED to CLEAN, // review_required → clean не в графе
        )
        forbidden.forEach { (from, to) ->
            assertFalse(TrackStateMachine.canTransition(from, to), "ожидался запрещённый: ${from.code} → ${to.code}")
        }
    }

    /** validateTransition бросает IllegalStateException на запрещённом переходе. */
    @Test
    fun `validateTransition throws on forbidden`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            TrackStateMachine.validateTransition(CLEAN, REMOVED_FROM_LIKES)
        }
        assertTrue(ex.message!!.contains("clean") && ex.message!!.contains("removed_from_likes"))
    }

    /** Спец-правило: любое ai-состояние → human_confirmed («не ИИ»). */
    @Test
    fun `any ai state to human_confirmed allowed`() {
        TrackStateMachine.AI_STATES.forEach { s ->
            assertTrue(TrackStateMachine.canTransition(s, HUMAN_CONFIRMED), "${s.code} → human_confirmed должен быть разрешён")
        }
    }

    /** Не-ai-состояния (кроме review_required, где ребро явное) НЕ откатываются в human_confirmed. */
    @Test
    fun `non ai states cannot jump to human_confirmed`() {
        listOf(UNKNOWN, CLEAN, DOWNLOADED, ARCHIVED).forEach { s ->
            assertFalse(TrackStateMachine.canTransition(s, HUMAN_CONFIRMED), "${s.code} → human_confirmed должен быть запрещён")
        }
    }

    /** Спец-правило: любое состояние → is_dead. */
    @Test
    fun `any state to is_dead allowed`() {
        TrackState.entries.filter { it != IS_DEAD }.forEach { s ->
            assertTrue(TrackStateMachine.canTransition(s, IS_DEAD), "${s.code} → is_dead должен быть разрешён")
        }
    }

    /** is_dead терминален: из него нет переходов. */
    @Test
    fun `is_dead is terminal`() {
        TrackState.entries.forEach { to ->
            assertFalse(TrackStateMachine.canTransition(IS_DEAD, to), "из is_dead не должно быть перехода в ${to.code}")
        }
    }

    /** Идемпотентные само-переходы action-узлов чистки безопасны; прочие само-переходы запрещены. */
    @Test
    fun `idempotent self transitions`() {
        listOf(DISLIKED, MOVED_TO_PLAYLIST, REMOVAL_PENDING, REMOVED_FROM_LIKES).forEach { s ->
            assertTrue(TrackStateMachine.canTransition(s, s), "идемпотентный само-переход ${s.code} должен быть разрешён")
        }
        listOf(UNKNOWN, CLEAN, ARCHIVED, AI_CONFIRMED).forEach { s ->
            assertFalse(TrackStateMachine.canTransition(s, s), "само-переход ${s.code} должен быть запрещён")
        }
    }
}
