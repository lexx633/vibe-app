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
    private val enricher: ArtistEnricher? = null,
) : ScanSource {
    override fun newCandidates(): List<TrackCandidate> {
        val discovered = try {
            reader.likedTracks()
        } catch (e: YandexThrottleException) {
            throw TransientException("троттлинг ЯМ при чтении лайков: HTTP ${e.status}", e)
        }
        repo.upsertDiscovered(discovered)
        // Дозаполнить artist_id новых треков из метаданных ЯМ → заработает slopless-гейт каскада 0.
        try {
            enricher?.enrich()
        } catch (e: YandexThrottleException) {
            throw TransientException("троттлинг ЯМ при обогащении artist_id: HTTP ${e.status}", e)
        }
        return delta.newCandidates()
    }
}

/**
 * Порт резолва метаданных трека для обогащения индекса (нужен только `artist_id` — вход slopless-гейта).
 * За интерфейсом (хард-правило 9) → [ArtistEnricher] тестируется на fake без сети/токена.
 */
fun interface MetaLookup {
    /** id основного артиста трека, либо null (трек недоступен / без артистов). */
    fun primaryArtistId(yandexTrackId: String): String?
}

/**
 * [MetaLookup] поверх [YandexClient]: `/tracks/{id}` → основной артист. Каждый вызов идёт через
 * rate-limiter клиента (хард-правило 7). Имя артиста не берём (PII §12) — только id.
 */
class YandexMetaLookup(private val client: YandexClient) : MetaLookup {
    override fun primaryArtistId(yandexTrackId: String): String? =
        client.trackMetadata(yandexTrackId).firstOrNull()?.primaryArtistId()
}

/**
 * Обогащение scan_delta полем `artist_id` из метаданных ЯМ — без него slopless-гейт каскада 0 спит
 * (нечем матчить AI-артиста). Берёт порцию треков без artist_id ([TrackRepository.tracksMissingArtist])
 * и по одному резолвит основного артиста через [lookup]. Идемпотентно и инкрементально: обогащённые/
 * сканированные треки не трогаются, повтор безопасен (§6.1).
 */
class ArtistEnricher(
    private val repo: TrackRepository,
    private val lookup: MetaLookup,
    private val batch: Int = 200,
) {
    /** Обогатить порцию; возвращает число проставленных artist_id. */
    fun enrich(): Int {
        var updated = 0
        for (id in repo.tracksMissingArtist(batch)) {
            lookup.primaryArtistId(id)?.let { repo.setArtistId(id, it); updated++ }
        }
        return updated
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
