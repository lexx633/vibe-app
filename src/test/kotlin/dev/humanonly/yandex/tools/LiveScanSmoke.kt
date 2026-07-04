package dev.humanonly.yandex.tools

import dev.humanonly.db.ArtistEnricher
import dev.humanonly.db.JdbcDb
import dev.humanonly.db.initSchema
import dev.humanonly.db.LiveScanSource
import dev.humanonly.db.SqlScanSource
import dev.humanonly.db.TrackRepository
import dev.humanonly.db.YandexLibraryReader
import dev.humanonly.db.YandexMetaLookup
import dev.humanonly.detector.SloplessGate
import dev.humanonly.yandex.Endpoints
import dev.humanonly.yandex.HttpYandexTransport
import dev.humanonly.yandex.RateLimiter
import dev.humanonly.yandex.YandexClient
import dev.humanonly.yandex.YandexConfig
import dev.humanonly.yandex.YandexJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Живой smoke live-скана (TODO :android #1) — реальный сквозной прогон сети ЯМ → БД → scan_delta:
 *   account/status → likes → upsertDiscovered → enrich artist_id → SqlScanSource.newCandidates().
 *
 * Плюс проверка DTO ПО ФАКТУ (хард-правило 9): печатает СХЕМУ сырых ответов likes/track_metadata —
 * только имена полей и типы, значения-листья редактируются (`<str:len>` / `<num>` / `<bool>`), поэтому
 * ни PII (§12: title/artist name), ни секреты (правило 4) в stdout не попадают.
 *
 * НЕ юнит-тест (в CI не гоняется — нужен токен и сеть). Запуск только вручную:
 *   gradlew liveScanSmoke --args="<путь к json-токену> [путь к snapshot slopless.json]"
 *
 * Только тестовый акк (правило 3). Rate-limit активен, ≤1 rps (правило 7). БД — in-memory (ничего
 * на диск/в ЯМ не пишем: чистое чтение, ничего деструктивного).
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "нужен путь к файлу токена (json с полем access_token)" }
    val token = extractAccessTokenForScan(Path.of(args[0]))
    val gate = args.getOrNull(1)?.let { SloplessGate.fromJson(Files.readString(Path.of(it))) }

    val config = YandexConfig(accessToken = token, clientHeader = "YandexMusicAndroid/24023621")
    val transport = HttpYandexTransport()
    val limiter = RateLimiter(System::nanoTime, { ns -> Thread.sleep(ns / 1_000_000, (ns % 1_000_000).toInt()) })
    val client = YandexClient(config, transport, limiter)

    // ── сеть → БД → scan_delta (та же обвязка, что в ServiceLocator для устройства) ──
    val db = JdbcDb()
    db.initSchema()
    val repo = TrackRepository(db) { System.currentTimeMillis() }
    val indexDelta = SqlScanSource(db)
    val source = LiveScanSource(
        YandexLibraryReader(client),
        repo,
        indexDelta,
        ArtistEnricher(repo, YandexMetaLookup(client)),
    )

    val uid = client.accountUid()
    println("account.uid:        получен (${uid.toString().length} цифр)")

    val ids = client.likedTrackIds(uid.toString())
    println("liked_tracks:       ${ids.size}")
    if (ids.isEmpty()) {
        println("нет лайков — добавь треки на тестовый акк и повтори.")
        db.close(); return
    }

    // ── DTO-верификация по факту (хард-правило 9): схема сырых ответов, значения редактированы ──
    println("\n── СХЕМА сырого likes-ответа (только поля/типы, значения скрыты) ──")
    println(shapeOf(rawBody(transport, config, Endpoints.likes(config, uid.toString()))))

    println("\n── СХЕМА сырого track_metadata (первый лайк) ──")
    println(shapeOf(rawBody(transport, config, Endpoints.trackMetadata(config, ids.first()))))

    // ── полный прогон конвейера источника ──
    val candidates = source.newCandidates()
    val withArtist = candidates.count { it.artistId.isNotBlank() }
    println("\nscan_delta:         ${candidates.size} кандидатов")
    println("  с artist_id:      $withArtist (enrich дозаполнил)")
    println("  без artist_id:    ${candidates.size - withArtist}")

    if (gate != null) {
        val hits = candidates.count { gate.isAiArtist(it.artistId) }
        println("slopless gate:      база=${gate.size} arts (v=${gate.version}) → hits=$hits из ${candidates.size}")
    } else {
        println("slopless gate:      снапшот не передан (arg #2) — гейт пропущен")
    }

    println("\nOK — live-скан прошёл: likes → БД → enrich → scan_delta.")
    db.close()
}

/** Сырое тело ответа ЯМ (для DTO-верификации). Идёт мимо DTO, но через тот же транспорт/заголовки. */
private fun rawBody(transport: HttpYandexTransport, config: YandexConfig, req: Endpoints.Request): String =
    transport.getJson(req.url, req.params, config.authHeaders())

/** Достаёт access_token из json-файла, ничего не логируя (правило 4). */
private fun extractAccessTokenForScan(path: Path): String {
    val json = YandexJson.parseToJsonElement(Files.readString(path)).jsonObject
    val token = json["access_token"]?.jsonPrimitive?.content
    require(!token.isNullOrBlank()) { "в файле нет непустого access_token" }
    return token
}

/**
 * Рекурсивный дамп СХЕМЫ json: имена полей + типы. Значения-листья редактируются
 * (`<str:len>` / `<num>` / `<bool>` / `null`), чтобы не печатать PII (§12) и секреты (правило 4).
 * Массивы: длина + схема первого элемента. Так DTO проверяется по факту без утечки данных.
 */
private fun shapeOf(rawJson: String, indent: String = ""): String =
    renderShape(YandexJson.parseToJsonElement(rawJson), indent)

private fun renderShape(el: JsonElement, indent: String): String = when (el) {
    is JsonObject -> el.entries.joinToString("\n") { (k, v) ->
        when (v) {
            is JsonObject, is JsonArray -> "$indent$k:\n${renderShape(v, "$indent  ")}"
            else -> "$indent$k: ${leaf(v)}"
        }
    }.ifEmpty { "$indent{}" }
    is JsonArray -> if (el.isEmpty()) "$indent[0]" else
        "$indent[${el.size}] of:\n${renderShape(el.first(), "$indent  ")}"
    else -> "$indent${leaf(el)}"
}

private fun leaf(el: JsonElement): String = when {
    el is JsonNull -> "null"
    el is JsonPrimitive && el.isString -> "<str:${el.content.length}>"
    el is JsonPrimitive && (el.content == "true" || el.content == "false") -> "<bool>"
    else -> "<num>"
}
