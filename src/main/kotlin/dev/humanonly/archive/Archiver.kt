package dev.humanonly.archive

import dev.humanonly.pipeline.StageListener
import dev.humanonly.state.ProcessingStage
import dev.humanonly.state.TrackState
import dev.humanonly.state.TrackStateMachine

/**
 * Архиватор чистых FLAC в S3 + manifest.json (ТЗ §F6, §9, data-model §7).
 *
 * Инварианты:
 *  - **upload идемпотентен по хэшу** (§6.2): блоб уже в S3 → не заливаем повторно, просто фиксируем.
 *  - **локальный файл удаляется ТОЛЬКО после подтверждённого upload** (§F6, хард-правило: необратимое —
 *    после записи). Порядок для crash-safety (§6.1): `upload → manifest → delete local`. Крэш между
 *    upload и manifest → ретрай видит блоб (dedup) и до-пишет manifest, затем удаляет локальный. Идемпотентно.
 *  - сбой upload/manifest → **archive_status=pending**, локальный НЕ удаляется, трек остаётся `downloaded`,
 *    ретрай позже (data-model §2). Никакой потери данных.
 *  - переход `downloaded → archived` валидируется [TrackStateMachine] (хард-правило 10, §5); коммит —
 *    только при подтверждённой архивации.
 *
 * Чистая оркестрация: S3/файлы/БД — за [BlobStore], [LocalStore], [ManifestStore], [ArchiveSink];
 * тестируется на fakes без сети/диска. Путь блоба детерминирован из hash → тот же трек = тот же путь.
 */
class Archiver(
    private val blobs: BlobStore,
    private val local: LocalStore,
    private val manifestStore: ManifestStore,
    private val sink: ArchiveSink = ArchiveSink.None,
    private val stageListener: StageListener = StageListener.None,
) {
    /** Архивировать батч. Каждый трек независим: сбой одного не роняет остальные (→ pending, ретрай). */
    fun run(candidates: Iterable<ArchiveCandidate>): ArchiveSummary {
        var uploaded = 0; var deduped = 0; var pending = 0
        val results = ArrayList<ArchiveItemResult>()

        for (c in candidates) {
            val res = archiveOne(c)
            results += res
            when (res.status) {
                ArchiveStatus.UPLOADED -> uploaded++
                ArchiveStatus.DEDUPED -> deduped++
                ArchiveStatus.PENDING -> pending++
            }
        }
        return ArchiveSummary(uploaded = uploaded, deduped = deduped, pending = pending, items = results)
    }

    private fun archiveOne(c: ArchiveCandidate): ArchiveItemResult {
        // Хард-правило 10: не архивируем из недопустимого состояния — в pending, без коммита перехода.
        if (!TrackStateMachine.canTransition(c.currentState, TrackState.ARCHIVED)) {
            return pending(c, REASON_INVALID_STATE)
        }
        stageListener.onStage(c.trackId, ProcessingStage.ARCHIVING)
        val path = archivePath(c.hash, c.codec)

        // 1. Дедуп по хэшу (§6.2): блоб уже в S3 → upload пропускаем.
        val alreadyThere = try {
            blobs.exists(path)
        } catch (e: Exception) {
            return pending(c, REASON_BLOB_ERROR)
        }

        var deduped = false
        if (alreadyThere) {
            deduped = true
        } else {
            // 2. Заливаем локальный блоб. Нет локального источника → нечего архивировать (ретрай позже).
            val bytes = try {
                local.read(c.trackId)
            } catch (e: Exception) {
                return pending(c, REASON_LOCAL_ERROR)
            } ?: return pending(c, REASON_NO_LOCAL_SOURCE)

            val confirmed = try {
                blobs.put(path, bytes)
            } catch (e: Exception) {
                false
            }
            if (!confirmed) return pending(c, REASON_UPLOAD_UNCONFIRMED)
        }

        // 3. Манифест ДО удаления локального (иначе потеряем указатель при крэше). Атомарно (temp+rename).
        val entry = ArchiveEntry(
            trackId = c.trackId, hash = c.hash, codec = c.codec, quality = c.quality,
            archivePath = path, backupId = c.backupId, verdict = c.verdict, detectorVersion = c.detectorVersion,
        )
        try {
            val updated = manifestStore.load().upsert(entry)
            manifestStore.save(updated)
        } catch (e: Exception) {
            // upload прошёл, но manifest не записался → локальный НЕ трогаем, ретрай допишет manifest (dedup).
            return pending(c, REASON_MANIFEST_ERROR)
        }

        // 4. Локальный удаляем только теперь — upload+manifest подтверждены. delete идемпотентен.
        try {
            local.delete(c.trackId)
        } catch (e: Exception) {
            // Блоб и manifest на месте — трек фактически заархивирован; лишний локальный файл не критичен.
        }

        // 5. Коммит перехода §5 (валидирован выше) + запись archive_status=archived.
        TrackStateMachine.validateTransition(c.currentState, TrackState.ARCHIVED)
        sink.onArchived(entry, from = c.currentState)
        stageListener.onStage(c.trackId, ProcessingStage.DONE)
        return ArchiveItemResult(
            c.trackId, if (deduped) ArchiveStatus.DEDUPED else ArchiveStatus.UPLOADED, path, reason = null,
        )
    }

    private fun pending(c: ArchiveCandidate, reason: String): ArchiveItemResult {
        sink.onPending(c.trackId, reason)
        return ArchiveItemResult(c.trackId, ArchiveStatus.PENDING, archivePath = null, reason = reason)
    }

    /**
     * 404-fallback (§F6): трек пропал у ЯМ → отдать из архива. Возвращает блоб по manifest-записи,
     * либо null, если трек не в архиве / блоба нет.
     */
    fun retrieve(trackId: String): ByteArray? {
        val entry = manifestStore.load().get(trackId) ?: return null
        return blobs.get(entry.archivePath)
    }

    companion object {
        const val REASON_INVALID_STATE = "invalid_state"
        const val REASON_NO_LOCAL_SOURCE = "no_local_source"
        const val REASON_UPLOAD_UNCONFIRMED = "upload_unconfirmed"
        const val REASON_MANIFEST_ERROR = "manifest_error"
        const val REASON_BLOB_ERROR = "blob_error"
        const val REASON_LOCAL_ERROR = "local_error"

        /**
         * Детерминированный путь блоба из хэша: `<codec>/<hh>/<hash>.<codec>`. Шардинг по первым 2 hex
         * (равномерное распределение по префиксам S3). Тот же контент → тот же путь → дедуп по хэшу.
         */
        fun archivePath(hash: String, codec: String): String {
            require(hash.length >= 2) { "hash слишком короткий для пути архива: '$hash'" }
            val ext = codec.lowercase()
            return "$ext/${hash.substring(0, 2)}/$hash.$ext"
        }
    }
}

/** Кандидат на архивацию: трек в `downloaded` + технические поля для manifest (§F6). */
data class ArchiveCandidate(
    val trackId: String,
    val hash: String,
    val codec: String,
    val quality: String,
    val verdict: String,
    val detectorVersion: String,
    val backupId: String? = null,
    /** текущее состояние — источник перехода в archived (по §5 обычно `downloaded`). */
    val currentState: TrackState = TrackState.DOWNLOADED,
)

/** Статус архивации одного трека. UPLOADED/DEDUPED → трек в архиве; PENDING → ретрай (данные целы). */
enum class ArchiveStatus { UPLOADED, DEDUPED, PENDING }

/** Итог по одному треку. reason заполнен только при PENDING (машинный код без PII, §12). */
data class ArchiveItemResult(
    val trackId: String,
    val status: ArchiveStatus,
    val archivePath: String?,
    val reason: String?,
)

/** Агрегат прогона архивации (§7 шаг 5 — уведомление). */
data class ArchiveSummary(
    val uploaded: Int,
    val deduped: Int,
    val pending: Int,
    val items: List<ArchiveItemResult>,
) {
    /** Всего фактически в архиве (залито сейчас + уже было). */
    val archived: Int get() = uploaded + deduped
}

/**
 * S3-совместимое хранилище блобов (§9: B2 дефолт). ТОЛЬКО FLAC-блобы, не синк. Реализация через
 * boto3/rclone/SDK — отдельной подзадачей; здесь контракт для оркестрации/тестов.
 */
interface BlobStore {
    /** Есть ли блоб по пути (дедуп по хэшу, §6.2). */
    fun exists(path: String): Boolean

    /** Загрузка блоба. true = подтверждённая запись (persisted). false/исключение → архив НЕ подтверждён. */
    fun put(path: String, content: ByteArray): Boolean

    /** Чтение блоба для 404-fallback (§F6). null — нет в архиве. */
    fun get(path: String): ByteArray?
}

/** Локальный источник FLAC: чтение для upload + удаление после подтверждения. delete идемпотентен. */
interface LocalStore {
    /** Байты локального FLAC; null — локального файла уже нет. */
    fun read(trackId: String): ByteArray?

    /** Удалить локальный файл (§F6: после подтверждённого upload). Повторный вызов безопасен (no-op). */
    fun delete(trackId: String)
}

/**
 * Хранилище manifest.json c **атомарным** обновлением (data-model §7). Реализация (FS/S3) обязана
 * писать через temp + atomic rename — частично записанный manifest недопустим. Здесь — контракт.
 */
interface ManifestStore {
    fun load(): ArchiveManifest
    fun save(manifest: ArchiveManifest)
}

/** Приёмник результата архивации: персист archive_status + перехода состояния + audit (§12, без PII). */
interface ArchiveSink {
    fun onArchived(entry: ArchiveEntry, from: TrackState)
    fun onPending(trackId: String, reason: String)

    companion object {
        val None = object : ArchiveSink {
            override fun onArchived(entry: ArchiveEntry, from: TrackState) {}
            override fun onPending(trackId: String, reason: String) {}
        }
    }
}
