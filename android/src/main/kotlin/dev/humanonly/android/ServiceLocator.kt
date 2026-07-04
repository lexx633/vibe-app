package dev.humanonly.android

import android.content.Context
import dev.humanonly.config.FeatureFlags
import dev.humanonly.db.ArtistEnricher
import dev.humanonly.db.LiveScanSource
import dev.humanonly.db.SqlActionQueue
import dev.humanonly.db.SqlActionSink
import dev.humanonly.db.SqlBackupSource
import dev.humanonly.db.SqlReviewSink
import dev.humanonly.db.SqlReviewSource
import dev.humanonly.db.SqlScanSource
import dev.humanonly.db.SqlVerdictSink
import dev.humanonly.db.TrackRepository
import dev.humanonly.review.ReviewQueue
import dev.humanonly.db.YandexLibraryReader
import dev.humanonly.db.YandexMetaLookup
import dev.humanonly.detector.DetectionCascade
import dev.humanonly.detector.MetadataScorer
import dev.humanonly.detector.SloplessGate
import dev.humanonly.pipeline.ActionDispatcher
import dev.humanonly.pipeline.ActionMode
import dev.humanonly.pipeline.ScanConveyor
import dev.humanonly.yandex.YandexLibraryActions
import dev.humanonly.schedule.BackoffKind
import dev.humanonly.schedule.BackoffPolicy
import dev.humanonly.schedule.CurationRun
import dev.humanonly.schedule.RunConstraints
import dev.humanonly.schedule.RunSchedule
import dev.humanonly.schedule.RunScheduler
import dev.humanonly.schedule.ScanSource
import dev.humanonly.yandex.RateLimiter
import dev.humanonly.yandex.YandexClient
import dev.humanonly.yandex.YandexConfig

/**
 * Минимальная ручная сборка зависимостей прогона (без DI-фреймворка). Профиль **MVP**: детектор =
 * hard gate (slopless) + метаданные; авто-дизлайк/архив/скачивание — отдельные чанки (флаги в
 * [FeatureFlags] и `null`-стадии в [CurationRun] держат их выключенными). Полноценный DI (YandexClient,
 * ActionDispatcher, Archiver) — TODO, см. docs/android-adapter-reference.md.
 */
object ServiceLocator {

    private const val DETECTOR_VERSION = "metadata-mvp-1"

    /**
     * Флаги прогона. `autoDislike=false` в MVP (хард-правило 5): деструктив выключен по умолчанию.
     * Включение — осознанное изменение здесь + ДА владельца; проводка ниже ([buildDispatcher]) уже готова.
     */
    private val flags: FeatureFlags = FeatureFlags.MVP

    /** Расписание MVP: раз в 6 ч, только по не-лимитной сети, экспоненциальный backoff. */
    val schedule: RunSchedule = RunSchedule(
        intervalMs = 6 * 60 * 60 * 1000L,
        flexMs = 30 * 60 * 1000L,
        constraints = RunConstraints(requiresUnmeteredNetwork = true, requiresBatteryNotLow = true),
        backoff = BackoffPolicy(BackoffKind.EXPONENTIAL, initialDelayMs = 30_000, maxDelayMs = 5 * 60 * 60 * 1000L),
    )

    @Volatile
    private var cached: RunScheduler? = null

    fun scheduler(ctx: Context): RunScheduler =
        cached ?: synchronized(this) { cached ?: build(ctx).also { cached = it } }

    private fun build(ctx: Context): RunScheduler {
        val db = AndroidDb(CurationOpenHelper(ctx).writableDatabase)
        val repo = TrackRepository(db)

        val gate = loadGate(ctx)
        val cascade = DetectionCascade(gate, MetadataScorer())
        val conveyor = ScanConveyor(cascade, SqlVerdictSink(repo, DETECTOR_VERSION))

        // scan_delta из индекса; поверх — живой источник лайков ЯМ, если сохранён токен (иначе только
        // индекс, офлайн). Live-стадия сама идёт через rate-limiter клиента (хард-правило 7); реальный
        // прогон против ЯМ включается лишь наличием токена (хард-правило 3 — токен кладётся с ДА).
        val indexDelta = SqlScanSource(db)
        val scanSource: ScanSource = liveClient(ctx)?.let { client ->
            // Обогащаем artist_id новых треков из метаданных ЯМ → slopless-гейт каскада 0 работает.
            val enricher = ArtistEnricher(repo, YandexMetaLookup(client))
            LiveScanSource(YandexLibraryReader(client), repo, indexDelta, enricher)
        } ?: indexDelta

        val run = CurationRun(
            flags = flags,
            scanSource = scanSource,
            conveyor = conveyor,
            schedule = schedule,
            actionQueue = SqlActionQueue(db),        // треки verdict=ai_confirmed, ждущие действия
            dispatcher = buildDispatcher(ctx, db, repo),
        )
        return RunScheduler(run, schedule)
    }

    /**
     * Диспетчер авто-действий (§F4). Строится ТОЛЬКО когда [FeatureFlags.autoDislike] включён осознанно
     * И есть токен ЯМ (хард-правило 3 — без токена live-операций нет). Режим MVP — [ActionMode.DISLIKE_ONLY]
     * (перенос в плейлист отложен, см. [YandexLibraryActions]). Хард-правило 5 обеспечивает [DeviceBackupGuard]:
     * без свежего восстановимого бэкапа лайков `ActionDispatcher.execute` откажется дизлайкать. Rate-limit
     * к ЯМ сохраняется в клиенте (хард-правило 7). Пока флаг off — возвращаем null (стадия действий не идёт).
     */
    private fun buildDispatcher(ctx: Context, db: AndroidDb, repo: TrackRepository): ActionDispatcher? {
        if (!flags.autoDislike) return null
        val client = liveClient(ctx) ?: return null
        return ActionDispatcher(
            mode = ActionMode.DISLIKE_ONLY,
            library = YandexLibraryActions.create(client),
            sink = SqlActionSink(repo),
            backup = DeviceBackupGuard(ctx, SqlBackupSource(db)),
        )
    }

    /**
     * Живой клиент ЯМ на платформенном [AndroidHttpTransport] (без новых зависимостей — `java.net.http`
     * на Android нет). Rate-limiter реальный: базово 1 rps, реальные `nanoTime`/`Thread.sleep` (хард-правило 7 —
     * не отключается). Токен инъектируется вызывающим (источник — [KeystoreTokenStore]).
     *
     * В MVP-прогоне используется как источник scan_delta: [liveClient] → [YandexLibraryReader] →
     * [LiveScanSource] (лайки → индекс). Стадии скачивания/действий/restore — отдельные чанки с ДА.
     */
    /**
     * Ревью-очередь (§F4-UI) поверх того же индекса SQLite. Источник — треки `verdict='review_required'`
     * (серая зона детекции без аудио); решение человека («ИИ»/«не ИИ») переводит verdict в
     * ai_confirmed/human_confirmed + audit (§12). Именно так штатно рождается `ai_confirmed`, который потом
     * забирает [SqlActionQueue] под авто-дизлайк. Валидация переходов — в [ReviewQueue] (хард-правило 10).
     */
    fun reviewQueue(ctx: Context): ReviewQueue {
        val db = AndroidDb(CurationOpenHelper(ctx).writableDatabase)
        return ReviewQueue(SqlReviewSource(db), SqlReviewSink(TrackRepository(db)))
    }

    /** Хранилище токена ЯМ на аппаратном Keystore (хард-правило 3/4). */
    fun tokenStore(ctx: Context): TokenStore = KeystoreTokenStore(ctx)

    /**
     * Живой клиент ЯМ из сохранённого токена, либо null если токена нет (тогда live-стадии пропускаются).
     * Токен в лог не пишем — при диагностике только `tokenStore(ctx).fingerprint()`.
     */
    fun liveClient(ctx: Context): YandexClient? =
        tokenStore(ctx).load()?.let { yandexClient(it) }

    fun yandexClient(token: String, baseUrl: String = YandexConfig.DEFAULT_BASE_URL): YandexClient {
        val rateLimiter = RateLimiter(
            nowNanos = System::nanoTime,
            sleeper = { waitNanos ->
                if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
            },
        )
        return YandexClient(YandexConfig(token, baseUrl = baseUrl), AndroidHttpTransport(), rateLimiter)
    }

    /**
     * База AI-артистов slopless грузится в РАНТАЙМЕ (GPL — не вендорим в APK, CLAUDE.md §10). Приоритет:
     *   1. `filesDir/slopless.json` — снапшот, скачанный/подложенный на устройстве (НЕ в репо/APK);
     *   2. `assets/slopless.json` — опциональный dev-снапшот (в публичный репо не коммитим);
     *   3. пустой гейт — детект по одним метаданным, каскад 0 всегда мимо.
     * Версия базы (timestamp снапшота) уходит в [SloplessGate.version] → slopless_db_version (§13).
     */
    private fun loadGate(ctx: Context): SloplessGate {
        val app = ctx.applicationContext
        val runtime = java.io.File(app.filesDir, SLOPLESS_FILE)
        val json = if (runtime.isFile) {
            runCatching { runtime.readText() }.getOrNull()
        } else {
            runCatching { app.assets.open(SLOPLESS_FILE).bufferedReader().use { it.readText() } }.getOrNull()
        }
        return json?.let(SloplessGate::fromJson)
            ?: SloplessGate.fromJson("""{"timestamp":"absent","artists":[]}""")
    }

    private const val SLOPLESS_FILE = "slopless.json"
}
