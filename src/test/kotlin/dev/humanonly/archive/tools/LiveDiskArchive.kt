package dev.humanonly.archive.tools

import dev.humanonly.archive.Archiver
import dev.humanonly.archive.ArchiveCandidate
import dev.humanonly.archive.DISK_DEFAULT_BASE_PATH
import dev.humanonly.archive.FsLocalStore
import dev.humanonly.archive.HttpDiskTransport
import dev.humanonly.archive.YandexDiskBlobStore
import dev.humanonly.archive.YandexDiskClient
import dev.humanonly.archive.YandexDiskManifestStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Живой smoke архивации на Яндекс.Диск (§F6, §9) — сквозной прогон реального [Archiver] поверх
 * [YandexDiskBlobStore]/[YandexDiskManifestStore] против настоящего Диска в папку `/Бекап/vibe`.
 *
 * НЕ юнит-тест (в CI не гоняется — нужен токен и сеть). Запуск вручную:
 *   gradlew liveDiskArchive --args="<путь к json-файлу с access_token>"          # dry-run: только проверки доступа
 *   gradlew liveDiskArchive --args="<путь к json-файлу с access_token> --execute" # реальная заливка тест-блоба+манифеста
 *
 * Токен читается из файла-аргумента (gitignored, ВНЕ публичного репо) и НЕ печатается (хард-правило 4):
 * в stdout только флаги/длины/пути внутри архива, без секрета. Загружаемый тест-файл НЕ удаляется —
 * Owner увидит его в веб-UI Диска (осознанно). Диск — отдельный сервис, не музыкальный API ЯМ.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "нужен путь к файлу токена (json с полем access_token)" }
    val token = extractAccessToken(Path.of(args[0]))
    val execute = args.contains("--execute")

    val client = YandexDiskClient(HttpDiskTransport(token))
    val blobs = YandexDiskBlobStore(client)
    val manifestStore = YandexDiskManifestStore(client)

    println("Диск-архив smoke → базовая папка: $DISK_DEFAULT_BASE_PATH")
    println("режим:              ${if (execute) "EXECUTE (реальная заливка)" else "DRY-RUN (только проверка доступа)"}")

    // Проверка доступа: манифест-load (404 → пустой) подтверждает валидность токена и досягаемость API.
    val existing = manifestStore.load()
    println("манифест доступен:  entries=${existing.entries.size}")

    if (!execute) {
        println("\nDRY-RUN OK — токен валиден, API Диска отвечает. Для реальной заливки добавь --execute.")
        return
    }

    // Детерминированный тест-блоб + кандидат. hash → путь блоба flac/<hh>/<hash>.flac под /Бекап/vibe.
    val payload = "humanonly disk archive smoke ${System.currentTimeMillis()}".toByteArray()
    val trackId = "smoke-disk"
    val hash = sha256(payload)

    // Локальный источник — временная папка (Archiver читает блоб из LocalStore и удаляет после заливки).
    val tmpDir = Files.createTempDirectory("humanonly-disk-smoke")
    val local = FsLocalStore(tmpDir)
    local.write(trackId, payload)

    val candidate = ArchiveCandidate(
        trackId = trackId, hash = hash, codec = "flac", quality = "lossless",
        verdict = "clean", detectorVersion = "live-smoke",
    )

    val summary = Archiver(blobs, local, manifestStore).run(listOf(candidate))
    val item = summary.items.single()
    println("archive:            status=${item.status} path=${item.archivePath} reason=${item.reason ?: "-"}")

    // Верификация: блоб реально лежит в архиве и байтово совпадает.
    val archivePath = Archiver.archivePath(hash, "flac")
    val back = blobs.get(archivePath)
    val ok = back != null && back.contentEquals(payload)
    println("verify download:    ${if (ok) "OK (${back!!.size}B совпало)" else "FAIL"}")

    val manifestAfter = manifestStore.load()
    println("манифест после:     entries=${manifestAfter.entries.size}, есть '$trackId'=${manifestAfter.get(trackId) != null}")

    println("\n${if (ok) "OK" else "FAIL"} — файл ОСТАВЛЕН на Диске: $DISK_DEFAULT_BASE_PATH/$archivePath (+ manifest.json).")
}

private val tokenJson: Json = Json { ignoreUnknownKeys = true }

/** Достаёт access_token из json-файла, ничего не логируя. */
private fun extractAccessToken(path: Path): String {
    val json = tokenJson.parseToJsonElement(Files.readString(path)).jsonObject
    val token = json["access_token"]?.jsonPrimitive?.content
    require(!token.isNullOrBlank()) { "в файле нет непустого access_token" }
    return token
}

private fun sha256(b: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
