package dev.humanonly.pipeline

import dev.humanonly.state.ProcessingStage
import dev.humanonly.state.TrackState
import dev.humanonly.state.TrackStateMachine

/**
 * Чистка ЖИВОЙ библиотеки лайков (рабочий акк, §F4 + чистка мёртвых лайков). Разбирает лайки на 4 корзины
 * ([CleanupBucket]) и применяет к каждой своё действие. Деструктив к лайкам (снятие/дизлайк) — под
 * хард-правилом 5: [execute] требует `confirm=true` И свежий бэкап ([BackupGuard]), иначе отказ с тем же
 * планом (dry-run). Каждый переход состояния валидируется [TrackStateMachine] (хард-правило 10).
 *
 * Действия по корзинам (решения Owner 2026-07-04):
 *   - [CleanupBucket.DEAD]   — трек удалён/недоступен в ЯМ (`available=false`/пустые метаданные): снять лайк
 *     [LibraryActions.unlike] БЕЗ дизлайка (мёртвый трек не «плохой», просто нет) → `is_dead`.
 *   - [CleanupBucket.AI_GATE] — slopless-гейт (артист в базе AI, `suspected`): дизлайк + перенос в плейлист
 *     «детект ИИ» (цепочка §5 `ai_confirmed → disliked → moved_to_playlist`). Живой дизлайк ЯМ снимает лайк.
 *   - [CleanupBucket.GRAY]   — серая зона (`review_required`: метаданные пахнут ИИ, гейт мимо, аудио отложено):
 *     добавить в ОТДЕЛЬНЫЙ плейлист «непонятно — ИИ или человек» И СНЯТЬ лайк (без дизлайка) — чтобы прогон
 *     скачивания на них не спотыкался, а человек потом прошёлся по плейлисту и вручную проставил лайк/дизлайк
 *     (решение Owner 2026-07-04). Деструктив к лайкам → серая зона тоже требует бэкапа.
 *   - [CleanupBucket.CLEAN]  — чисто: не трогаем.
 *
 * Классификация ([scan]) отделена от исполнения ([execute]): сначала полный dry-run со счётчиками (без единой
 * мутации акка), потом — по подтверждению — применение. Идемпотентность (§6.2): повтор безопасен (no-op).
 * Чистая оркестрация: сеть/акк — за [LibraryActions] и [TrackClassifier], персист — за [CleanupSink].
 */
class LibraryCleanup(
    private val library: LibraryActions,
    /** kind плейлиста «детект ИИ» (уверенные гейт-хиты). Обязателен, если план содержит AI_GATE. */
    private val aiPlaylistKind: String,
    /** kind плейлиста «непонятно» (серая зона на ревью). Обязателен, если план содержит GRAY. */
    private val grayPlaylistKind: String,
    private val backup: BackupGuard = BackupGuard.None,
    private val sink: CleanupSink = CleanupSink.None,
    private val stageListener: StageListener = StageListener.None,
) {
    /**
     * Скан (dry-run, §F4 «dry-run → …»): классифицирует каждый лайкнутый трек через [classifier] (реально —
     * один запрос метаданных на трек через лимитер, хард-правило 7). Ни одной мутации акка. [onScanned]
     * зовётся после каждого трека — для живого прогресса на UI (9k треков идут долго). Возвращает [CleanupPlan].
     */
    fun scan(
        likedTrackIds: Iterable<String>,
        classifier: TrackClassifier,
        onScanned: (index: Int, trackId: String, bucket: CleanupBucket) -> Unit = { _, _, _ -> },
    ): CleanupPlan {
        val items = ArrayList<CleanupScanItem>()
        likedTrackIds.forEachIndexed { i, trackId ->
            val bucket = classifier.classify(trackId)
            items += CleanupScanItem(trackId, bucket)
            onScanned(i, trackId, bucket)
        }
        return CleanupPlan(items)
    }

    /**
     * Применить план (§F4 «… → бэкап → подтверждение»). Требует (хард-правило 5): `confirm=true` И, если план
     * трогает лайки ([CleanupPlan.touchesLikes] — любая корзина кроме CLEAN снимает лайк), свежий бэкап.
     * Иначе — отказ с тем же планом (dry-run).
     */
    fun execute(plan: CleanupPlan, confirm: Boolean): CleanupResult {
        require(plan.aiGate.isEmpty() || aiPlaylistKind.isNotBlank()) { "для корзины AI_GATE нужен непустой aiPlaylistKind" }
        require(plan.gray.isEmpty() || grayPlaylistKind.isNotBlank()) { "для корзины GRAY нужен непустой grayPlaylistKind" }

        if (!confirm) return CleanupResult(plan, executed = false, refusedReason = REFUSED_NOT_CONFIRMED)
        val backupId = if (plan.touchesLikes) {
            backup.latestBackupId() ?: return CleanupResult(plan, executed = false, refusedReason = REFUSED_NO_BACKUP)
        } else {
            null
        }

        var deadUnliked = 0; var aiMoved = 0; var grayMoved = 0; var noop = 0

        // ── DEAD: снять лайк без дизлайка → is_dead ───────────────────────────
        for (trackId in plan.dead) {
            stageListener.onStage(trackId, ProcessingStage.ACTING)
            TrackStateMachine.validateTransition(DEAD_FROM, TrackState.IS_DEAD)
            val changed = library.unlike(trackId)
            if (changed) deadUnliked++ else noop++
            sink.onDead(trackId, DEAD_FROM, changed)
        }

        // ── AI_GATE: дизлайк + перенос в плейлист «детект ИИ» (цепочка §5) ─────
        for (trackId in plan.aiGate) {
            stageListener.onStage(trackId, ProcessingStage.ACTING)
            TrackStateMachine.validateTransition(TrackState.SUSPECTED, TrackState.AI_CONFIRMED)
            TrackStateMachine.validateTransition(TrackState.AI_CONFIRMED, TrackState.DISLIKED)
            val disliked = library.dislike(trackId)
            TrackStateMachine.validateTransition(TrackState.DISLIKED, TrackState.MOVED_TO_PLAYLIST)
            val added = library.addToPlaylist(trackId, aiPlaylistKind)
            if (disliked || added) aiMoved++ else noop++
            sink.onAiMoved(trackId, disliked, added)
        }

        // ── GRAY: в плейлист «непонятно» + СНЯТЬ лайк (ручное ре-лайк/дизлайк) ─
        // Порядок важен: сначала добавляем в плейлист (сохраняем ссылку на трек), потом снимаем лайк —
        // иначе при сбое между операциями трек потерялся бы. §5-узел не двигаем (нет ребра
        // review_required → removed_from_likes): снятие лайка тут — физическая акк-операция под бэкапом,
        // а не переход детекции; verdict остаётся review_required (человек решит из плейлиста).
        for (trackId in plan.gray) {
            stageListener.onStage(trackId, ProcessingStage.ACTING)
            val added = library.addToPlaylist(trackId, grayPlaylistKind)
            val unliked = library.unlike(trackId)
            if (added || unliked) grayMoved++ else noop++
            sink.onGrayReview(trackId, unliked, added)
        }

        return CleanupResult(
            plan = plan,
            executed = true,
            refusedReason = null,
            deadUnliked = deadUnliked,
            aiMoved = aiMoved,
            grayMoved = grayMoved,
            noop = noop,
            backupId = backupId,
        )
    }

    companion object {
        const val REFUSED_NOT_CONFIRMED = "not_confirmed"
        const val REFUSED_NO_BACKUP = "no_backup"

        /**
         * Стартовое состояние для перехода мёртвого трека. `любое → is_dead` разрешено графом (§5), поэтому
         * берём UNKNOWN как нейтральный старт: реальное прежнее состояние для мёртвого не важно.
         */
        private val DEAD_FROM = TrackState.UNKNOWN
    }
}

/** Корзина лайкнутого трека по итогу скана чистки. */
enum class CleanupBucket {
    /** Удалён/недоступен в ЯМ — снять лайк (unlike, без дизлайка) → is_dead. */
    DEAD,

    /** slopless-гейт (артист в базе AI, `suspected`) — дизлайк + плейлист «детект ИИ». */
    AI_GATE,

    /** Серая зона (`review_required`) — в плейлист «непонятно» + снять лайк (ручное ре-лайк/дизлайк). */
    GRAY,

    /** Чисто (`clean`) — не трогаем. */
    CLEAN,
}

/**
 * Классификатор одного лайкнутого трека в [CleanupBucket]. Реализация на устройстве делает РОВНО один запрос
 * [dev.humanonly.yandex.YandexClient.trackMetadata] (через лимитер — хард-правило 7) и гоняет
 * [dev.humanonly.detector.DetectionCascade]: `available=false`/пусто → [CleanupBucket.DEAD]; иначе вердикт
 * каскада (`suspected`→AI_GATE, `review_required`→GRAY, `clean`→CLEAN). В тестах — на карте. Функция чистая
 * относительно [trackId] (для одного id один и тот же ответ метаданных → один bucket).
 */
fun interface TrackClassifier {
    fun classify(trackId: String): CleanupBucket
}

/** Один трек из лайков + присвоенная корзина (результат [LibraryCleanup.scan]). */
data class CleanupScanItem(val trackId: String, val bucket: CleanupBucket)

/** План чистки (dry-run): все просканированные лайки по корзинам. Порядок сохранён (стабильный вывод). */
data class CleanupPlan(val items: List<CleanupScanItem>) {
    val dead: List<String> get() = idsOf(CleanupBucket.DEAD)
    val aiGate: List<String> get() = idsOf(CleanupBucket.AI_GATE)
    val gray: List<String> get() = idsOf(CleanupBucket.GRAY)
    val clean: List<String> get() = idsOf(CleanupBucket.CLEAN)

    /** Счётчики по корзинам (для dry-run UI). Корзины без треков в карте отсутствуют. */
    val counts: Map<CleanupBucket, Int> get() = items.groupingBy { it.bucket }.eachCount()

    /**
     * Тронет ли исполнение хоть один лайк → обязателен бэкап. Теперь ЛЮБАЯ корзина кроме CLEAN снимает лайк
     * (мёртвые — unlike; гейт-ИИ — дизлайк; серая — unlike + плейлист «непонятно»). Только CLEAN лайк не трогает.
     */
    val touchesLikes: Boolean get() = items.any { it.bucket != CleanupBucket.CLEAN }

    private fun idsOf(b: CleanupBucket): List<String> = items.filter { it.bucket == b }.map { it.trackId }
}

/** Итог [LibraryCleanup.execute]. При отказе `executed=false` + [refusedReason]; [plan] всегда заполнен. */
data class CleanupResult(
    val plan: CleanupPlan,
    val executed: Boolean,
    val refusedReason: String? = null,
    /** Снято лайков с мёртвых. */
    val deadUnliked: Int = 0,
    /** Гейт-ИИ, по которым сработал дизлайк/перенос. */
    val aiMoved: Int = 0,
    /** Серых, снятых с лайков и добавленных в плейлист «непонятно» на ревью. */
    val grayMoved: Int = 0,
    /** No-op (уже в целевом состоянии, идемпотентность §6.2). */
    val noop: Int = 0,
    val backupId: String? = null,
)

/**
 * Приёмник результатов чистки: персист состояния + audit (§12, без PII). Реализация — в БД-слое
 * (`SqlCleanupSink`). Все методы вызываются ПОСЛЕ фактической акк-операции с флагом «изменилось ли».
 */
interface CleanupSink {
    /** Мёртвый трек: снят лайк ([unliked]) → пометить `is_dead`. */
    fun onDead(trackId: String, from: TrackState, unliked: Boolean)

    /** Гейт-ИИ: дизлайк ([disliked]) + добавление в плейлист «детект ИИ» ([added]). */
    fun onAiMoved(trackId: String, disliked: Boolean, added: Boolean)

    /** Серая зона: добавление в плейлист «непонятно» ([added]) + снятие лайка ([unliked]) на ручное ревью. */
    fun onGrayReview(trackId: String, unliked: Boolean, added: Boolean)

    companion object {
        /** Ничего не персистит (для dry-run без БД / базовых тестов). */
        val None: CleanupSink = object : CleanupSink {
            override fun onDead(trackId: String, from: TrackState, unliked: Boolean) {}
            override fun onAiMoved(trackId: String, disliked: Boolean, added: Boolean) {}
            override fun onGrayReview(trackId: String, unliked: Boolean, added: Boolean) {}
        }
    }
}
