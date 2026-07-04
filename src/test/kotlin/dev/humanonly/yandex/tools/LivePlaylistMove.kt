package dev.humanonly.yandex.tools

import dev.humanonly.yandex.HttpYandexTransport
import dev.humanonly.yandex.RateLimiter
import dev.humanonly.yandex.YandexClient
import dev.humanonly.yandex.YandexConfig
import dev.humanonly.yandex.YandexJson
import dev.humanonly.yandex.YandexLibraryActions
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Живой smoke режима MOVE_TO_PLAYLIST (§F4) — album-aware `change-relative` на тестовом акке.
 * Не юнит-тест (нужны токен и сеть). Самодостаточен и ПОЛНОСТЬЮ обратим: создаёт СВОЙ временный
 * плейлист, двигает один трек внутрь/наружу и удаляет плейлист за собой. **Лайки/дизлайки НЕ трогает**
 * (изолируем именно механику плейлиста — в отличие от полной цепочки dispatcher'а).
 *
 * Две фазы (мутация под хард-правило 5 — dry-run → подтверждение; операции обратимы, бэкап не нужен —
 * временный плейлист удаляется целиком):
 *   - без `--execute`: печатает ПЛАН (какой трек и куда), НИ ОДНОЙ мутации.
 *   - с `--execute`: create playlist → add(target) → verify(в плейлисте) → remove(target) →
 *     verify(нет) → delete playlist. Акк возвращается в исходное (лишнего плейлиста не остаётся).
 *
 * Хард-правила: 3 — тестовый акк; 4 — токен/PII не в stdout (только id/счётчики); 7 — реальный лимитер;
 * 9 — форма change-relative/create/delete выверена по референс-репо (MarshalX/yandex-music-api).
 *
 * Запуск: gradlew livePlaylistMove --args="<токен> [--execute]"
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "нужен путь к файлу токена (json с полем access_token) [+ --execute]" }
    val tokenPath = Path.of(args[0])
    val execute = args.drop(1).any { it == "--execute" }
    val token = extractAccessTokenForPlaylist(tokenPath)

    val config = YandexConfig(accessToken = token, clientHeader = "YandexMusicAndroid/24023621")
    val limiter = RateLimiter(System::nanoTime, { ns -> Thread.sleep(ns / 1_000_000, (ns % 1_000_000).toInt()) })
    val client = YandexClient(config, HttpYandexTransport(), limiter)

    val uid = client.accountUid().toString()
    val lib = YandexLibraryActions(client, uid)
    println("account.uid:        получен (${uid.length} цифр)")

    // Цель — любой лайкнутый трек (у него точно есть albumId в метаданных). Лайк НЕ трогаем.
    val likes = client.likedTrackIds(uid)
    val target = likes.firstOrNull()
    if (target == null) {
        println("нет ни одного лайка — добавь трек в лайки на тест-акк и повтори (нужен трек с альбомом).")
        return
    }
    println("target trackId:     $target")
    println("\nПЛАН (dry-run, §F4 MOVE_TO_PLAYLIST):")
    println("  - create временный плейлист «$LIVE_PLAYLIST_TITLE» (private)")
    println("  - addToPlaylist [$target] (album-aware change-relative)")
    println("  - removeFromPlaylist [$target]")
    println("  - delete временный плейлист (очистка)")

    if (!execute) {
        println("\nDRY-RUN завершён. Акк НЕ изменён. Для реального прогона: --args=\"<токен> --execute\".")
        return
    }

    println("\n=== EXECUTE: изолированный live-цикл плейлиста (полностью обратим) ===")
    val kind = client.createPlaylist(uid, LIVE_PLAYLIST_TITLE, visibility = "private")
    println("создан плейлист:    kind=$kind")
    try {
        val added = lib.addToPlaylist(target, kind)
        val afterAdd = client.playlist(uid, kind).trackIds
        println("addToPlaylist:      applied=$added  в плейлисте=${target in afterAdd} (${afterAdd.size} треков)")

        val removed = lib.removeFromPlaylist(target, kind)
        val afterRemove = client.playlist(uid, kind).trackIds
        println("removeFromPlaylist: applied=$removed  ушёл из плейлиста=${target !in afterRemove} (${afterRemove.size} треков)")

        val ok = target in afterAdd && target !in afterRemove
        println(if (ok) "\nOK — album-aware add/remove в плейлист работает на живом API."
        else "\nВНИМАНИЕ — состояние не совпало с ожидаемым; проверь вручную (плейлист kind=$kind).")
    } finally {
        val deleted = client.deletePlaylist(uid, kind)
        println("cleanup:            плейлист kind=$kind удалён=$deleted (акк в исходном состоянии)")
    }
}

private const val LIVE_PLAYLIST_TITLE = "humanonly-live-test (delete me)"

/** Достаёт access_token из json-файла, ничего не логируя (хард-правило 4). */
private fun extractAccessTokenForPlaylist(path: Path): String {
    val json = YandexJson.parseToJsonElement(Files.readString(path)).jsonObject
    val token = json["access_token"]?.jsonPrimitive?.content
    require(!token.isNullOrBlank()) { "в файле нет непустого access_token" }
    return token
}
