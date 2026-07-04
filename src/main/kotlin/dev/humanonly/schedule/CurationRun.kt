package dev.humanonly.schedule

import dev.humanonly.archive.ArchiveCandidate
import dev.humanonly.archive.ArchiveSummary
import dev.humanonly.archive.Archiver
import dev.humanonly.config.FeatureFlags
import dev.humanonly.pipeline.ActionCandidate
import dev.humanonly.pipeline.ActionDispatcher
import dev.humanonly.pipeline.ActionResult
import dev.humanonly.pipeline.ConveyorSummary
import dev.humanonly.pipeline.DownloadQueue
import dev.humanonly.pipeline.DownloadStage
import dev.humanonly.pipeline.DownloadSummary
import dev.humanonly.pipeline.ScanConveyor
import dev.humanonly.pipeline.TrackCandidate

/**
 * Оркестратор одного планового прогона куратора (ТЗ §4.1, §7). Тело работы, которое дёргает планировщик
 * (WorkManager `CoroutineWorker`) по [RunSchedule]. Framework-agnostic — чистая композиция уже собранных
 * стадий за интерфейсами, поэтому гоняется на JVM-fakes без Android/сети/акка.
 *
 * Порядок стадий (буфер = очередь, каждая стадия независима и идемпотентна → безопасный ретрай, §6.1):
 *   1. **scan+detect** (если включён любой детектор-флаг): [scanSource] → [conveyor] (scan→каскад→вердикт→checkpoint).
 *   2. **actions** (если [FeatureFlags.autoDislike]): [actionQueue] (треки, подтверждённые как ИИ ревью/гейтом) →
 *      [dispatcher]. Хард-правило 5 соблюдён СТРУКТУРНО: `confirm=true` — это «стоячее» согласие авто-режима,
 *      но [ActionDispatcher] всё равно откажет без свежего бэкапа ([ActionResult.refusedReason]) → деструктив
 *      без бэкапа НЕ произойдёт (это не сбой прогона, а зафиксированный отказ стадии).
 *   3. **download** (если [FeatureFlags.archive]): [downloadQueue] (чистые треки) → [downloadStage]
 *      (download→prepare→write local) → свежие [ArchiveCandidate].
 *   4. **archive** (если [FeatureFlags.archive]): свежескачанные (шаг 3) + [archiveQueue] (ранее скачанные,
 *      но не заархивированные — resume после крэша) → [archiver].
 *
 * Итог — [RunOutcome] (зеркало WorkManager Result): исключение [TransientException] (сеть/ЯМ 5xx/капча) →
 * [RunOutcome.RETRY] (перепланирование по [BackoffPolicy]); любое другое → [RunOutcome.FAILURE]. Успех —
 * [RunOutcome.SUCCESS], даже если делать было нечего. Ограничения устройства ([RunConstraints]) проверяются
 * на входе (WorkManager обычно уже отфильтровал, но защищаемся): не выполнены → RETRY без побочек.
 */
class CurationRun(
    private val flags: FeatureFlags,
    private val scanSource: ScanSource,
    private val conveyor: ScanConveyor,
    private val schedule: RunSchedule,
    private val actionQueue: ActionQueue = ActionQueue.Empty,
    private val dispatcher: ActionDispatcher? = null,
    private val downloadQueue: DownloadQueue = DownloadQueue.Empty,
    private val downloadStage: DownloadStage? = null,
    private val archiveQueue: ArchiveQueue = ArchiveQueue.Empty,
    private val archiver: Archiver? = null,
) {
    fun execute(deviceState: DeviceState = DeviceState()): RunReport {
        // Гейт ограничений — WorkManager обычно уже проверил, но повтор безопасен и тестируем.
        if (!schedule.constraints.satisfiedBy(deviceState)) {
            return RunReport(RunOutcome.RETRY, skippedReason = REASON_CONSTRAINTS_NOT_MET)
        }
        return try {
            // ── 1. scan + detect ──────────────────────────────────────────────
            val conveyorSummary: ConveyorSummary? =
                if (flags.detectorMetadata || flags.detectorAudio) {
                    conveyor.run(scanSource.newCandidates())
                } else null

            // ── 2. actions (авто-режим, за бэкапом — хард-правило 5) ───────────
            val actionResult: ActionResult? =
                if (flags.autoDislike && dispatcher != null) {
                    val pending = actionQueue.pending()
                    if (pending.isNotEmpty()) dispatcher.execute(pending, confirm = true) else null
                } else null

            // ── 3. download (чистые треки: download→prepare→write local) ───────
            val downloadSummary: DownloadSummary? =
                if (flags.archive && downloadStage != null) {
                    val pending = downloadQueue.pending()
                    if (pending.isNotEmpty()) downloadStage.run(pending) else null
                } else null

            // ── 4. archive (свежескачанные + resume ранее скачанных, но не заархивированных) ──
            val archiveSummary: ArchiveSummary? =
                if (flags.archive && archiver != null) {
                    val fresh = downloadSummary?.archiveCandidates.orEmpty()
                    val resume = archiveQueue.pending()
                    val all = fresh + resume
                    if (all.isNotEmpty()) archiver.run(all) else null
                } else null

            RunReport(
                outcome = RunOutcome.SUCCESS,
                conveyor = conveyorSummary,
                action = actionResult,
                download = downloadSummary,
                archive = archiveSummary,
            )
        } catch (e: TransientException) {
            RunReport(RunOutcome.RETRY, error = e.message)
        } catch (e: Exception) {
            RunReport(RunOutcome.FAILURE, error = e.message)
        }
    }

    companion object {
        const val REASON_CONSTRAINTS_NOT_MET = "constraints_not_met"
    }
}

/** Источник новых треков прогона (§7 шаг 1: scan_delta — что появилось в библиотеке с прошлого раза). */
fun interface ScanSource {
    fun newCandidates(): List<TrackCandidate>
}

/** Очередь треков, подтверждённых как ИИ (ревью/precision-gate) и ждущих авто-действия (§F4). */
fun interface ActionQueue {
    fun pending(): List<ActionCandidate>

    companion object {
        val Empty = ActionQueue { emptyList() }
    }
}

/** Очередь скачанных (`downloaded`) треков, ждущих архивации (§F6). */
fun interface ArchiveQueue {
    fun pending(): List<ArchiveCandidate>

    companion object {
        val Empty = ArchiveQueue { emptyList() }
    }
}

/**
 * Временный сбой стадии (сеть недоступна, ЯМ 5xx, капча/троттлинг §6.3) — прогон надо ПОВТОРИТЬ по
 * backoff, а не снимать. Стадии/интерфейсы кидают его, чтобы оркестратор вернул [RunOutcome.RETRY].
 * Всё прочее (баг/битый конфиг) → [RunOutcome.FAILURE] (долбить бессмысленно).
 */
class TransientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Итог прогона: outcome + сводки выполненных стадий (null — стадия выключена/пуста). error/skip — диагностика без PII. */
data class RunReport(
    val outcome: RunOutcome,
    val conveyor: ConveyorSummary? = null,
    val action: ActionResult? = null,
    val download: DownloadSummary? = null,
    val archive: ArchiveSummary? = null,
    val skippedReason: String? = null,
    val error: String? = null,
)
