package dev.humanonly.archive.tools

import dev.humanonly.archive.ArchiveCandidate
import dev.humanonly.archive.ArchiveManifest
import dev.humanonly.archive.Archiver
import dev.humanonly.archive.AwsSigV4
import dev.humanonly.archive.FsLocalStore
import dev.humanonly.archive.HttpS3Transport
import dev.humanonly.archive.ManifestStore
import dev.humanonly.archive.S3BlobStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Живой smoke архивации в S3-совместимое хранилище (§9: B2 дефолт; годится AWS S3/MinIO/R2) —
 * сквозной прогон реального [Archiver] поверх [S3BlobStore] против настоящего бакета.
 *
 * НЕ юнит-тест (в CI не гоняется — нужны ключи и сеть; хард-правило 4). Запуск вручную:
 *   gradlew liveS3Archive --args="<путь к json-конфигу>"            # dry-run: только проверка доступа (HEAD)
 *   gradlew liveS3Archive --args="<путь к json-конфигу> --execute"  # реальная заливка тест-блоба
 *
 * JSON-конфиг (gitignored, ВНЕ репо):
 *   { "endpoint": "s3.eu-central-003.backblazeb2.com", "bucket": "vibe-archive",
 *     "region": "eu-central-003", "accessKeyId": "...", "secretAccessKey": "...", "service": "s3" }
 *
 * Секреты (ключи) НЕ печатаются (хард-правило 4): в stdout только endpoint/bucket/флаги/длины/пути.
 * Манифест держим в памяти (S3 — только блобы; синк-манифест живёт на Диске/VPS, не в S3 §9).
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "нужен путь к json-конфигу (endpoint/bucket/region/accessKeyId/secretAccessKey)" }
    val cfg = readConfig(Path.of(args[0]))
    val execute = args.contains("--execute")

    val blobs = S3BlobStore(
        http = HttpS3Transport(),
        endpoint = cfg.endpoint,
        bucket = cfg.bucket,
        region = cfg.region,
        creds = AwsSigV4.Credentials(cfg.accessKeyId, cfg.secretAccessKey),
        service = cfg.service,
    )

    println("S3-архив smoke → endpoint=${cfg.endpoint} bucket=${cfg.bucket} region=${cfg.region}")
    println("режим:            ${if (execute) "EXECUTE (реальная заливка)" else "DRY-RUN (только проверка доступа)"}")

    // Проверка доступа: HEAD несуществующего ключа не должен бросать (200/404 — оба валидны для токена/подписи).
    val probeKey = Archiver.archivePath("0".repeat(64), "flac")
    val reachable = runCatching { blobs.exists(probeKey) }
    reachable.onSuccess { println("доступ (HEAD):    OK (объект есть=$it) — подпись/ключи приняты") }
    reachable.onFailure { println("доступ (HEAD):    FAIL — ${it.message}"); return }

    if (!execute) {
        println("\nDRY-RUN OK — подпись валидна, S3 отвечает. Для реальной заливки добавь --execute.")
        return
    }

    // Детерминированный тест-блоб + кандидат. hash → путь блоба flac/<hh>/<hash>.flac.
    val payload = "humanonly s3 archive smoke ${System.currentTimeMillis()}".toByteArray()
    val trackId = "smoke-s3"
    val hash = sha256(payload)

    val tmpDir = Files.createTempDirectory("humanonly-s3-smoke")
    val local = FsLocalStore(tmpDir)
    local.write(trackId, payload)

    val candidate = ArchiveCandidate(
        trackId = trackId, hash = hash, codec = "flac", quality = "lossless",
        verdict = "clean", detectorVersion = "live-smoke",
    )

    val summary = Archiver(blobs, local, MemManifestStore()).run(listOf(candidate))
    val item = summary.items.single()
    println("archive:          status=${item.status} path=${item.archivePath} reason=${item.reason ?: "-"}")

    val archivePath = Archiver.archivePath(hash, "flac")
    val back = blobs.get(archivePath)
    val ok = back != null && back.contentEquals(payload)
    println("verify download:  ${if (ok) "OK (${back!!.size}B совпало)" else "FAIL"}")

    println("\n${if (ok) "OK" else "FAIL"} — тест-блоб залит в S3: $archivePath (в бакете ${cfg.bucket}).")
}

/** Манифест в памяти: S3 хранит только блобы (§9), синк-манифест живёт на Диске/VPS — здесь не нужен. */
private class MemManifestStore : ManifestStore {
    private var manifest = ArchiveManifest()
    override fun load(): ArchiveManifest = manifest
    override fun save(manifest: ArchiveManifest) { this.manifest = manifest }
}

private data class S3Config(
    val endpoint: String,
    val bucket: String,
    val region: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val service: String,
)

private val cfgJson: Json = Json { ignoreUnknownKeys = true }

/** Читает S3-конфиг из json-файла, ключи не логирует. */
private fun readConfig(path: Path): S3Config {
    val o = cfgJson.parseToJsonElement(Files.readString(path)).jsonObject
    fun req(k: String): String {
        val v = o[k]?.jsonPrimitive?.content
        require(!v.isNullOrBlank()) { "в конфиге нет непустого '$k'" }
        return v
    }
    return S3Config(
        endpoint = req("endpoint"),
        bucket = req("bucket"),
        region = req("region"),
        accessKeyId = req("accessKeyId"),
        secretAccessKey = req("secretAccessKey"),
        service = o["service"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "s3",
    )
}

private fun sha256(b: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
