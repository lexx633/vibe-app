package dev.humanonly.pipeline

import dev.humanonly.archive.ArchiveCandidate
import dev.humanonly.archive.WritableLocalStore
import dev.humanonly.state.ProcessingStage
import dev.humanonly.state.TrackState
import dev.humanonly.state.TrackStateMachine
import dev.humanonly.yandex.FlacArchivePreparer
import dev.humanonly.yandex.TrackDownloader
import dev.humanonly.yandex.YandexClient

/**
 * Стадия скачивания чистых треков (§F6, §7): `download → prepare → write local` — мост между вердиктом
 * «чистый» и [dev.humanonly.archive.Archiver]. Для каждого кандидата:
 *   1. [TrackFetcher.fetch] — get-file-info → скачивание → AES-CTR дешифровка (реальный блоб ЯМ);
 *   2. [FlacArchivePreparer.prepare] — привести к сырому `.flac` (passthrough RAW_FLAC / ремукс FLAC_MP4),
 *      sha256 считается от ФИНАЛЬНОГО `.flac` (ключ дедупа/путь архива);
 *   3. [WritableLocalStore.write] — атомарно положить локальный блоб (crash-safety §6.1: локальный файл
 *      переживает крэш, [Archiver] заберёт его и на ретрае);
 *   4. эмитить [ArchiveCandidate] (`currentState = downloaded`) для стадии архивации.
 *
 * Хард-правило 10: переход `clean | human_confirmed → downloaded` валидируется [TrackStateMachine] ДО
 * скачивания (недопустимое состояние → трек в failed, без побочек). Каждый трек независим: сбой одного
 * (throttle/битый контейнер/ошибка записи) не роняет остальных — фиксируется как failure, ретрай на
 * следующем прогоне (данные целы). Чистая оркестрация за интерфейсами → тестируется на JVM-fakes.
 */
class DownloadStage(
    private val fetcher: TrackFetcher,
    private val preparer: FlacArchivePreparer,
    private val local: WritableLocalStore,
    private val sink: DownloadSink = DownloadSink.None,
    private val stageListener: StageListener = StageListener.None,
) {
    /** Скачать батч. Возвращает готовые к архивации кандидаты + пофайловые результаты (для диагностики). */
    fun run(candidates: Iterable<DownloadCandidate>): DownloadSummary {
        val archiveCandidates = ArrayList<ArchiveCandidate>()
        val results = ArrayList<DownloadItemResult>()
        var downloaded = 0; var failed = 0

        for (c in candidates) {
            val res = downloadOne(c)
            results += res
            if (res.status == DownloadStatus.DOWNLOADED && res.candidate != null) {
                archiveCandidates += res.candidate
                downloaded++
            } else {
                failed++
            }
        }
        return DownloadSummary(downloaded, failed, archiveCandidates, results)
    }

    private fun downloadOne(c: DownloadCandidate): DownloadItemResult {
        // Хард-правило 10: не качаем из недопустимого состояния (только clean|human_confirmed → downloaded).
        if (!TrackStateMachine.canTransition(c.currentState, TrackState.DOWNLOADED)) {
            return fail(c, REASON_INVALID_STATE)
        }
        stageListener.onStage(c.trackId, ProcessingStage.DOWNLOADING)

        val fetched = try {
            fetcher.fetch(c.trackId)
        } catch (e: Exception) {
            return fail(c, REASON_FETCH_ERROR)
        }

        val prepared = try {
            preparer.prepare(fetched.decrypted)
        } catch (e: Exception) {
            // UNKNOWN-контейнер / сбой демукса → не архивируем вслепую (данные не портим), ретрай/ручной разбор.
            return fail(c, REASON_PREPARE_ERROR)
        }

        try {
            local.write(c.trackId, prepared.flac)
        } catch (e: Exception) {
            return fail(c, REASON_LOCAL_WRITE_ERROR)
        }

        // Коммит перехода §5 (валидирован выше) — трек стал downloaded, локальный блоб на месте.
        TrackStateMachine.validateTransition(c.currentState, TrackState.DOWNLOADED)
        sink.onDownloaded(c.trackId, from = c.currentState, sha256 = prepared.sha256, remuxed = prepared.remuxed)

        val candidate = ArchiveCandidate(
            trackId = c.trackId,
            hash = prepared.sha256,
            codec = CODEC_FLAC, // после prepare — всегда сырой .flac
            quality = fetched.quality,
            verdict = c.verdict,
            detectorVersion = c.detectorVersion,
            backupId = c.backupId,
            currentState = TrackState.DOWNLOADED,
        )
        return DownloadItemResult(c.trackId, DownloadStatus.DOWNLOADED, candidate, reason = null)
    }

    private fun fail(c: DownloadCandidate, reason: String): DownloadItemResult {
        sink.onFailed(c.trackId, reason)
        return DownloadItemResult(c.trackId, DownloadStatus.FAILED, candidate = null, reason = reason)
    }

    companion object {
        const val CODEC_FLAC = "flac"
        const val REASON_INVALID_STATE = "invalid_state"
        const val REASON_FETCH_ERROR = "fetch_error"
        const val REASON_PREPARE_ERROR = "prepare_error"
        const val REASON_LOCAL_WRITE_ERROR = "local_write_error"
    }
}

/** Трек, подтверждённый чистым (по §5 обычно `clean`) и подлежащий скачиванию+архивации (§F6). */
data class DownloadCandidate(
    val trackId: String,
    val verdict: String,
    val detectorVersion: String,
    val backupId: String? = null,
    /** источник перехода в downloaded — по §5 `clean` или `human_confirmed`. */
    val currentState: TrackState = TrackState.CLEAN,
)

/** Расшифрованный блоб трека + техполя из get-file-info (codec/quality исходника; итоговый codec — flac). */
data class FetchedTrack(
    val decrypted: ByteArray,
    val quality: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is FetchedTrack && decrypted.contentEquals(other.decrypted) && quality == other.quality)

    override fun hashCode(): Int = 31 * decrypted.contentHashCode() + quality.hashCode()
}

/**
 * Достаёт расшифрованный блоб трека: get-file-info → download → AES-CTR. Абстрагирует сеть/акк за
 * интерфейсом, чтобы [DownloadStage] тестировалась на fake. Живая реализация — [YandexTrackFetcher].
 */
fun interface TrackFetcher {
    fun fetch(trackId: String): FetchedTrack
}

/**
 * Живой [TrackFetcher] поверх уже верифицированных [YandexClient] (get-file-info) и [TrackDownloader]
 * (скачивание+дешифровка). [clock] отдаёт unix-секунды для подписи get-file-info (в тестах — фиксировано).
 */
class YandexTrackFetcher(
    private val client: YandexClient,
    private val downloader: TrackDownloader,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : TrackFetcher {
    override fun fetch(trackId: String): FetchedTrack {
        val info = client.getFileInfo(trackId, clock())
        val result = downloader.download(info)
        return FetchedTrack(decrypted = result.decrypted, quality = info.quality ?: "")
    }
}

/** Очередь чистых треков, ждущих скачивания+архивации (§F6). */
fun interface DownloadQueue {
    fun pending(): List<DownloadCandidate>

    companion object {
        val Empty = DownloadQueue { emptyList() }
    }
}

/** Приёмник результата скачивания: персист перехода `→ downloaded` + audit (§12, без PII). */
interface DownloadSink {
    fun onDownloaded(trackId: String, from: TrackState, sha256: String, remuxed: Boolean)
    fun onFailed(trackId: String, reason: String)

    companion object {
        val None = object : DownloadSink {
            override fun onDownloaded(trackId: String, from: TrackState, sha256: String, remuxed: Boolean) {}
            override fun onFailed(trackId: String, reason: String) {}
        }
    }
}

/** Статус скачивания одного трека. */
enum class DownloadStatus { DOWNLOADED, FAILED }

/** Итог по одному треку: при DOWNLOADED заполнен [candidate] (для архивации), при FAILED — [reason]. */
data class DownloadItemResult(
    val trackId: String,
    val status: DownloadStatus,
    val candidate: ArchiveCandidate?,
    val reason: String?,
)

/** Агрегат стадии скачивания: сколько скачано/провалено + готовые кандидаты на архивацию. */
data class DownloadSummary(
    val downloaded: Int,
    val failed: Int,
    val archiveCandidates: List<ArchiveCandidate>,
    val items: List<DownloadItemResult>,
)
