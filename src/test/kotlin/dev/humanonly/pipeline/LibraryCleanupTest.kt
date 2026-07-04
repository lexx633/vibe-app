package dev.humanonly.pipeline

import dev.humanonly.state.TrackState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Тесты чистки живой библиотеки (§F4 + чистка мёртвых). Инварианты: скан классифицирует без мутаций;
 * без подтверждения/бэкапа деструктив не идёт (хард-правило 5); мёртвые снимаются лайком БЕЗ дизлайка;
 * гейт-ИИ дизлайкается + в плейлист «детект ИИ»; серая зона — снимается с лайков + в плейлист «непонятно»
 * (потому тоже требует бэкапа); идемпотентность (no-op). Всё на fakes, без сети.
 */
class LibraryCleanupTest {

    /** Записывающие акк-операции. changed=что вернуть; call-лог по (op, trackId). */
    private class RecordingLibrary(private val changed: Boolean = true) : LibraryActions {
        val calls = mutableListOf<Pair<String, String>>() // (op, trackId)
        val playlistAdds = mutableListOf<Pair<String, String>>() // (trackId, kind)
        override fun dislike(trackId: String): Boolean { calls += "dislike" to trackId; return changed }
        override fun undislike(trackId: String): Boolean { calls += "undislike" to trackId; return changed }
        override fun like(trackId: String): Boolean { calls += "like" to trackId; return changed }
        override fun unlike(trackId: String): Boolean { calls += "unlike" to trackId; return changed }
        override fun addToPlaylist(trackId: String, playlistKind: String): Boolean {
            calls += "addToPlaylist" to trackId; playlistAdds += trackId to playlistKind; return changed
        }
        override fun removeFromPlaylist(trackId: String, playlistKind: String): Boolean {
            calls += "removeFromPlaylist" to trackId; return changed
        }
    }

    private class RecordingSink : CleanupSink {
        val dead = mutableListOf<String>()
        val ai = mutableListOf<String>()
        val gray = mutableListOf<String>()
        override fun onDead(trackId: String, from: TrackState, unliked: Boolean) { dead += trackId }
        override fun onAiMoved(trackId: String, disliked: Boolean, added: Boolean) { ai += trackId }
        override fun onGrayReview(trackId: String, unliked: Boolean, added: Boolean) { gray += trackId }
    }

    /** Классификатор на карте: id → bucket. Неизвестный id → CLEAN. */
    private fun classifierOf(vararg pairs: Pair<String, CleanupBucket>): TrackClassifier {
        val map = pairs.toMap()
        return TrackClassifier { id -> map[id] ?: CleanupBucket.CLEAN }
    }

    private val freshBackup = BackupGuard { "likes-123.json" }
    private val aiKind = "ai-1000"
    private val grayKind = "gray-2000"

    private fun cleanup(
        library: LibraryActions,
        backup: BackupGuard = freshBackup,
        sink: CleanupSink = CleanupSink.None,
    ) = LibraryCleanup(library, aiKind, grayKind, backup, sink)

    // ── скан: классификация без мутаций ──────────────────────────────────────

    @Test
    fun `scan раскладывает лайки по корзинам и не трогает акк`() {
        val lib = RecordingLibrary()
        val classifier = classifierOf(
            "dead1" to CleanupBucket.DEAD,
            "ai1" to CleanupBucket.AI_GATE,
            "gray1" to CleanupBucket.GRAY,
            "clean1" to CleanupBucket.CLEAN,
        )
        val plan = cleanup(lib).scan(listOf("dead1", "ai1", "gray1", "clean1"), classifier)

        assertEquals(listOf("dead1"), plan.dead)
        assertEquals(listOf("ai1"), plan.aiGate)
        assertEquals(listOf("gray1"), plan.gray)
        assertEquals(listOf("clean1"), plan.clean)
        assertEquals(mapOf(CleanupBucket.DEAD to 1, CleanupBucket.AI_GATE to 1, CleanupBucket.GRAY to 1, CleanupBucket.CLEAN to 1), plan.counts)
        assertTrue(plan.touchesLikes, "есть мёртвые/гейт → бэкап обязателен")
        assertTrue(lib.calls.isEmpty(), "скан ничего не мутирует")
    }

    @Test
    fun `scan зовёт onScanned на каждый трек по порядку`() {
        val seen = mutableListOf<Pair<Int, CleanupBucket>>()
        val classifier = classifierOf("a" to CleanupBucket.DEAD, "b" to CleanupBucket.CLEAN)
        cleanup(RecordingLibrary()).scan(listOf("a", "b"), classifier) { i, _, b -> seen += i to b }
        assertEquals(listOf(0 to CleanupBucket.DEAD, 1 to CleanupBucket.CLEAN), seen)
    }

    @Test
    fun `scan останавливается кооперативно по shouldStop — план частичный`() {
        val classifier = classifierOf(
            "a" to CleanupBucket.CLEAN,
            "b" to CleanupBucket.CLEAN,
            "c" to CleanupBucket.CLEAN,
        )
        var scanned = 0
        // Просим остановиться после первого просканированного трека. shouldStop проверяется ДО классификации:
        // «a» проходит (scanned=0), затем «b» упирается в stop (scanned=1) — классификатора «b»/«c» не будет.
        val plan = cleanup(RecordingLibrary()).scan(
            listOf("a", "b", "c"),
            classifier,
            shouldStop = { scanned >= 1 },
        ) { _, _, _ -> scanned++ }

        assertEquals(1, plan.items.size)
        assertEquals("a", plan.items.first().trackId)
    }

    // ── хард-правило 5: без confirm/бэкапа деструктив не идёт ─────────────────

    @Test
    fun `execute без confirm — отказ, ни одной мутации`() {
        val lib = RecordingLibrary()
        val plan = CleanupPlan(listOf(CleanupScanItem("dead1", CleanupBucket.DEAD)))
        val res = cleanup(lib).execute(plan, confirm = false)

        assertFalse(res.executed)
        assertEquals(LibraryCleanup.REFUSED_NOT_CONFIRMED, res.refusedReason)
        assertTrue(lib.calls.isEmpty())
    }

    @Test
    fun `execute с confirm но без бэкапа — отказ, когда план трогает лайки`() {
        val lib = RecordingLibrary()
        val plan = CleanupPlan(listOf(CleanupScanItem("ai1", CleanupBucket.AI_GATE)))
        val res = cleanup(lib, backup = BackupGuard.None).execute(plan, confirm = true)

        assertFalse(res.executed)
        assertEquals(LibraryCleanup.REFUSED_NO_BACKUP, res.refusedReason)
        assertTrue(lib.calls.isEmpty(), "без бэкапа — ноль акк-операций")
    }

    @Test
    fun `gray-only план теперь требует бэкапа — серую снимаем с лайков`() {
        val lib = RecordingLibrary()
        val plan = CleanupPlan(listOf(CleanupScanItem("gray1", CleanupBucket.GRAY)))
        val res = cleanup(lib, backup = BackupGuard.None).execute(plan, confirm = true)

        assertFalse(res.executed, "серая теперь снимает лайк → бэкап обязателен (хард-правило 5)")
        assertEquals(LibraryCleanup.REFUSED_NO_BACKUP, res.refusedReason)
        assertTrue(lib.calls.isEmpty(), "без бэкапа — ноль акк-операций")
    }

    // ── исполнение по корзинам ───────────────────────────────────────────────

    @Test
    fun `dead снимает лайк без дизлайка и помечает sink`() {
        val lib = RecordingLibrary()
        val sink = RecordingSink()
        val plan = CleanupPlan(listOf(CleanupScanItem("dead1", CleanupBucket.DEAD)))
        val res = cleanup(lib, sink = sink).execute(plan, confirm = true)

        assertEquals(1, res.deadUnliked)
        assertEquals("likes-123.json", res.backupId)
        assertEquals(listOf("unlike" to "dead1"), lib.calls, "мёртвый — только unlike, без dislike")
        assertEquals(listOf("dead1"), sink.dead)
    }

    @Test
    fun `ai gate дизлайкает и добавляет в плейлист детект-ИИ`() {
        val lib = RecordingLibrary()
        val sink = RecordingSink()
        val plan = CleanupPlan(listOf(CleanupScanItem("ai1", CleanupBucket.AI_GATE)))
        val res = cleanup(lib, sink = sink).execute(plan, confirm = true)

        assertEquals(1, res.aiMoved)
        assertEquals(listOf("dislike" to "ai1", "addToPlaylist" to "ai1"), lib.calls)
        assertEquals(listOf("ai1" to aiKind), lib.playlistAdds, "гейт-ИИ в плейлист «детект ИИ»")
        assertEquals(listOf("ai1"), sink.ai)
    }

    @Test
    fun `gray снимает лайк и добавляет в отдельный плейлист непонятно`() {
        val lib = RecordingLibrary()
        val sink = RecordingSink()
        val plan = CleanupPlan(listOf(CleanupScanItem("gray1", CleanupBucket.GRAY)))
        val res = cleanup(lib, sink = sink).execute(plan, confirm = true)

        assertEquals(1, res.grayMoved)
        assertEquals("likes-123.json", res.backupId, "серая теперь деструктив → под бэкапом")
        assertEquals(listOf("gray1" to grayKind), lib.playlistAdds, "серая в плейлист «непонятно», НЕ в «детект ИИ»")
        // порядок важен: сначала addToPlaylist (сохранить ссылку), потом unlike (снять лайк)
        assertEquals(listOf("addToPlaylist" to "gray1", "unlike" to "gray1"), lib.calls)
        assertFalse(lib.calls.any { it.first == "dislike" }, "серую не дизлайкаем — только снимаем лайк")
        assertEquals(listOf("gray1"), sink.gray)
        assertTrue(sink.dead.isEmpty() && sink.ai.isEmpty())
    }

    @Test
    fun `смешанный план — все три корзины отрабатывают, clean не трогается`() {
        val lib = RecordingLibrary()
        val plan = CleanupPlan(
            listOf(
                CleanupScanItem("dead1", CleanupBucket.DEAD),
                CleanupScanItem("ai1", CleanupBucket.AI_GATE),
                CleanupScanItem("gray1", CleanupBucket.GRAY),
                CleanupScanItem("clean1", CleanupBucket.CLEAN),
            ),
        )
        val res = cleanup(lib).execute(plan, confirm = true)

        assertEquals(1, res.deadUnliked)
        assertEquals(1, res.aiMoved)
        assertEquals(1, res.grayMoved)
        assertFalse(lib.calls.any { it.second == "clean1" }, "чистый трек не трогаем")
    }

    @Test
    fun `идемпотентность — акк отвечает no-op, счётчик noop`() {
        val lib = RecordingLibrary(changed = false)
        val plan = CleanupPlan(listOf(CleanupScanItem("dead1", CleanupBucket.DEAD)))
        val res = cleanup(lib).execute(plan, confirm = true)

        assertEquals(0, res.deadUnliked)
        assertEquals(1, res.noop)
    }
}
