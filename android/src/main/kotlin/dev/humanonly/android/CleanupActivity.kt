package dev.humanonly.android

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
        root.addView(title("Чистка библиотеки (мёртвые + ИИ)"))
        root.addView(hint("Скан → счётчики (акк не трогаем; чистые помечаем просканированными локально — 2-й прогон их пропустит). Потом плейлисты. Потом ВЫПОЛНИТЬ (деструктив, с бэкапом)."))

        root.addView(button("1. Скан библиотеки (dry-run)") { onScan() })
        root.addView(button("2. Создать плейлисты «детект ИИ» + «непонятно»") { onCreatePlaylists() })
        root.addView(button("3. ВЫПОЛНИТЬ чистку (деструктив)") { onExecute() })
        root.addView(hint("— уборка после ревью —"))
        root.addView(button("Удалить плейлист «детект ИИ»") { onDeletePlaylist(ai = true) })
        root.addView(button("Удалить плейлист «непонятно»") { onDeletePlaylist(ai = false) })

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
        log("Плейлисты: детект ИИ=${ai ?: "—"} непонятно=${gray ?: "—"}\nЖми «Скан» для dry-run.")
    }

    // ── 1. скан (dry-run) ─────────────────────────────────────────────────────

    private fun onScan() {
        log("Скан… (один запрос метаданных на трек, ≤1 rps — может идти долго)")
        Thread {
            val report = runCatching { runScan() }.getOrElse { "ОШИБКА: ${it.message}" }
            log(report)
        }.start()
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
        val plan = cleanup.scan(toScan, classifier) { i, trackId, bucket ->
            // Чистый лайк сразу помечаем просканированным (last_scan) — повторный прогон его пропустит.
            // Мёртвые/ИИ/серые НЕ помечаем здесь: их отметит SqlCleanupSink на ВЫПОЛНИТЬ (до этого они
            // остаются «новыми», чтобы не потеряться, если чистку не довели до конца).
            if (bucket == CleanupBucket.CLEAN) repo.markCleanupScannedClean(trackId)
            if (i % 25 == 0) log("Скан… $i/${toScan.size} (пропущено ранее: $skipped)")
        }
        lastPlan = plan

        return buildString {
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
        Thread {
            val report = runCatching {
                val client = client() ?: return@runCatching NO_TOKEN
                val uid = client.accountUid().toString()
                val aiKind = client.createPlaylist(uid, PLAYLIST_AI)
                val grayKind = client.createPlaylist(uid, PLAYLIST_GRAY)
                prefs().edit().putString(KEY_AI_KIND, aiKind).putString(KEY_GRAY_KIND, grayKind).apply()
                "Плейлисты созданы: детект ИИ=kind $aiKind, непонятно=kind $grayKind.\nМожно ВЫПОЛНИТЬ чистку (3)."
            }.getOrElse { "ОШИБКА создания плейлистов: ${it.message}" }
            log(report)
        }.start()
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
        Thread {
            val report = runCatching {
                val client = client() ?: return@runCatching NO_TOKEN
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
            }.getOrElse { "ОШИБКА: ${it.message}" }
            log(report)
        }.start()
    }

    // ── удаление плейлистов (после ревью) ─────────────────────────────────────

    private fun onDeletePlaylist(ai: Boolean) {
        val key = if (ai) KEY_AI_KIND else KEY_GRAY_KIND
        val label = if (ai) "детект ИИ" else "непонятно"
        val kind = prefs().getString(key, null)
        if (kind == null) { log("Плейлист «$label» не создан/уже удалён."); return }
        log("Удаляю плейлист «$label» (kind $kind)…")
        Thread {
            val report = runCatching {
                val client = client() ?: return@runCatching NO_TOKEN
                val uid = client.accountUid().toString()
                val ok = client.deletePlaylist(uid, kind)
                if (ok) prefs().edit().remove(key).apply()
                "Плейлист «$label» удалён=$ok."
            }.getOrElse { "ОШИБКА удаления: ${it.message}" }
            log(report)
        }.start()
    }

    /** Клиент ЯМ из сохранённого токена, иначе запечённый тестовый (этап A — тест-акк). */
    private fun client(): YandexClient? {
        val token = ServiceLocator.tokenStore(this).load() ?: BakedTokens.yandexMusic(this) ?: return null
        return ServiceLocator.yandexClient(token)
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun log(text: String) = runOnUiThread { out.text = text }

    private fun title(text: String) = TextView(this).apply {
        this.text = text; textSize = 20f; setPadding(0, 0, 0, dp(4))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setPadding(0, dp(8), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

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
