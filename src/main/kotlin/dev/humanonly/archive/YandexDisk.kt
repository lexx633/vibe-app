package dev.humanonly.archive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Архив FLAC-блобов и manifest.json на Яндекс.Диске через REST API (ТЗ §F6, §9).
 *
 * Полноценная удалённая [BlobStore]/[ManifestStore] поверх Диска — альтернатива S3/B2 (§9: B2 дефолт,
 * но Диск — рабочий бэкенд для персонального архива без отдельного S3-аккаунта). ТОЛЬКО FLAC-блобы +
 * manifest, НЕ синк (§9: S3-как-БД — антипаттерн; синк — VPS-мастер).
 *
 * Эндпоинты (ХАРД-ПРАВИЛО 9 — сверено по официальной доке cloud-api.yandex.net, не по памяти):
 *  - upload:   GET  `/v1/disk/resources/upload?path=<enc>&overwrite=true` → `{href, method:PUT}`, затем PUT
 *              байтов на href (href самодостаточен, токен на него НЕ нужен).
 *  - download: GET  `/v1/disk/resources/download?path=<enc>` → `{href}`, затем GET href → байты.
 *  - mkdir:    PUT  `/v1/disk/resources?path=<enc>` → 201 Created (409 — уже есть; родители НЕ
 *              создаются автоматически — создаём каждый уровень).
 *  - exists:   GET  `/v1/disk/resources?path=<enc>` → 200 есть / 404 нет.
 *
 * Auth: заголовок `Authorization: OAuth <token>` (стандарт Яндекса) на вызовы к cloud-api; PUT/GET на
 * выданный href идут БЕЗ токена (auth=false). Токен держит реализация [DiskHttp] — здесь не хранится и
 * не логируется (хард-правило 4).
 */

/** База REST API Диска. */
private const val DISK_API_BASE = "https://cloud-api.yandex.net/v1/disk"

/** Дефолтная папка архива на Диске (URL из запроса Owner → `/Бекап/vibe`). */
const val DISK_DEFAULT_BASE_PATH: String = "/Бекап/vibe"

/** Ответ HTTP для слоя Диска: код + сырое тело. Тело значимо для JSON (href) и для скачивания блоба. */
data class DiskResponse(val status: Int, val body: ByteArray) {
    fun text(): String = String(body, StandardCharsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        this === other || (other is DiskResponse && status == other.status && body.contentEquals(other.body))

    override fun hashCode(): Int = 31 * status + body.contentHashCode()
}

/**
 * Транспорт для Диска: GET/PUT с опциональным токеном. `auth=true` → реализация вешает
 * `Authorization: OAuth <token>` (вызовы к cloud-api); `auth=false` → без токена (выданный href).
 * Живая реализация (java.net.http) — в test-source; ядро тестируется на fake.
 */
interface DiskHttp {
    fun get(url: String, auth: Boolean): DiskResponse

    /** PUT с телом (upload на href) или без тела (mkdir). */
    fun put(url: String, body: ByteArray?, auth: Boolean): DiskResponse
}

/** JSON-ответ upload/download: интересует только `href` (метод фиксирован докой). */
@Serializable
private data class HrefResponse(@SerialName("href") val href: String)

private val diskJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Клиент Диска: exists / mkdir (с родителями) / upload / download поверх [DiskHttp]. Пути абсолютные
 * от корня Диска (напр. `/Бекап/vibe/flac/ab/x.flac`). URL-кодирование пути — единым значением
 * (слэши тоже кодируются: API ждёт path одним параметром).
 */
class YandexDiskClient(private val http: DiskHttp) {

    /** Есть ли ресурс (файл/папка) по абсолютному пути. */
    fun exists(path: String): Boolean {
        val resp = http.get("$DISK_API_BASE/resources?path=${enc(path)}", auth = true)
        return when (resp.status) {
            200 -> true
            404 -> false
            else -> error("Диск exists: неожиданный HTTP ${resp.status}")
        }
    }

    /** Создать одну папку. 201 — создана, 409 — уже существует (оба — успех). */
    fun makeDir(path: String) {
        val resp = http.put("$DISK_API_BASE/resources?path=${enc(path)}", body = null, auth = true)
        if (resp.status != 201 && resp.status != 409) {
            error("Диск mkdir '$path': неожиданный HTTP ${resp.status}")
        }
    }

    /**
     * Создать все родительские папки для файла [filePath] (Диск не создаёт их сам). Идёт от корня к
     * листу: `/Бекап`, `/Бекап/vibe`, `/Бекап/vibe/flac`, … Существующие пропускаются (409).
     */
    fun ensureParentDirs(filePath: String) {
        val segments = filePath.trim('/').split('/')
        if (segments.size <= 1) return // файл в корне — родителей нет
        var acc = ""
        for (i in 0 until segments.size - 1) {
            acc += "/" + segments[i]
            makeDir(acc)
        }
    }

    /**
     * Залить байты по абсолютному пути (overwrite=true — идемпотентно по пути, дедуп у [Archiver] по
     * хэшу выше). Двухшагово: получить href → PUT байтов на него без токена. Родительские папки —
     * ответственность вызывающего (см. [ensureParentDirs]).
     */
    fun upload(path: String, content: ByteArray) {
        val meta = http.get("$DISK_API_BASE/resources/upload?path=${enc(path)}&overwrite=true", auth = true)
        if (meta.status !in 200..299) error("Диск upload-href '$path': HTTP ${meta.status}")
        val href = diskJson.decodeFromString(HrefResponse.serializer(), meta.text()).href
        val put = http.put(href, body = content, auth = false)
        // Диск отдаёт 201 Created (иногда 202 Accepted при догрузке) — оба валидны.
        if (put.status !in 200..299) error("Диск upload-put '$path': HTTP ${put.status}")
    }

    /** Скачать байты по абсолютному пути, либо null, если ресурса нет (404 на выдаче href). */
    fun download(path: String): ByteArray? {
        val meta = http.get("$DISK_API_BASE/resources/download?path=${enc(path)}", auth = true)
        if (meta.status == 404) return null
        if (meta.status !in 200..299) error("Диск download-href '$path': HTTP ${meta.status}")
        val href = diskJson.decodeFromString(HrefResponse.serializer(), meta.text()).href
        val data = http.get(href, auth = false)
        if (data.status == 404) return null
        if (data.status !in 200..299) error("Диск download-get '$path': HTTP ${data.status}")
        return data.body
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
}

/**
 * [BlobStore] поверх Диска: относительный путь блоба (`flac/ab/hash.flac` от [Archiver]) резолвится
 * под [basePath]. [put] создаёт родительские папки и заливает; [exists] — дедуп по пути (хэшу).
 */
class YandexDiskBlobStore(
    private val client: YandexDiskClient,
    private val basePath: String = DISK_DEFAULT_BASE_PATH,
) : BlobStore {

    override fun exists(path: String): Boolean = client.exists(full(path))

    override fun put(path: String, content: ByteArray): Boolean {
        val target = full(path)
        client.ensureParentDirs(target)
        client.upload(target, content)
        return true
    }

    override fun get(path: String): ByteArray? = client.download(full(path))

    /** Абсолютный путь на Диске = basePath + относительный путь блоба (без двойных слэшей). */
    private fun full(path: String): String = "${basePath.trimEnd('/')}/${path.trimStart('/')}"
}

/**
 * [ManifestStore] поверх Диска: `manifest.json` под [basePath] с атомарной семантикой upload
 * (overwrite=true — Диск заменяет файл целиком, частичного состояния снаружи не видно). [load] на
 * отсутствующем файле → пустой манифест (первый прогон).
 */
class YandexDiskManifestStore(
    private val client: YandexDiskClient,
    private val basePath: String = DISK_DEFAULT_BASE_PATH,
) : ManifestStore {

    private val filePath: String = "${basePath.trimEnd('/')}/manifest.json"

    override fun load(): ArchiveManifest {
        val bytes = client.download(filePath) ?: return ArchiveManifest()
        return ArchiveSerialization.decode(String(bytes, StandardCharsets.UTF_8))
    }

    override fun save(manifest: ArchiveManifest) {
        client.ensureParentDirs(filePath)
        client.upload(filePath, ArchiveSerialization.encode(manifest).toByteArray(StandardCharsets.UTF_8))
    }
}
