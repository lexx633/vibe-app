package dev.humanonly.db

import dev.humanonly.pipeline.TrackCandidate
import dev.humanonly.schedule.ScanSource
import dev.humanonly.schedule.TransientException
import dev.humanonly.yandex.YandexClient
import dev.humanonly.yandex.YandexThrottleException

/**
 * Порт чтения библиотеки ЯМ для scan_delta (§7 шаг 1). Узкий интерфейс — прогону нужен только список
 * лайкнутых треков текущего аккаунта; всё остальное (uid, транспорт, rate-limit) прячет реализация.
 * За интерфейсом (хард-правило 9: внешний вызов инъектируется) → [LiveScanSource] тестируется на fake
 * без сети/токена.
 */
fun interface LibraryReader {
    /** Рефы лайкнутых треков (id + технические поля; без PII — §12). */
    fun likedTracks(): List<DiscoveredTrack>
}

/**
 * Живой [ScanSource] (§7 шаг 1): читает лайки ЯМ через [reader], регистрирует новые в индексе
 * ([TrackRepository.upsertDiscovered] — идемпотентно, `INSERT OR IGNORE`), затем отдаёт scan_delta из
 * индекса ([delta]: `verdict IS NULL`). Разделение обязанностей: сеть → БД → каскад; сам детект/вердикт
 * дальше делает конвейер поверх этого же индекса.
 *
 * Троттлинг ЯМ ([YandexThrottleException]) → [TransientException] → прогон уходит в RETRY по backoff
 * (§6.3), а не в FAILURE. Прочие ошибки [reader] пробрасываются как есть (баг/битый конфиг → FAILURE,
 * долбить бессмысленно).
 */
class LiveScanSource(
    private val reader: LibraryReader,
    private val repo: TrackRepository,
    private val delta: ScanSource,
) : ScanSource {
    override fun newCandidates(): List<TrackCandidate> {
        val discovered = try {
            reader.likedTracks()
        } catch (e: YandexThrottleException) {
            throw TransientException("троттлинг ЯМ при чтении лайков: HTTP ${e.status}", e)
        }
        repo.upsertDiscovered(discovered)
        return delta.newCandidates()
    }
}

/**
 * [LibraryReader] поверх живого [YandexClient]: uid аккаунта → лайки. Все вызовы идут через
 * rate-limiter внутри клиента (хард-правило 7). `artist_id` пока НЕ заполняется — извлечение из DTO ЯМ
 * (метаданные трека → artists) это отдельный чанк ([MetaResolver]); в MVP гейт по artist_id спит,
 * детект по метаданным (пусто → clean), так что регистрация треков корректна и без него.
 */
class YandexLibraryReader(private val client: YandexClient) : LibraryReader {
    override fun likedTracks(): List<DiscoveredTrack> {
        val uid = client.accountUid().toString()
        return client.likedTrackIds(uid).map { DiscoveredTrack(yandexTrackId = it, artistId = null) }
    }
}
