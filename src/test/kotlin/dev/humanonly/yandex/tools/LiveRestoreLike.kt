package dev.humanonly.yandex.tools

import dev.humanonly.backup.BackupSerialization
import dev.humanonly.yandex.HttpYandexTransport
import dev.humanonly.yandex.RateLimiter
import dev.humanonly.yandex.YandexClient
import dev.humanonly.yandex.YandexConfig
import dev.humanonly.yandex.YandexJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Живое F7-ВОССТАНОВЛЕНИЕ лайков из бэкапа (§F7, хард-правило 5) — не юнит-тест (нужны токен и сеть).
 *
 * Зачем отдельный инструмент: на живом API ЯМ **дизлайк трека СНИМАЕТ лайк** (проверено на тест-акке
 * 2026-07-04). `undislike` возвращает трек из dislikes, но лайк не восстанавливает. Поэтому корректный
 * откат AI-дизлайка обязан ВЕРНУТЬ лайк — что и делает этот инструмент по снятому ранее бэкапу.
 *
 * Две фазы (деструктив/мутация под хард-правило 5 — dry-run → подтверждение; бэкап уже снят ранее):
 *   - без `--execute` (дефолт): читает акк (uid/likes) и бэкап, считает недостающие лайки
 *     (`backup.likes − current`), печатает ПЛАН восстановления. НИ ОДНОЙ мутации акка.
 *   - с `--execute`: ре-лайкает недостающие треки через [YandexClient.likeTrack] → верификация
 *     (набор likes стал надмножеством бэкапа). Обратимо (`likes/tracks/remove`).
 *
 * Хард-правила: 3 — только тестовый акк; 4 — токен/PII в stdout НЕ печатаем (только счётчики/id треков);
 * 7 — реальный rate-limiter; 9 — эндпоинт лайка выверен по референс-репо (MarshalX/yandex-music-api).
 *
 * Запуск: gradlew liveRestoreLike --args="<токен> <путь к likes-live-*.json> [--execute]"
 */
fun main(args: Array<String>) {
    require(args.size >= 2) {
        "нужны: путь к токену (json access_token) и путь к бэкапу likes-live-*.json [+ --execute]"
    }
    val tokenPath = Path.of(args[0])
    val backupPath = Path.of(args[1])
    val execute = args.drop(2).any { it == "--execute" }
    val token = extractAccessTokenForRestore(tokenPath)

    val manifest = BackupSerialization.decode(Files.readString(backupPath))
    val backupLikes = manifest.likes.map { it.trackId }
    println("бэкап:              ${backupPath.fileName} (v${manifest.backupFormatVersion}, ${backupLikes.size} лайков, §12 без PII)")

    val config = YandexConfig(accessToken = token, clientHeader = "YandexMusicAndroid/24023621")
    val limiter = RateLimiter(System::nanoTime, { ns -> Thread.sleep(ns / 1_000_000, (ns % 1_000_000).toInt()) })
    val client = YandexClient(config, HttpYandexTransport(), limiter)

    val uid = client.accountUid().toString()
    println("account.uid:        получен (${uid.length} цифр)")

    val likesNow = client.likedTrackIds(uid).toSet()
    println("likes(now):         ${likesNow.size}")

    val missing = backupLikes.filter { it !in likesNow }
    println("недостающих лайков: ${missing.size} ${if (missing.isNotEmpty()) missing else ""}")

    if (missing.isEmpty()) {
        println("\nВосстанавливать нечего — все лайки из бэкапа на месте. Акк уже в исходном состоянии.")
        return
    }

    println("\nПЛАН восстановления (dry-run):")
    missing.forEach { println("  - LIKE (likes/tracks/add-multiple) [$it]") }

    if (!execute) {
        println("\nDRY-RUN. Акк НЕ изменён. Для реального восстановления добавь --execute.")
        return
    }

    println("\n=== EXECUTE: возврат лайков (хард-правило 5, обратимо через likes/tracks/remove) ===")
    var applied = 0
    for (trackId in missing) {
        if (client.likeTrack(uid, trackId)) applied++
        println("  like [$trackId]: ok")
    }
    println("возвращено лайков:  $applied")

    val likesAfter = client.likedTrackIds(uid).toSet()
    val restored = backupLikes.all { it in likesAfter }
    println("верификация:        likes(after)=${likesAfter.size}  все лайки из бэкапа на месте=$restored")
    println(if (restored) "\nOK — лайки восстановлены; акк вернулся в исходное состояние по бэкапу."
    else "\nВНИМАНИЕ — не все лайки восстановились; проверь вручную (бэкап: ${backupPath.fileName}).")
}

/** Достаёт access_token из json-файла, ничего не логируя (хард-правило 4). */
private fun extractAccessTokenForRestore(path: Path): String {
    val json = YandexJson.parseToJsonElement(Files.readString(path)).jsonObject
    val token = json["access_token"]?.jsonPrimitive?.content
    require(!token.isNullOrBlank()) { "в файле нет непустого access_token" }
    return token
}
