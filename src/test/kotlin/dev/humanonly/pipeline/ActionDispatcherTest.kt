package dev.humanonly.pipeline

import dev.humanonly.state.ProcessingStage
import dev.humanonly.state.TrackState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Тесты диспетчера действий F4 (§F4, §5, §6.2). Инварианты: цепочка строго по рёбрам §5;
 * dry-run == факт (парити план); без бэкапа/подтверждения действий нет (хард-правило 5);
 * «не ИИ» полностью откатывает и уводит в human_confirmed; идемпотентность (no-op).
 * Все акк-операции — на записывающих fakes, никакой сети.
 */
class ActionDispatcherTest {

    /** Записывающие акк-операции. changed=что вернуть; call-лог по (op,trackId). */
    private class RecordingLibrary(private val changed: Boolean = true) : LibraryActions {
        val calls = mutableListOf<Pair<ActionOp, String>>()
        override fun dislike(trackId: String): Boolean { calls += ActionOp.DISLIKE to trackId; return changed }
        override fun undislike(trackId: String): Boolean { calls += ActionOp.UNDISLIKE to trackId; return changed }
        override fun like(trackId: String): Boolean { calls += ActionOp.RELIKE to trackId; return changed }
        override fun unlike(trackId: String): Boolean = changed
        override fun addToPlaylist(trackId: String, playlistKind: String): Boolean {
            calls += ActionOp.ADD_TO_PLAYLIST to trackId; return changed
        }
        override fun removeFromPlaylist(trackId: String, playlistKind: String): Boolean {
            calls += ActionOp.REMOVE_FROM_PLAYLIST to trackId; return changed
        }
    }

    private class RecordingSink : ActionSink {
        data class C(val id: String, val op: ActionOp, val from: TrackState, val to: TrackState, val changed: Boolean)
        val commits = mutableListOf<C>()
        override fun commit(trackId: String, op: ActionOp, from: TrackState, to: TrackState, changed: Boolean) {
            commits += C(trackId, op, from, to, changed)
        }
    }

    private class RecordingStages : StageListener {
        val events = mutableListOf<Pair<String, ProcessingStage>>()
        override fun onStage(trackId: String, stage: ProcessingStage) { events += trackId to stage }
    }

    private val okBackup = BackupGuard { "bkp-1" }
    private val playlist = "ai-playlist"

    // ── Хард-правило 5: dry-run → бэкап → подтверждение ─────────────────────────

    @Test
    fun `dry-run план равен фактически исполняемому (парити §16)`() {
        val d = ActionDispatcher(ActionMode.MOVE_TO_PLAYLIST, RecordingLibrary(), backup = okBackup, aiPlaylistKind = playlist)
        val cands = listOf(ActionCandidate("t1"), ActionCandidate("t2"))
        val planned = d.plan(cands)
        val refused = d.execute(cands, confirm = false)
        assertFalse(refused.executed)
        assertEquals(ActionDispatcher.REFUSED_NOT_CONFIRMED, refused.refusedReason)
        assertEquals(planned.steps, refused.plan.steps) // один и тот же план
    }

    @Test
    fun `без подтверждения ничего не делается`() {
        val lib = RecordingLibrary()
        val d = ActionDispatcher(ActionMode.DISLIKE_ONLY, lib, backup = okBackup)
        val r = d.execute(listOf(ActionCandidate("t1")), confirm = false)
        assertFalse(r.executed)
        assertEquals(ActionDispatcher.REFUSED_NOT_CONFIRMED, r.refusedReason)
        assertTrue(lib.calls.isEmpty(), "акк не должен трогаться без подтверждения")
    }

    @Test
    fun `без свежего бэкапа действие отклоняется (хард-правило 5)`() {
        val lib = RecordingLibrary()
        val d = ActionDispatcher(ActionMode.DISLIKE_ONLY, lib, backup = BackupGuard.None)
        val r = d.execute(listOf(ActionCandidate("t1")), confirm = true)
        assertFalse(r.executed)
        assertEquals(ActionDispatcher.REFUSED_NO_BACKUP, r.refusedReason)
        assertTrue(lib.calls.isEmpty(), "без бэкапа акк не трогаем")
    }

    // ── Режим (a): только дизлайк ──────────────────────────────────────────────

    @Test
    fun `режим DISLIKE_ONLY — один дизлайк, переход ai_confirmed→disliked`() {
        val lib = RecordingLibrary()
        val sink = RecordingSink()
        val d = ActionDispatcher(ActionMode.DISLIKE_ONLY, lib, sink, okBackup)
        val r = d.execute(listOf(ActionCandidate("t1")), confirm = true)
        assertTrue(r.executed)
        assertEquals("bkp-1", r.backupId)
        assertEquals(1, r.applied)
        assertEquals(listOf(ActionOp.DISLIKE to "t1"), lib.calls)
        assertEquals(1, sink.commits.size)
        assertEquals(TrackState.AI_CONFIRMED, sink.commits[0].from)
        assertEquals(TrackState.DISLIKED, sink.commits[0].to)
    }

    // ── Режим (b): дизлайк + плейлист ──────────────────────────────────────────

    @Test
    fun `режим MOVE_TO_PLAYLIST — дизлайк затем плейлист, рёбра §5`() {
        val lib = RecordingLibrary()
        val sink = RecordingSink()
        val d = ActionDispatcher(ActionMode.MOVE_TO_PLAYLIST, lib, sink, okBackup, aiPlaylistKind = playlist)
        val r = d.execute(listOf(ActionCandidate("t1")), confirm = true)
        assertTrue(r.executed)
        assertEquals(2, r.applied)
        assertEquals(listOf(ActionOp.DISLIKE to "t1", ActionOp.ADD_TO_PLAYLIST to "t1"), lib.calls)
        assertEquals(TrackState.AI_CONFIRMED, sink.commits[0].from)
        assertEquals(TrackState.DISLIKED, sink.commits[0].to)
        assertEquals(TrackState.DISLIKED, sink.commits[1].from)
        assertEquals(TrackState.MOVED_TO_PLAYLIST, sink.commits[1].to)
    }

    @Test
    fun `MOVE_TO_PLAYLIST без kind плейлиста → исключение конфигурации`() {
        try {
            ActionDispatcher(ActionMode.MOVE_TO_PLAYLIST, RecordingLibrary(), backup = okBackup)
            throw AssertionError("ожидалось IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("aiPlaylistKind"))
        }
    }

    // ── Resume / идемпотентность ───────────────────────────────────────────────

    @Test
    fun `resume — уже disliked в режиме b → остаётся только шаг плейлиста`() {
        val lib = RecordingLibrary()
        val d = ActionDispatcher(ActionMode.MOVE_TO_PLAYLIST, lib, backup = okBackup, aiPlaylistKind = playlist)
        val r = d.execute(listOf(ActionCandidate("t1", currentState = TrackState.DISLIKED)), confirm = true)
        assertEquals(1, r.plan.size)
        assertEquals(listOf(ActionOp.ADD_TO_PLAYLIST to "t1"), lib.calls)
    }

    @Test
    fun `уже в целевом состоянии → пустой план, акк не трогается`() {
        val lib = RecordingLibrary()
        val d = ActionDispatcher(ActionMode.MOVE_TO_PLAYLIST, lib, backup = okBackup, aiPlaylistKind = playlist)
        val r = d.execute(listOf(ActionCandidate("t1", currentState = TrackState.MOVED_TO_PLAYLIST)), confirm = true)
        assertTrue(r.executed)
        assertEquals(0, r.plan.size)
        assertTrue(lib.calls.isEmpty())
    }

    @Test
    fun `идемпотентность — операция вернула no-op → счётчик noop, не applied`() {
        val lib = RecordingLibrary(changed = false) // всё уже в целевом на стороне акка
        val d = ActionDispatcher(ActionMode.MOVE_TO_PLAYLIST, lib, backup = okBackup, aiPlaylistKind = playlist)
        val r = d.execute(listOf(ActionCandidate("t1")), confirm = true)
        assertTrue(r.executed)
        assertEquals(0, r.applied)
        assertEquals(2, r.noop)
    }

    // ── Откат «не ИИ» ──────────────────────────────────────────────────────────

    @Test
    fun `rollback из moved_to_playlist — снять плейлист и дизлайк, увести в human_confirmed`() {
        val lib = RecordingLibrary()
        val sink = RecordingSink()
        val d = ActionDispatcher(ActionMode.MOVE_TO_PLAYLIST, lib, sink, okBackup, aiPlaylistKind = playlist)
        val r = d.rollback("t1", TrackState.MOVED_TO_PLAYLIST)
        assertTrue(r.executed)
        // обратный порядок: убрать из плейлиста → снять дизлайк → вернуть лайк (дизлайк ЯМ снял лайк)
        assertEquals(
            listOf(ActionOp.REMOVE_FROM_PLAYLIST to "t1", ActionOp.UNDISLIKE to "t1", ActionOp.RELIKE to "t1"),
            lib.calls,
        )
        // финальный коммит — перевод в human_confirmed
        val last = sink.commits.last()
        assertEquals(ActionOp.WHITELIST, last.op)
        assertEquals(TrackState.MOVED_TO_PLAYLIST, last.from)
        assertEquals(TrackState.HUMAN_CONFIRMED, last.to)
    }

    @Test
    fun `rollback из disliked — снять дизлайк и вернуть лайк (дизлайк ЯМ снимает лайк)`() {
        val lib = RecordingLibrary()
        val sink = RecordingSink()
        val d = ActionDispatcher(ActionMode.DISLIKE_ONLY, lib, sink, okBackup)
        val r = d.rollback("t1", TrackState.DISLIKED)
        assertTrue(r.executed)
        assertEquals(listOf(ActionOp.UNDISLIKE to "t1", ActionOp.RELIKE to "t1"), lib.calls)
        assertEquals(TrackState.HUMAN_CONFIRMED, sink.commits.last().to)
    }

    @Test
    fun `rollback не требует бэкапа и подтверждения`() {
        // backup=None и никакого confirm — откат всё равно проходит (восстановление обратимо).
        val lib = RecordingLibrary()
        val d = ActionDispatcher(ActionMode.DISLIKE_ONLY, lib, backup = BackupGuard.None)
        val r = d.rollback("t1", TrackState.DISLIKED)
        assertTrue(r.executed)
        assertNull(r.refusedReason)
    }

    // ── Чекпоинты и агрегаты ───────────────────────────────────────────────────

    @Test
    fun `чекпоинт ACTING ставится по разу на трек`() {
        val stages = RecordingStages()
        val d = ActionDispatcher(ActionMode.MOVE_TO_PLAYLIST, RecordingLibrary(), backup = okBackup,
            stageListener = stages, aiPlaylistKind = playlist)
        d.execute(listOf(ActionCandidate("t1"), ActionCandidate("t2")), confirm = true)
        assertEquals(listOf("t1" to ProcessingStage.ACTING, "t2" to ProcessingStage.ACTING), stages.events)
    }

    @Test
    fun `батч из нескольких треков — счётчики суммируются`() {
        val lib = RecordingLibrary()
        val d = ActionDispatcher(ActionMode.DISLIKE_ONLY, lib, backup = okBackup)
        val r = d.execute(listOf(ActionCandidate("t1"), ActionCandidate("t2"), ActionCandidate("t3")), confirm = true)
        assertEquals(3, r.applied)
        assertEquals(3, r.plan.size)
        assertEquals(3, lib.calls.size)
    }
}
