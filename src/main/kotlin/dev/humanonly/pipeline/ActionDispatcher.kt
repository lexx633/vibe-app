package dev.humanonly.pipeline

import dev.humanonly.state.ProcessingStage
import dev.humanonly.state.TrackState
import dev.humanonly.state.TrackStateMachine

/**
 * Диспетчер действий с AI-треками (ТЗ §F4, §5, §6.2). Гоняет подтверждённый как ИИ трек по цепочке
 * §5 `ai_confirmed → disliked → moved_to_playlist` согласно выбранному режиму:
 *   (a) [ActionMode.DISLIKE_ONLY]  — только дизлайк;
 *   (b) [ActionMode.MOVE_TO_PLAYLIST] (дефолт) — дизлайк + перенос в плейлист «Определены как ИИ треки».
 * Режим (c) удаление (`removal_pending → removed_from_likes`) отложен до precision-gate (§10, v1.1) —
 * здесь его нет намеренно.
 *
 * Хард-правило 5 (деструктив/действия с акком): **dry-run → бэкап → подтверждение**. Реализовано так:
 *   - [plan] — чистое планирование без единой побочки: ровно те шаги, что выполнит [execute] (парити);
 *   - [execute] отказывается действовать без свежего бэкапа ([BackupGuard]) и без явного `confirm=true`,
 *     возвращая тот же план как «намерение» (это и есть dry-run: `plan(x) == execute(x, confirm=false).plan`).
 *
 * Кнопка «не ИИ» ([rollback]) — полный откат действий + перевод в `human_confirmed` (whitelist, §5:
 * «любое ai-состояние → human_confirmed»). Обратима, бэкапа/подтверждения не требует (восстановление).
 *
 * Идемпотентность (§6.2): реальные операции ([LibraryActions]) повторно безопасны — «уже так» = no-op,
 * счётчик [ActionResult.noop]. Переход каждого шага валидируется [TrackStateMachine] (хард-правило 10).
 * Чистая оркестрация: сеть/акк — за [LibraryActions], персист — за [ActionSink]; тестируется на fakes.
 */
class ActionDispatcher(
    private val mode: ActionMode,
    private val library: LibraryActions,
    private val sink: ActionSink = ActionSink.None,
    private val backup: BackupGuard = BackupGuard.None,
    private val stageListener: StageListener = StageListener.None,
    /** kind плейлиста «Определены как ИИ треки» — обязателен для режима (b). */
    private val aiPlaylistKind: String = "",
) {
    init {
        require(mode != ActionMode.MOVE_TO_PLAYLIST || aiPlaylistKind.isNotBlank()) {
            "для режима MOVE_TO_PLAYLIST нужен непустой aiPlaylistKind"
        }
    }

    /**
     * Dry-run (§F4 «dry-run → …»): что будет сделано, без единой побочки. Ровно эти шаги применит
     * [execute] при подтверждении (парити с фактическим прогоном — инвариант §16).
     */
    fun plan(candidates: Iterable<ActionCandidate>): ActionPlan =
        ActionPlan(candidates.flatMap { stepsFor(mode, it.trackId, it.currentState) })

    /**
     * Фактическое исполнение. Требует (хард-правило 5): свежий бэкап И `confirm=true`. Иначе — отказ
     * с тем же планом (dry-run). Каждый шаг: валидируем переход → зовём акк-операцию → коммитим.
     */
    fun execute(candidates: Iterable<ActionCandidate>, confirm: Boolean): ActionResult {
        val plan = plan(candidates)
        if (!confirm) return ActionResult(plan, executed = false, refusedReason = REFUSED_NOT_CONFIRMED)
        val backupId = backup.latestBackupId()
            ?: return ActionResult(plan, executed = false, refusedReason = REFUSED_NO_BACKUP)

        var applied = 0; var noop = 0
        // Группируем по треку — чекпоинт ACTING один раз на трек, шаги идут по порядку цепочки.
        for ((trackId, steps) in plan.steps.groupBy { it.trackId }) {
            stageListener.onStage(trackId, ProcessingStage.ACTING)
            for (step in steps) {
                TrackStateMachine.validateTransition(step.from, step.to)
                val changed = invoke(step.op, trackId)
                if (changed) applied++ else noop++
                sink.commit(trackId, step.op, step.from, step.to, changed)
            }
        }
        return ActionResult(plan, executed = true, refusedReason = null, applied = applied, noop = noop, backupId = backupId)
    }

    /**
     * Откат действий «не ИИ» (§F4/§5): снять дизлайк / убрать из плейлиста в обратном порядке и
     * перевести в `human_confirmed`. Обратимо → без бэкапа/подтверждения. Идемпотентно.
     */
    fun rollback(trackId: String, currentState: TrackState): ActionResult {
        val undo = undoStepsFor(trackId, currentState)
        val plan = ActionPlan(undo)
        var applied = 0; var noop = 0
        if (undo.isNotEmpty()) stageListener.onStage(trackId, ProcessingStage.ACTING)
        for (step in undo) {
            val changed = invoke(step.op, trackId)
            if (changed) applied++ else noop++
        }
        // Перевод в human_confirmed валидируется (любое ai-состояние → human_confirmed, §5).
        TrackStateMachine.validateTransition(currentState, TrackState.HUMAN_CONFIRMED)
        sink.commit(trackId, ActionOp.WHITELIST, currentState, TrackState.HUMAN_CONFIRMED, changed = true)
        return ActionResult(plan, executed = true, refusedReason = null, applied = applied, noop = noop)
    }

    private fun invoke(op: ActionOp, trackId: String): Boolean = when (op) {
        ActionOp.DISLIKE -> library.dislike(trackId)
        ActionOp.ADD_TO_PLAYLIST -> library.addToPlaylist(trackId, aiPlaylistKind)
        ActionOp.UNDISLIKE -> library.undislike(trackId)
        ActionOp.REMOVE_FROM_PLAYLIST -> library.removeFromPlaylist(trackId, aiPlaylistKind)
        ActionOp.WHITELIST -> false // сам перевод состояния — без акк-операции
    }

    /** Шаги вперёд по цепочке режима, начиная с [from] (уже пройденные — пропускаются, идемпотентность). */
    private fun stepsFor(mode: ActionMode, trackId: String, from: TrackState): List<ActionStep> {
        val chain = FORWARD_CHAIN.getValue(mode)
        val fromRank = CHAIN_RANK[from] ?: 0 // не-цепочечное состояние трактуем как старт (ai_confirmed)
        return chain.filter { (CHAIN_RANK.getValue(it.to)) > fromRank }
            .map { it.copy(trackId = trackId) }
    }

    /** Обратные шаги: отменить уже применённые действия для [currentState] в обратном порядке. */
    private fun undoStepsFor(trackId: String, currentState: TrackState): List<ActionStep> {
        val rank = CHAIN_RANK[currentState] ?: 0
        val undo = ArrayList<ActionStep>(2)
        if (rank >= CHAIN_RANK.getValue(TrackState.MOVED_TO_PLAYLIST)) {
            undo += ActionStep(trackId, ActionOp.REMOVE_FROM_PLAYLIST, currentState, currentState)
        }
        if (rank >= CHAIN_RANK.getValue(TrackState.DISLIKED)) {
            undo += ActionStep(trackId, ActionOp.UNDISLIKE, currentState, currentState)
        }
        return undo
    }

    companion object {
        const val REFUSED_NOT_CONFIRMED = "not_confirmed"
        const val REFUSED_NO_BACKUP = "no_backup"

        /** Ранг узла в цепочке чистки §5 (для «сколько уже пройдено» и обратного отката). */
        private val CHAIN_RANK: Map<TrackState, Int> = mapOf(
            TrackState.AI_CONFIRMED to 0,
            TrackState.DISLIKED to 1,
            TrackState.MOVED_TO_PLAYLIST to 2,
        )

        // Полные прямые цепочки режимов (trackId проставляется в stepsFor). from→to — рёбра §5.
        private val FORWARD_CHAIN: Map<ActionMode, List<ActionStep>> = mapOf(
            ActionMode.DISLIKE_ONLY to listOf(
                ActionStep("", ActionOp.DISLIKE, TrackState.AI_CONFIRMED, TrackState.DISLIKED),
            ),
            ActionMode.MOVE_TO_PLAYLIST to listOf(
                ActionStep("", ActionOp.DISLIKE, TrackState.AI_CONFIRMED, TrackState.DISLIKED),
                ActionStep("", ActionOp.ADD_TO_PLAYLIST, TrackState.DISLIKED, TrackState.MOVED_TO_PLAYLIST),
            ),
        )
    }
}

/** Режим действий F4 (§F4). Режим (c) удаление отложен до precision-gate — здесь его нет. */
enum class ActionMode {
    /** (a) только авто-дизлайк. */
    DISLIKE_ONLY,

    /** (b, дефолт) дизлайк + перенос в плейлист «Определены как ИИ треки». */
    MOVE_TO_PLAYLIST,
}

/** Трек-кандидат на действие: подтверждён как ИИ (по умолчанию `ai_confirmed`), опц. resume с середины цепочки. */
data class ActionCandidate(
    val trackId: String,
    val currentState: TrackState = TrackState.AI_CONFIRMED,
)

/** Атомарная операция шага (акк-эффект + узел графа). WHITELIST — чистый перевод состояния «не ИИ». */
enum class ActionOp { DISLIKE, ADD_TO_PLAYLIST, UNDISLIKE, REMOVE_FROM_PLAYLIST, WHITELIST }

/** Шаг плана: одна операция + ребро §5 (from→to). Для undo from==to (только акк-эффект, без смены узла). */
data class ActionStep(
    val trackId: String,
    val op: ActionOp,
    val from: TrackState,
    val to: TrackState,
)

/** План действий (dry-run): упорядоченный список шагов. Пустой = делать нечего (уже в цели/идемпотентно). */
data class ActionPlan(val steps: List<ActionStep>) {
    val size: Int get() = steps.size
    val trackIds: Set<String> get() = steps.mapTo(LinkedHashSet()) { it.trackId }
}

/** Итог прогона [ActionDispatcher]. При отказе `executed=false` + [refusedReason], но [plan] всегда заполнен. */
data class ActionResult(
    val plan: ActionPlan,
    val executed: Boolean,
    val refusedReason: String? = null,
    val applied: Int = 0,
    val noop: Int = 0,
    val backupId: String? = null,
)

/**
 * Реальные операции с аккаунтом ЯМ (каждая идемпотентна, §6.2: «уже так» = success). Возвращает
 * `true`, если состояние фактически изменилось; `false` — если уже было целевым (no-op).
 * Реализация через YandexClient — отдельной подзадачей; здесь только контракт для тестов на fakes.
 */
interface LibraryActions {
    fun dislike(trackId: String): Boolean
    fun undislike(trackId: String): Boolean
    fun addToPlaylist(trackId: String, playlistKind: String): Boolean
    fun removeFromPlaylist(trackId: String, playlistKind: String): Boolean

    /** Заглушка «ничего не делаю» — все операции no-op (для dry-run без акка / базовых тестов). */
    object Noop : LibraryActions {
        override fun dislike(trackId: String): Boolean = false
        override fun undislike(trackId: String): Boolean = false
        override fun addToPlaylist(trackId: String, playlistKind: String): Boolean = false
        override fun removeFromPlaylist(trackId: String, playlistKind: String): Boolean = false
    }
}

/**
 * Гарант свежего бэкапа перед необратимыми действиями (хард-правило 5, §F7). [latestBackupId] возвращает
 * id актуального pre-destructive бэкапа или `null`, если бэкапа нет → [ActionDispatcher.execute] откажет.
 */
fun interface BackupGuard {
    fun latestBackupId(): String?

    companion object {
        /** Бэкапа нет — действия будут отклонены (безопасный дефолт). */
        val None = BackupGuard { null }
    }
}

/** Приёмник шага действия: персист перехода состояния + audit (§12, без PII). Реализация — в БД-слое. */
fun interface ActionSink {
    fun commit(trackId: String, op: ActionOp, from: TrackState, to: TrackState, changed: Boolean)

    companion object {
        val None = ActionSink { _, _, _, _, _ -> }
    }
}
