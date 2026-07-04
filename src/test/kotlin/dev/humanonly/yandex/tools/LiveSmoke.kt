package dev.humanonly.yandex.tools

import dev.humanonly.yandex.HttpYandexTransport
import dev.humanonly.yandex.RateLimiter
import dev.humanonly.yandex.TrackDownloader
import dev.humanonly.yandex.YandexClient
import dev.humanonly.yandex.YandexConfig
import dev.humanonly.yandex.YandexJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Живой smoke yandex-слоя (подзадача 1c) — реальный сквозной прогон против API/CDN ЯМ:
 *   account/status → likes → подписанный get-file-info → скачивание → AES-CTR дешифровка.
 *
 * НЕ юнит-тест (в CI не гоняется — нужен токен и сеть). Запуск только вручную:
 *   gradlew liveSmoke --args="<путь к json-файлу с access_token>"
 *
 * Токен читается из файла-аргумента (gitignored, ВНЕ этого публичного репо) и НЕ печатается
 * (хард-правило 4). В stdout — только флаги/счётчики/длины, без секретов и без PII (§12).
 * Использовать ТОЛЬКО тестовый акк (хард-правило 3). Rate-limit активен (хард-правило 7).
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "нужен путь к файлу токена (json с полем access_token)" }
    val token = extractAccessToken(Path.of(args[0]))

    val config = YandexConfig(accessToken = token, clientHeader = "YandexMusicAndroid/24023621")
    val transport = HttpYandexTransport()
    // Реальный сон по времени лимитера (в тестах инъектируется виртуальное время — тут живой прогон).
    val limiter = RateLimiter(System::nanoTime, { ns -> Thread.sleep(ns / 1_000_000, (ns % 1_000_000).toInt()) })
    val client = YandexClient(config, transport, limiter)

    val uid = client.accountUid()
    println("account.uid:        получен (${uid.toString().length} цифр)")

    val ids = client.likedTrackIds(uid.toString())
    println("liked_tracks:       ${ids.size}")
    if (ids.isEmpty()) {
        println("нет лайков — добавь треки на тестовый акк и повтори.")
        return
    }

    val trackId = ids.first()
    val info = client.getFileInfo(trackId, System.currentTimeMillis() / 1000)
    println("get-file-info:      codec=${info.codec} quality=${info.quality} urls=${info.urls.size} keyLen=${info.key.length}")

    val downloader = TrackDownloader(client, transport, limiter)
    val result = downloader.download(info)
    println("download+decrypt:   ${result.decrypted.size}B container=${result.container} sha256=${result.sha256.take(12)}…")

    println("\nOK — живой Kotlin yandex-слой прошёл: likes → get-file-info → download → AES-CTR decrypt.")
}

/** Достаёт access_token из json-файла, ничего не логируя. */
private fun extractAccessToken(path: Path): String {
    val json = YandexJson.parseToJsonElement(Files.readString(path)).jsonObject
    val token = json["access_token"]?.jsonPrimitive?.content
    require(!token.isNullOrBlank()) { "в файле нет непустого access_token" }
    return token
}
