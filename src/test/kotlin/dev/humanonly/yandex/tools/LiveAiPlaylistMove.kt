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
 * Живая ПОЛНАЯ цепочка §F4 MOVE_TO_PLAYLIST на тестовом акке — деструктив под хард-правило 5:
 * **dry-run плана → снятый бэкап → подтверждение → авто-откат**. Не юнит-тест (в CI не гоняется —
 * нужны токен и сеть). В отличие от изолированного [LivePlaylistMove] (свой временный плейлист, лайки
 * не трогает) — здесь прогоняется РЕАЛЬНАЯ цепочка диспетчера: `dislike` (живой дизлайк ЯМ СНИМАЕТ лайк!)
 * → `addToPlaylist` в плейлист «Определены как ИИ треки». Поэтому обязателен бэкап лайков (F7) ДО.
 *
 * Плейлист «Определены как ИИ треки» инструмент СОЗДАЁТ сам (приватный, знает его kind, удаляет за собой),
 * так что после отката + удаления плейлиста аккаунт полностью в исходном состоянии.
 *
 * Две фазы:
 *   - без `--execute` (дефолт): читает акк (uid/likes/dislikes), выбирает цель, снимает бэкап лайков,
 *     печатает ПЛАН [ActionDispatcher.plan] (dislike→disliked, add→moved_to_playlist) и парити
 *     execute(confirm=false)→отказ. НИ ОДНОЙ мутации, плейлист НЕ создаётся.
 *   - с `--execute`: свежий бэкап → создать плейлист → [ActionDispatcher.execute] (дизлайк снимает лайк +
 *     перенос) → верификация (в dislikes, в плейлисте, лайк снят) → авто-откат [ActionDispatcher.rollback]
 *     (убрать из плейлиста + undislike + вернуть лайк, → human_confirmed) → верификация → удалить плейлист.
 *
 * Хард-правила: 3 — только тестовый акк; 4 — токен/PII в stdout НЕ печатаем (только счётчики/флаги/id);
 * 7 — реальный rate-limiter (не отключаем даже тут); 9 — эндпоинты выверены по референс-репо
 * (MarshalX/yandex-music-api). Бэкап пишем рядом с токен-файлом (gitignored, вне публичного репо).
 *
 * Запуск: gradlew liveAiPlaylistMove --args="<путь к тест-токену> [--execute]"
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "нужен путь к файлу токена (json с полем access_token) [+ --execute]" }
    val tokenPath = Path.of(args[0])
    val execute = args.drop(1).any { it == "--execute" }
    val token = extractAccessTokenForAiMove(tokenPath)

    val config = YandexConfig(accessToken = token, clientHeader = "YandexMusicAndroid/24023621")
    val limiter = RateLimiter(System::nanoTime, { ns -> Thread.sleep(ns / 1_000_000, (ns % 1_000_000).toInt()) })
    val client = YandexClient(config, HttpYandexTransport(), limiter)

    val uid = client.accountUid().toString()
    val library = YandexLibraryActions(client, uid)
    println("account.uid:            получен (${uid.length} цифр)")

    val likesBefore = client.likedTrackIds(uid)
    val dislikesBefore = client.dislikedTrackIds(uid).toSet()
    println("likes(before):          ${likesBefore.size}")
    println("dislikes(before):       ${dislikesBefore.size}")

    // Цель — лайкнутый трек, ещё НЕ в дизлайках (реальный сценарий авто-чистки AI-лайка).
    val target = likesBefore.firstOrNull { it !in dislikesBefore }
    if (target == null) {
        println("нет подходящей цели (лайк не в дизлайках) — добавь трек в лайки на тест-акк и повтори.")
        return
    }
    println("target trackId:         $target")

    // Бэкап пишем рядом с токеном (gitignored). Гуард отдаёт id только при успешной записи (хард-правило 5).
    val backupDir = tokenPath.toAbsolutePath().parent
    val guard = FileLikesBackupGuardForAiMove(backupDir, likesBefore) { System.currentTimeMillis() }
    val candidate = ActionCandidate(target, TrackState.AI_CONFIRMED)

    // ── dry-run плана (парити с фактическим прогоном, §16). Плейлист ещё не создан → плейсхолдер kind. ──
    val dryDispatcher = ActionDispatcher(mode = ActionMode.MOVE_TO_PLAYLIST, library = library, backup = guard, aiPlaylistKind = "PENDING_CREATE")
    val plan = dryDispatcher.plan(listOf(candidate))
    println("\nПЛАН (dry-run, §F4 MOVE_TO_PLAYLIST):")
    plan.steps.forEach { println("  - ${it.op}: ${it.from.code} → ${it.to.code}  [$target]") }
    println("  (внимание: dislike СНИМАЕТ лайк — откат вернёт его из бэкапа)")

    // ── снятый бэкап (F7, хард-правило 5) — реально пишем на диск ДО деструктива ───
    val backupId = guard.latestBackupId()
    println("\nбэкап лайков:           ${guard.lastBackupPath ?: "СБОЙ ЗАПИСИ"} (id=$backupId, ${likesBefore.size} записей, §12 без PII)")

    val refused = dryDispatcher.execute(listOf(candidate), confirm = false)
    println("execute(confirm=false): executed=${refused.executed} reason=${refused.refusedReason}")
    check(refused.plan.steps == plan.steps) { "план execute≠план dry-run (нарушен инвариант §16)" }

    if (!execute) {
        println("\nDRY-RUN завершён. Акк НЕ изменён, плейлист НЕ создан. Для реального прогона: --args=\"<токен> --execute\".")
        return
    }

    // ── ФАЗА 2: создать плейлист → реальная цепочка → верификация → откат → удалить плейлист ──
    println("\n=== EXECUTE: полная цепочка §F4 (хард-правило 5, будет откат) ===")
    val kind = client.createPlaylist(uid, AI_PLAYLIST_TITLE, visibility = "private")
    println("создан плейлист:        «$AI_PLAYLIST_TITLE» kind=$kind")

    try {
        val dispatcher = ActionDispatcher(mode = ActionMode.MOVE_TO_PLAYLIST, library = library, backup = guard, aiPlaylistKind = kind)
        val result = dispatcher.execute(listOf(candidate), confirm = true)
        println("execute(confirm=true):  executed=${result.executed} applied=${result.applied} backupId=${result.backupId}")

        val dislikesAfter = client.dislikedTrackIds(uid).toSet()
        val likesAfter = client.likedTrackIds(uid).toSet()
        val inPlaylistAfter = target in client.playlist(uid, kind).trackIds
        println("верификация переноса:   в dislikes=${target in dislikesAfter}  в плейлисте=$inPlaylistAfter  лайк снят=${target !in likesAfter}")

        // Откат «не ИИ» — убрать из плейлиста + undislike + вернуть лайк (→ human_confirmed).
        println("\n=== ОТКАТ: removeFromPlaylist + undislike + relike ===")
        val rollback = dispatcher.rollback(target, TrackState.MOVED_TO_PLAYLIST)
        println("rollback:               applied=${rollback.applied}")

        val dislikesRestored = client.dislikedTrackIds(uid).toSet()
        val likesRestored = client.likedTrackIds(uid).toSet()
        val inPlaylistRestored = target in client.playlist(uid, kind).trackIds
        println("верификация отката:     ушёл из dislikes=${target !in dislikesRestored}  ушёл из плейлиста=${!inPlaylistRestored}  лайк вернулся=${target in likesRestored}")

        val ok = target in dislikesAfter && inPlaylistAfter && target !in likesAfter &&
            target !in dislikesRestored && !inPlaylistRestored && target in likesRestored &&
            likesRestored == likesBefore.toSet()
        println(if (ok) "\nOK — полная цепочка §F4 применилась и полностью откачена."
        else "\nВНИМАНИЕ — состояние не совпало с ожидаемым; проверь вручную (бэкап: ${guard.lastBackupPath}).")
    } finally {
        val deleted = client.deletePlaylist(uid, kind)
        println("cleanup:                плейлист kind=$kind удалён=$deleted (акк в исходном состоянии)")
    }
}

private const val AI_PLAYLIST_TITLE = "Определены как ИИ треки"

/**
 * [BackupGuard] поверх файла: снимает pre-destructive бэкап лайков (F7, §12 — только id/время) на диск
 * атомарно (tmp→rename) и отдаёт имя файла как id. `null` при сбое записи → [ActionDispatcher] откажет.
 */
private class FileLikesBackupGuardForAiMove(
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
private fun extractAccessTokenForAiMove(path: Path): String {
    val json = YandexJson.parseToJsonElement(Files.readString(path)).jsonObject
    val token = json["access_token"]?.jsonPrimitive?.content
    require(!token.isNullOrBlank()) { "в файле нет непустого access_token" }
    return token
}
