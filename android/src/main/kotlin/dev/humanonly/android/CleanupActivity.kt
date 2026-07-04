package dev.humanonly.android

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.humanonly.backup.DryRunExecutor
import dev.humanonly.backup.RestorePlanner
import dev.humanonly.db.DiscoveredTrack
import dev.humanonly.db.TrackRepository
import dev.humanonly.pipeline.CleanupBucket
import dev.humanonly.pipeline.CleanupPlan
import dev.humanonly.pipeline.LibraryCleanup
import dev.humanonly.yandex.YandexClient

/**
 * Чистка ЖИВОЙ библиотеки лайков на устройстве — БЕЗ adb (§F4 + чистка мёртвых лайков). Гоняет реальный
 * продакшн-путь [LibraryCleanup] против ЯМ и показывает результат. Три корзины (решения Owner 2026-07-04):
 *   - мёртвые (трек удалён/недоступен) → снять лайк без дизлайка;
 *   - slopless-гейт (уверенный ИИ)     → дизлайк + плейлист «детект ИИ»;
 *   - серая зона (непонятно)           → плейлист «непонятно», лайк НЕ трогаем (на ручное ревью).
 *
 * Хард-правило 5 (деструктив): сначала «Скан (dry-run)» — только счётчики, НИ ОДНОЙ мутации акка. Затем
 * «Создать плейлисты». И только явный тап по «ВЫПОЛНИТЬ чистку» применяет действия (это подтверждение на
 * устройстве); [DeviceBackupGuard] перед этим снимет восстановимый бэкап лайков — иначе [LibraryCleanup]
 * откажет. Удаление плейлистов — отдельные кнопки (после ручного ревью «непонятно»).
 *
 * Хард-правило 3/4: токен ЯМ — из [ServiceLocator.tokenStore]/запечённого [BakedTokens] (в этап A — ТЕСТОВЫЙ
 * акк). Токен не логируется. Rate-limit к ЯМ реальный (хард-правило 7). PII §12: наружу только id/счётчики.
 */
class CleanupActivity : Activity() {

    private lateinit var out: TextView
    private var lastPlan: CleanupPlan? = null

    /** Флаг кооперативной остановки скана (кнопка «Стоп скан»). Скан проверяет его перед каждым треком. */
    @Volatile private var stopScan = false

    /** Wake-lock фоновых операций: держится, пока идёт хоть одна (счётчик [activeOps]). */
    private val opLock = Any()
    private var activeOps = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        showState()
    }

    private fun buildUi(): ScrollView {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(title("Чистка библиотеки  ·  v${appVersion()}"))

        // 1 — скан: безопасен, акк не трогается; здесь же снимается бэкап лайков.
        root.addView(sectionHeader("1 · Скан (dry-run — акк не трогаем)"))
        root.addView(button("Сканировать библиотеку") { onScan() })
        root.addView(button("Стоп скан", indent = true) { onStopScan() })

        // 2 — применение: под-шаги с отступом; ВЫПОЛНИТЬ — деструктив (красный).
        root.addView(sectionHeader("2 · Применить чистку"))
        root.addView(hint("Сначала создай плейлисты, затем ВЫПОЛНИТЬ. Бэкап лайков снимется сам (хард-правило 5)."))
        root.addView(button("Создать плейлисты «детект ИИ» + «непонятно»", indent = true) { onCreatePlaylists() })
        root.addView(button("ВЫПОЛНИТЬ чистку", indent = true, danger = true) { onExecute() })

        // 3 — откат: план (dry-run) → под-шаг возврата лайков (мутирует акк).
        root.addView(sectionHeader("3 · Откат (F7 — вернуть лайки из бэкапа)"))
        root.addView(button("План отката (dry-run)") { onRestore(execute = false) })
        root.addView(button("Вернуть лайки из бэкапа", indent = true, danger = true) { onRestore(execute = true) })

        // уборка плейлистов — вторичное, под отступом.
        root.addView(sectionHeader("Плейлисты (уборка после ревью)"))
        root.addView(button("Удалить «детект ИИ»", indent = true) { onDeletePlaylist(ai = true) })
        root.addView(button("Удалить «непонятно»", indent = true) { onDeletePlaylist(ai = false) })

        out = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
            text = "Готов."
        }
        root.addView(out)
        return ScrollView(this).apply { addView(root) }
    }

    private fun showState() {
        val prefs = prefs()
        val ai = prefs.getString(KEY_AI_KIND, null)
        val gray = prefs.getString(KEY_GRAY_KIND, null)
        log("Запечённый акк: ${accountLabel()}\nПлейлисты: детект ИИ=${ai ?: "—"} непонятно=${gray ?: "—"}\nЖми «Скан» для dry-run.")
    }

    /** Метка запечённого акк-источника (хард-правило 4: без утечки токена) — "prod"/"test"/"none". */
    private fun accountLabel(): String = BakedTokens.yandexMusicSource(this)

    // ── 1. скан (dry-run) ─────────────────────────────────────────────────────

    private fun onScan() {
        stopScan = false
        log("Скан… (один запрос метаданных на трек, ≤1 rps — может идти долго). Экран не гаснет и телефон не спит, пока идёт скан. «Стоп скан» прервёт после текущего трека.")
        runBg("scan") { runScan() }
    }

    /** Кооперативно остановить идущий скан: следующий трек не сканируется. Прогресс чистых уже в базе. */
    private fun onStopScan() {
        stopScan = true
        log("Останавливаю скан после текущего трека… (прогресс сохранён, повторный скан продолжит с места)")
    }

    private fun runScan(): String {
        val client = client() ?: return NO_TOKEN
        val uid = client.accountUid().toString()
        val liked = client.likedTrackIds(uid)
        if (liked.isEmpty()) return "В лайках пусто — чистить нечего."

        // Кладём ВСЕ лайки в индекс (is_dead=0) — так DeviceBackupGuard снимет восстановимый бэкап именно
        // текущего множества лайков, а SqlCleanupSink сможет писать переходы/audit по существующим строкам.
        val db = ServiceLocator.openDb(this)
        val repo = TrackRepository(db)
        repo.upsertDiscovered(liked.map { DiscoveredTrack(yandexTrackId = it, artistId = null) })

        // Инкрементально (хард-правило 7, вежливость к ЯМ): сканируем ТОЛЬКО новые лайки — те, что ещё не
        // просканированы (last_scan IS NULL). Уже разобранные (чистые с прошлого прогона, обработанные
        // мёртвые/ИИ/серые) метаданными не дёргаем повторно. Итог: второй прогон обходит лишь свежие лайки.
        val alreadyScanned = repo.scannedTrackIds()
        val toScan = liked.filterNot { it in alreadyScanned }
        val skipped = liked.size - toScan.size
        if (toScan.isEmpty()) {
            lastPlan = CleanupPlan(emptyList())
            return "Все ${liked.size} лайков уже просканированы ранее — новых нет.\nЧистить нечего (для повторной чистки состояние в индексе)."
        }

        val classifier = ServiceLocator.cleanupClassifier(this, client)
        val cleanup = ServiceLocator.libraryCleanup(this, db, client, aiPlaylistKind = "-", grayPlaylistKind = "-")
        val plan = cleanup.scan(toScan, classifier, shouldStop = { stopScan }) { i, trackId, bucket ->
            // Чистый лайк сразу помечаем просканированным (last_scan) — повторный прогон его пропустит.
            // Мёртвые/ИИ/серые НЕ помечаем здесь: их отметит SqlCleanupSink на ВЫПОЛНИТЬ (до этого они
            // остаются «новыми», чтобы не потеряться, если чистку не довели до конца).
            if (bucket == CleanupBucket.CLEAN) repo.markCleanupScannedClean(trackId)
            if (i % 25 == 0) log("Скан… $i/${toScan.size} (пропущено ранее: $skipped)")
        }
        lastPlan = plan
        val stopped = stopScan

        return buildString {
            if (stopped) appendLine("⏹ СКАН ОСТАНОВЛЕН — просканировано ${plan.items.size}/${toScan.size} новых. Прогресс чистых сохранён, повторный скан продолжит с места.")
            appendLine("Лайков всего: ${liked.size} (новых к скану: ${toScan.size}, пропущено ранее: $skipped)")
            appendLine("————————————————————")
            appendLine("мёртвые (снять лайк):        ${plan.dead.size}")
            appendLine("ИИ по гейту (дизлайк+плейл): ${plan.aiGate.size}")
            appendLine("серая зона (снять+«непонятно»): ${plan.gray.size}")
            appendLine("чисто (не трогаем):          ${plan.clean.size}")
            appendLine("————————————————————")
            appendLine(if (plan.touchesLikes) "⚠ есть деструктив к лайкам — при ВЫПОЛНИТЬ снимется бэкап." else "Деструктива к лайкам нет.")
            append("Готов. Для действий: создай плейлисты (2), затем ВЫПОЛНИТЬ (3).")
        }.trimEnd()
    }

    // ── 2. создать плейлисты ──────────────────────────────────────────────────

    private fun onCreatePlaylists() {
        log("Создаю плейлисты…")
        runBg("playlists") {
            val client = client() ?: return@runBg NO_TOKEN
            val uid = client.accountUid().toString()
            val aiKind = client.createPlaylist(uid, PLAYLIST_AI)
            val grayKind = client.createPlaylist(uid, PLAYLIST_GRAY)
            prefs().edit().putString(KEY_AI_KIND, aiKind).putString(KEY_GRAY_KIND, grayKind).apply()
            "Плейлисты созданы: детект ИИ=kind $aiKind, непонятно=kind $grayKind.\nМожно ВЫПОЛНИТЬ чистку (3)."
        }
    }

    // ── 3. выполнить чистку (деструктив) ──────────────────────────────────────

    private fun onExecute() {
        val plan = lastPlan
        if (plan == null) { log("Сначала «Скан (dry-run)» — нужен план."); return }
        val prefs = prefs()
        val aiKind = prefs.getString(KEY_AI_KIND, null)
        val grayKind = prefs.getString(KEY_GRAY_KIND, null)
        if (aiKind == null || grayKind == null) { log("Сначала «Создать плейлисты» (2)."); return }

        log("ВЫПОЛНЕНИЕ чистки (снимаю бэкап, затем действия)…")
        runBg("execute") {
            val client = client() ?: return@runBg NO_TOKEN
            val db = ServiceLocator.openDb(this)
            val cleanup = ServiceLocator.libraryCleanup(this, db, client, aiKind, grayKind)
            val res = cleanup.execute(plan, confirm = true)
            if (!res.executed) {
                "ОТКАЗ: ${res.refusedReason} (нет подтверждения/бэкапа — хард-правило 5)."
            } else {
                buildString {
                    appendLine("ЧИСТКА ВЫПОЛНЕНА ✓")
                    appendLine("бэкап лайков:   ${res.backupId ?: "не требовался (лайки не трогали)"}")
                    appendLine("————————————————————")
                    appendLine("снято мёртвых:  ${res.deadUnliked}")
                    appendLine("ИИ дизлайк+пл.: ${res.aiMoved}")
                    appendLine("серых в «непонятно»: ${res.grayMoved}")
                    appendLine("no-op (уже так): ${res.noop}")
                    append("Разбери плейлист «непонятно» руками, потом удали плейлисты кнопками ниже.")
                }.trimEnd()
            }
        }
    }

    // ── откат F7: вернуть лайки из последнего бэкапа ──────────────────────────

    private fun onRestore(execute: Boolean) {
        log(if (execute) "ОТКАТ: возвращаю лайки из бэкапа…" else "ОТКАТ dry-run: читаю бэкап + текущие лайки…")
        runBg("restore") { runRestore(execute) }
    }

    /**
     * Откат чистки (§F7, хард-правило 5): целевое состояние — последний снятый бэкап лайков; текущие лайки
     * берём живьём из акка. [RestorePlanner] строит план (restoreExactly=false → только доливаем недостающие
     * лайки, лишние НЕ снимаем). При [execute]=false — [DryRunExecutor] (счётчики, ноль мутаций акка); при
     * true — [ServiceLocator.libraryRestore] реально возвращает лайки (undislike+like) через лимитер ЯМ.
     */
    private fun runRestore(execute: Boolean): String {
        val client = client() ?: return NO_TOKEN
        val db = ServiceLocator.openDb(this)
        val manifest = ServiceLocator.latestBackupManifest(this, db)
            ?: return "Бэкапов лайков нет (filesDir/backups пуст). Бэкап снимается на скане/ВЫПОЛНИТЬ — сначала прогони чистку."
        val uid = client.accountUid().toString()
        val currentLikes = client.likedTrackIds(uid)
        val plan = RestorePlanner.plan(currentLikes, manifest, restoreExactly = false)

        if (!execute) {
            val dry = DryRunExecutor(currentLikes).execute(plan)
            return buildString {
                appendLine("ОТКАТ (dry-run) — план восстановления лайков. Акк: ${accountLabel()}")
                appendLine("бэкап: createdAt=${manifest.createdAt}, лайков в бэкапе=${manifest.likes.size}")
                appendLine("сейчас лайков в акке: ${currentLikes.size}")
                appendLine("————————————————————")
                appendLine("вернуть лайк (re-add): ${dry.reAddedCount}")
                appendLine("уже на месте (no-op):  ${dry.noopCount}")
                appendLine("(restoreExactly=false → лишние лайки НЕ снимаем)")
                append("Готов. Реальный возврат — «ОТКАТ: вернуть лайки (execute)».")
            }.trimEnd()
        }

        val res = ServiceLocator.libraryRestore(client).execute(plan)
        return buildString {
            appendLine("ОТКАТ ВЫПОЛНЕН ✓ (лайки возвращены из бэкапа). Акк: ${accountLabel()}")
            appendLine("————————————————————")
            appendLine("вернули лайк:    ${res.reAddedCount}")
            appendLine("сняли лайк:      ${res.removedCount}")
            appendLine("no-op (уже так): ${res.noopCount}")
        }.trimEnd()
    }

    // ── удаление плейлистов (после ревью) ─────────────────────────────────────

    private fun onDeletePlaylist(ai: Boolean) {
        val key = if (ai) KEY_AI_KIND else KEY_GRAY_KIND
        val label = if (ai) "детект ИИ" else "непонятно"
        val kind = prefs().getString(key, null)
        if (kind == null) { log("Плейлист «$label» не создан/уже удалён."); return }
        log("Удаляю плейлист «$label» (kind $kind)…")
        runBg("delete") {
            val client = client() ?: return@runBg NO_TOKEN
            val uid = client.accountUid().toString()
            val ok = client.deletePlaylist(uid, kind)
            if (ok) prefs().edit().remove(key).apply()
            "Плейлист «$label» удалён=$ok."
        }
    }

    /** Клиент ЯМ из сохранённого токена, иначе запечённый тестовый (этап A — тест-акк). */
    private fun client(): YandexClient? {
        val token = ServiceLocator.tokenStore(this).load() ?: BakedTokens.yandexMusic(this) ?: return null
        return ServiceLocator.yandexClient(token)
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun log(text: String) = runOnUiThread { out.text = text }

    /**
     * Запустить длинную сетевую операцию в фоне, удерживая устройство бодрым, пока она идёт:
     *   - PARTIAL_WAKE_LOCK — CPU не уходит в suspend при засыпании телефона (иначе поток встаёт на
     *     середине скана/чистки; WorkManager-прогон по расписанию имеет свой wake-lock, ручной — нет);
     *   - FLAG_KEEP_SCREEN_ON — экран не гаснет, пока панель на переднем плане.
     * Лок снимается в finally по завершении последней операции (счётчик [activeOps]).
     * Оговорка: wake-lock спасает от засыпания, но НЕ от убийства процесса при уходе из приложения
     * (для полной живучести в фоне нужен foreground-сервис — отдельный шаг).
     */
    private fun runBg(tag: String, work: () -> String) {
        acquireAwake(tag)
        Thread {
            val report = runCatching { work() }.getOrElse { "ОШИБКА: ${it.message}" }
            log(report)
            releaseAwake()
        }.start()
    }

    private fun acquireAwake(tag: String) {
        synchronized(opLock) {
            if (activeOps == 0) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "humanonly:$tag").apply {
                    setReferenceCounted(false)
                    acquire(60 * 60 * 1000L) // предохранитель: не держать лок дольше 60 мин
                }
                runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            activeOps++
        }
    }

    private fun releaseAwake() {
        synchronized(opLock) {
            activeOps--
            if (activeOps <= 0) {
                activeOps = 0
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    override fun onDestroy() {
        // Не оставляем лок висеть, если экран уничтожили посреди операции.
        synchronized(opLock) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            activeOps = 0
        }
        super.onDestroy()
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text; textSize = 20f; setPadding(0, 0, 0, dp(4))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setPadding(0, dp(8), 0, dp(4))
    }

    /** Заголовок секции (жирный) — визуально разбивает список кнопок на группы. */
    private fun sectionHeader(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    /**
     * Кнопка. [indent] — под-шаг (отступ слева, чтобы читалась вложенность в главное действие секции).
     * [danger] — деструктив (красноватый фон): ВЫПОЛНИТЬ чистку / возврат лайков.
     */
    private fun button(label: String, indent: Boolean = false, danger: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (indent) marginStart = dp(24)
        }
        if (danger) backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFC62828.toInt())
        setOnClickListener { onClick() }
    }

    /** versionName из манифеста — видимая метка сборки, чтобы отличать свежий APK от старого. */
    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val NO_TOKEN = "Нет токена ЯМ. Положи токен (MainActivity) или запечённый ассет."
        const val PREFS = "cleanup"
        const val KEY_AI_KIND = "ai_playlist_kind"
        const val KEY_GRAY_KIND = "gray_playlist_kind"
        const val PLAYLIST_AI = "детект ИИ"
        const val PLAYLIST_GRAY = "непонятно — ИИ или человек"
    }
}
