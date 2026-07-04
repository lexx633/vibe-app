package dev.humanonly.yandex.tools

import dev.humanonly.backup.BackupManifest
import dev.humanonly.backup.BackupSerialization
import dev.humanonly.backup.LikedTrackEntry
import dev.humanonly.pipeline.ActionCandidate
import dev.humanonly.pipeline.ActionDispatcher
import dev.humanonly.pipeline.ActionMode
import dev.humanonly.pipeline.BackupGuard
import dev.humanonly.state.TrackState
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
 * Живой авто-дизлайк ОДНОГО трека на тестовом акке (ТЗ §F4, §5, §6.2) — деструктив под хард-правило 5:
 * **dry-run плана → снятый бэкап → подтверждение**. Не юнит-тест (в CI не гоняется — нужны токен и сеть).
 *
 * Две фазы, чтобы деструктив не сработал случайно:
 *   - без `--execute` (дефолт): читает акк (uid/likes/dislikes), выбирает цель, печатает ПЛАН [ActionDispatcher.plan],
 *     снимает pre-destructive бэкап лайков (F7, §12 — только id/время) в файл, вызывает execute(confirm=false)
 *     → отказ REFUSED_NOT_CONFIRMED. НИ ОДНОЙ мутации акка.
 *   - с `--execute`: свежий бэкап → реальный дизлайк через [ActionDispatcher.execute] → верификация
 *     (трек появился в dislikes, набор likes НЕ изменился) → авто-откат [ActionDispatcher.rollback] (undislike)
 *     → верификация (трек ушёл из dislikes). Акк возвращается в исходное состояние.
 *
 * Хард-правила: 3 — только тестовый акк; 4 — токен/PII в stdout НЕ печатаем (только счётчики/флаги/id);
 * 7 — реальный rate-limiter (не отключаем даже тут); 9 — эндпоинты дизлайка выверены по референс-репо
 * (MarshalX/yandex-music-api). Бэкап пишем в тот же каталог, что токен-файл (gitignored, вне публичного репо).
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "нужен путь к файлу токена (json с полем access_token) [+ --execute]" }
    val tokenPath = Path.of(args[0])
    val execute = args.drop(1).any { it == "--execute" }
    val token = extractAccessTokenForDislike(tokenPath)

    val config = YandexConfig(accessToken = token, clientHeader = "YandexMusicAndroid/24023621")
    val limiter = RateLimiter(System::nanoTime, { ns -> Thread.sleep(ns / 1_000_000, (ns % 1_000_000).toInt()) })
    val client = YandexClient(config, HttpYandexTransport(), limiter)

    val uid = client.accountUid().toString()
    val library = YandexLibraryActions(client, uid)
    println("account.uid:        получен (${uid.length} цифр)")

    val likesBefore = client.likedTrackIds(uid)
    val dislikesBefore = client.dislikedTrackIds(uid).toSet()
    println("likes(before):      ${likesBefore.size}")
    println("dislikes(before):   ${dislikesBefore.size}")

    // Цель — лайкнутый трек, ещё НЕ в дизлайках (реальный сценарий авто-чистки AI-лайка).
    val target = likesBefore.firstOrNull { it !in dislikesBefore }
    if (target == null) {
        println("нет подходящей цели (лайк не в дизлайках) — добавь трек в лайки на тест-акк и повтори.")
        return
    }
    println("target trackId:     $target")

    // Бэкап пишем рядом с токеном (gitignored). Гуард отдаёт id только при успешной записи (хард-правило 5).
    val backupDir = tokenPath.toAbsolutePath().parent
    val guard = FileLikesBackupGuard(backupDir, likesBefore) { System.currentTimeMillis() }

    val dispatcher = ActionDispatcher(mode = ActionMode.DISLIKE_ONLY, library = library, backup = guard)
    val candidate = ActionCandidate(target, TrackState.AI_CONFIRMED)

    // ── dry-run плана (парити с фактическим прогоном, §16) ─────────────────────────
    val plan = dispatcher.plan(listOf(candidate))
    println("\nПЛАН (dry-run, §F4):")
    plan.steps.forEach { println("  - ${it.op}: ${it.from.code} → ${it.to.code}  [$target]") }

    // ── снятый бэкап (F7, хард-правило 5) — реально пишем на диск ДО деструктива ───
    // execute(confirm=false) отказывает ещё до обращения к guard, поэтому бэкап снимаем явно здесь.
    val backupId = guard.latestBackupId()
    println("\nбэкап лайков:       ${guard.lastBackupPath ?: "СБОЙ ЗАПИСИ"} (id=$backupId, ${likesBefore.size} записей, §12 без PII)")

    // dry-run парити: execute без подтверждения возвращает тот же план и отказ (не мутирует).
    val refused = dispatcher.execute(listOf(candidate), confirm = false)
    println("execute(confirm=false): executed=${refused.executed} reason=${refused.refusedReason}")
    check(refused.plan.steps == plan.steps) { "план execute≠план dry-run (нарушен инвариант §16)" }

    if (!execute) {
        println("\nDRY-RUN завершён. Акк НЕ изменён. Для реального дизлайка+отката: --args=\"<токен> --execute\".")
        return
    }

    // ── ФАЗА 2: реальный дизлайк → верификация → откат ────────────────────────────
    println("\n=== EXECUTE: реальный дизлайк (хард-правило 5, будет откат) ===")
    val result = dispatcher.execute(listOf(candidate), confirm = true)
    println("execute(confirm=true):  executed=${result.executed} applied=${result.applied} backupId=${result.backupId}")

    val dislikesAfter = client.dislikedTrackIds(uid).toSet()
    val likesAfterDislike = client.likedTrackIds(uid)
    println("верификация дизлайка:   в dislikes=${target in dislikesAfter}  likes сохранены=${likesAfterDislike.toSet() == likesBefore.toSet()} (${likesAfterDislike.size})")

    // Откат «не ИИ» (undislike) — возвращаем акк в исходное состояние.
    println("\n=== ОТКАТ: undislike ===")
    val rollback = dispatcher.rollback(target, TrackState.DISLIKED)
    println("rollback:               applied=${rollback.applied}")

    val dislikesRestored = client.dislikedTrackIds(uid).toSet()
    val likesRestored = client.likedTrackIds(uid)
    println("верификация отката:     ушёл из dislikes=${target !in dislikesRestored}  likes целы=${likesRestored.toSet() == likesBefore.toSet()} (${likesRestored.size})")

    val ok = target in dislikesAfter && target !in dislikesRestored && likesRestored.toSet() == likesBefore.toSet()
    println(if (ok) "\nOK — живой авто-дизлайк применился и полностью откачен; акк в исходном состоянии."
    else "\nВНИМАНИЕ — состояние не совпало с ожидаемым; проверь вручную (бэкап: ${guard.lastBackupPath}).")
}

/**
 * [BackupGuard] поверх файла: снимает pre-destructive бэкап лайков (F7, §12 — только id/время) на диск
 * атомарно (tmp→rename) и отдаёт имя файла как id. `null` при сбое записи → [ActionDispatcher] откажет.
 */
private class FileLikesBackupGuard(
    private val dir: Path,
    private val likedTrackIds: List<String>,
    private val now: () -> Long,
) : BackupGuard {
    var lastBackupPath: String? = null
        private set

    override fun latestBackupId(): String? = runCatching {
        val createdAt = now()
        val manifest = BackupManifest(
            createdAt = createdAt,
            likes = likedTrackIds.map { LikedTrackEntry(trackId = it, likedAt = createdAt) },
        )
        val name = "likes-live-$createdAt.json"
        val target = dir.resolve(name)
        val tmp = dir.resolve("$name.tmp")
        Files.writeString(tmp, BackupSerialization.encode(manifest))
        Files.move(tmp, target)
        lastBackupPath = target.toString()
        name
    }.getOrNull()
}

/** Достаёт access_token из json-файла, ничего не логируя (хард-правило 4). */
private fun extractAccessTokenForDislike(path: Path): String {
    val json = YandexJson.parseToJsonElement(Files.readString(path)).jsonObject
    val token = json["access_token"]?.jsonPrimitive?.content
    require(!token.isNullOrBlank()) { "в файле нет непустого access_token" }
    return token
}
