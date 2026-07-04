package dev.humanonly.acceptance

import dev.humanonly.archive.ArchiveManifest
import dev.humanonly.archive.ArchiveEntry
import dev.humanonly.archive.Archiver
import dev.humanonly.archive.BlobStore
import dev.humanonly.archive.LocalStore
import dev.humanonly.archive.ManifestStore
import dev.humanonly.backup.BackupManifest
import dev.humanonly.backup.DryRunExecutor
import dev.humanonly.backup.LikedTrackEntry
import dev.humanonly.backup.RestoreAction
import dev.humanonly.backup.RestoreExecutor
import dev.humanonly.backup.RestoreOutcome
import dev.humanonly.backup.RestorePlan
import dev.humanonly.backup.RestorePlanner
import dev.humanonly.detector.LinearDetector
import dev.humanonly.detector.LinearWeights
import dev.humanonly.detector.PrecisionGate
import dev.humanonly.detector.PrecisionStats
import dev.humanonly.pipeline.ActionCandidate
import dev.humanonly.pipeline.ActionDispatcher
import dev.humanonly.pipeline.ActionMode
import dev.humanonly.pipeline.ActionOp
import dev.humanonly.pipeline.ActionSink
import dev.humanonly.pipeline.BackupGuard
import dev.humanonly.pipeline.LibraryActions
import dev.humanonly.state.TrackState
import dev.humanonly.state.TrackStateMachine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Сквозные acceptance-инварианты MVP (ТЗ §16). Увязывают уже собранные компоненты на in-memory
 * fakes (ни сети, ни диска, ни акка) — гоняются на CI как страховка ключевых гарантий:
 *  - Акк: dry-run удаления == факт; дизлайк-батч идемпотентен; «не ИИ» откатывает полностью.
 *  - Восстановление: dry restore diff корректен; restore малого набора == исходное.
 *  - Отказоустойчивость: повтор архивации не плодит дублей (resume по хэшу).
 *  - State-machine: запрещённые переходы отклоняются, разрешённые проходят.
 *  - Precision-gate: режим удаления открывается только при ≥200 / ≥90%.
 *  - Детект: 1000 последовательных инференсов детерминированы (стабильность, аналог memory-leak-гейта).
 */
class AcceptanceTest {

    // Идемпотентная библиотека с журналом вызовов: add/remove возвращают «фактически изменилось».
    private class StatefulLibrary : LibraryActions {
        val disliked = HashSet<String>()
        val liked = HashSet<String>()
        val playlist = HashSet<String>()
        val calls = mutableListOf<ActionOp>()
        override fun dislike(trackId: String): Boolean { calls += ActionOp.DISLIKE; return disliked.add(trackId) }
        override fun undislike(trackId: String): Boolean { calls += ActionOp.UNDISLIKE; return disliked.remove(trackId) }
        override fun like(trackId: String): Boolean { calls += ActionOp.RELIKE; return liked.add(trackId) }
        override fun addToPlaylist(trackId: String, playlistKind: String): Boolean {
            calls += ActionOp.ADD_TO_PLAYLIST; return playlist.add(trackId)
        }
        override fun removeFromPlaylist(trackId: String, playlistKind: String): Boolean {
            calls += ActionOp.REMOVE_FROM_PLAYLIST; return playlist.remove(trackId)
        }
    }

    private val okBackup = BackupGuard { "bkp-1" }

    // ── §16 Акк: dry-run удаления == фактическое ───────────────────────────────

    @Test
    fun `dry-run действий побайтово совпадает с фактически выполненными операциями`() {
        val cands = listOf(ActionCandidate("t1"), ActionCandidate("t2"))
        val planner = ActionDispatcher(ActionMode.MOVE_TO_PLAYLIST, StatefulLibrary(), backup = okBackup, aiPlaylistKind = "p")
        val plan = planner.plan(cands)
        val dry = planner.execute(cands, confirm = false)
        assertFalse(dry.executed)
        assertEquals(plan.steps, dry.plan.steps) // dry-run отдаёт тот же план

        // Факт: те же операции в том же порядке, что и в плане.
        val lib = StatefulLibrary()
        ActionDispatcher(ActionMode.MOVE_TO_PLAYLIST, lib, backup = okBackup, aiPlaylistKind = "p")
            .execute(cands, confirm = true)
        assertEquals(plan.steps.map { it.op }, lib.calls)
    }

    // ── §16 Акк: дизлайк-батч идемпотентен ─────────────────────────────────────

    @Test
    fun `повторный дизлайк-батч идемпотентен — состояние не меняется`() {
        val lib = StatefulLibrary()
        val d = ActionDispatcher(ActionMode.DISLIKE_ONLY, lib, backup = okBackup)
        val cands = listOf(ActionCandidate("t1"), ActionCandidate("t2"))

        val first = d.execute(cands, confirm = true)
        assertEquals(2, first.applied)
        assertEquals(setOf("t1", "t2"), lib.disliked)

        val second = d.execute(cands, confirm = true)
        assertEquals(0, second.applied)
        assertEquals(2, second.noop) // «уже так» = success
        assertEquals(setOf("t1", "t2"), lib.disliked) // без изменений
    }

    // ── §16 Акк: «не ИИ» откатывает полностью ──────────────────────────────────

    @Test
    fun `не ИИ полностью откатывает действия и уводит в human_confirmed`() {
        val lib = StatefulLibrary()
        val sink = RecordingActionSink()
        val d = ActionDispatcher(ActionMode.MOVE_TO_PLAYLIST, lib, sink, okBackup, aiPlaylistKind = "p")
        d.execute(listOf(ActionCandidate("t1")), confirm = true)
        assertTrue("t1" in lib.disliked && "t1" in lib.playlist)

        d.rollback("t1", TrackState.MOVED_TO_PLAYLIST)
        assertTrue(lib.disliked.isEmpty(), "дизлайк снят")
        assertTrue(lib.playlist.isEmpty(), "убран из плейлиста")
        assertTrue("t1" in lib.liked, "лайк возвращён (дизлайк ЯМ его снимает — откат обязан вернуть)")
        assertEquals(TrackState.HUMAN_CONFIRMED, sink.last().to)
    }

    private class RecordingActionSink : ActionSink {
        data class C(val to: TrackState)
        private val commits = mutableListOf<C>()
        override fun commit(trackId: String, op: ActionOp, from: TrackState, to: TrackState, changed: Boolean) {
            commits += C(to)
        }
        fun last() = commits.last()
    }

    // ── §16 Восстановление: dry diff корректен + restore малого набора == исходное ─

    @Test
    fun `dry restore diff корректен и реальный restore возвращает исходный набор лайков`() {
        val backup = BackupManifest(
            createdAt = 0,
            likes = listOf(LikedTrackEntry("a"), LikedTrackEntry("b"), LikedTrackEntry("c")),
        )
        val current = setOf("a") // b, c потеряны
        val plan = RestorePlanner.plan(current, backup)

        // Dry: намерения без мутации.
        val dry = DryRunExecutor(liveLikes = current).execute(plan)
        assertEquals(setOf("b", "c"), dry.reAdded.toSet())
        assertTrue(dry.removed.isEmpty())

        // Факт: применяем план → набор лайков становится равен бэкапу.
        val likes = current.toMutableSet()
        val real = MutatingRestoreExecutor(likes).execute(plan)
        assertEquals(setOf("a", "b", "c"), likes)
        // Парити намерений: то, что dry обещал добавить, факт и добавил.
        assertEquals(dry.reAdded.toSet(), real.reAdded.toSet())
    }

    /** Настоящий executor поверх мутируемого набора лайков — для проверки «restore == исходное». */
    private class MutatingRestoreExecutor(private val likes: MutableSet<String>) : RestoreExecutor {
        override fun execute(plan: RestorePlan): RestoreOutcome {
            val reAdded = ArrayList<String>(); val removed = ArrayList<String>(); val noop = ArrayList<String>()
            for (s in plan.steps) when (s.action) {
                RestoreAction.RE_ADD -> if (likes.add(s.trackId)) reAdded += s.trackId else noop += s.trackId
                RestoreAction.REMOVE -> if (likes.remove(s.trackId)) removed += s.trackId else noop += s.trackId
            }
            return RestoreOutcome(reAdded, removed, noop)
        }
    }

    // ── §16 Отказоустойчивость: повтор архивации без дублей ─────────────────────

    @Test
    fun `повторная архивация того же трека не плодит дублей (resume по хэшу)`() {
        val blobs = MemBlob()
        val local = MemLocal(mutableMapOf("t1" to byteArrayOf(1, 2, 3)))
        val manifest = MemManifest()
        val archiver = Archiver(blobs, local, manifest)
        val cand = dev.humanonly.archive.ArchiveCandidate(
            "t1", "hashhash", "flac", "lossless", "clean", "det-v1",
        )
        val r1 = archiver.run(listOf(cand))
        assertEquals(1, r1.uploaded)

        val r2 = archiver.run(listOf(cand)) // resume: блоб уже есть
        assertEquals(1, r2.deduped)
        assertEquals(1, blobs.putCalls, "второй раз не заливаем")
        assertEquals(1, manifest.m.entries.size, "одна запись, без дублей")
    }

    private class MemBlob : BlobStore {
        val store = HashMap<String, ByteArray>(); var putCalls = 0
        override fun exists(path: String) = store.containsKey(path)
        override fun put(path: String, content: ByteArray): Boolean { putCalls++; store[path] = content; return true }
        override fun get(path: String) = store[path]
    }
    private class MemLocal(val files: MutableMap<String, ByteArray>) : LocalStore {
        override fun read(trackId: String) = files[trackId]
        override fun delete(trackId: String) { files.remove(trackId) }
    }
    private class MemManifest : ManifestStore {
        var m = ArchiveManifest()
        override fun load() = m
        override fun save(manifest: ArchiveManifest) { m = manifest }
    }

    // ── §16 State-machine: запрещённые переходы отклоняются ─────────────────────

    @Test
    fun `запрещённые переходы §5 отклоняются, разрешённые проходят`() {
        // Запрещённые:
        assertFalse(TrackStateMachine.canTransition(TrackState.UNKNOWN, TrackState.ARCHIVED))
        assertFalse(TrackStateMachine.canTransition(TrackState.CLEAN, TrackState.AI_CONFIRMED))
        assertFalse(TrackStateMachine.canTransition(TrackState.REVIEW_REQUIRED, TrackState.DISLIKED))
        // Разрешённые (ключевой путь MVP):
        assertTrue(TrackStateMachine.canTransition(TrackState.UNKNOWN, TrackState.REVIEW_REQUIRED))
        assertTrue(TrackStateMachine.canTransition(TrackState.AI_CONFIRMED, TrackState.DISLIKED))
        assertTrue(TrackStateMachine.canTransition(TrackState.DOWNLOADED, TrackState.ARCHIVED))
        assertTrue(TrackStateMachine.canTransition(TrackState.SUSPECTED, TrackState.HUMAN_CONFIRMED))
    }

    // ── §16 Precision-gate: режим удаления только при ≥200 / ≥90% ───────────────

    @Test
    fun `precision-gate открывает удаление только при достаточной точности`() {
        val gate = PrecisionGate()
        assertFalse(gate.isRemovalAllowed(PrecisionStats(150, 5)))   // мало выборки
        assertFalse(gate.isRemovalAllowed(PrecisionStats(170, 30)))  // 200, но 0.85
        assertTrue(gate.isRemovalAllowed(PrecisionStats(185, 15)))   // 200 и 0.925
    }

    // ── §16 Детект: стабильность 1000 инференсов ────────────────────────────────

    @Test
    fun `1000 последовательных инференсов детерминированы (без дрейфа)`() {
        val det = LinearDetector(LinearWeights(floatArrayOf(0.1f, -0.2f, 0.3f, 0.05f), 0.02f))
        val feat = floatArrayOf(1.0f, 2.0f, 3.0f, -1.0f)
        val expected = det.score(feat)
        repeat(1000) { assertEquals(expected, det.score(feat)) } // бит-идентично на всех итерациях
    }
}
