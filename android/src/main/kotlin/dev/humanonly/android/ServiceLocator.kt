package dev.humanonly.android

import android.content.Context
import dev.humanonly.config.FeatureFlags
import dev.humanonly.db.SqlScanSource
import dev.humanonly.db.SqlVerdictSink
import dev.humanonly.db.TrackRepository
import dev.humanonly.detector.DetectionCascade
import dev.humanonly.detector.MetadataScorer
import dev.humanonly.detector.SloplessGate
import dev.humanonly.pipeline.ScanConveyor
import dev.humanonly.schedule.BackoffKind
import dev.humanonly.schedule.BackoffPolicy
import dev.humanonly.schedule.CurationRun
import dev.humanonly.schedule.RunConstraints
import dev.humanonly.schedule.RunSchedule
import dev.humanonly.schedule.RunScheduler

/**
 * Минимальная ручная сборка зависимостей прогона (без DI-фреймворка). Профиль **MVP**: детектор =
 * hard gate (slopless) + метаданные; авто-дизлайк/архив/скачивание — отдельные чанки (флаги в
 * [FeatureFlags] и `null`-стадии в [CurationRun] держат их выключенными). Полноценный DI (YandexClient,
 * ActionDispatcher, Archiver) — TODO, см. docs/android-adapter-reference.md.
 */
object ServiceLocator {

    private const val DETECTOR_VERSION = "metadata-mvp-1"

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

        val run = CurationRun(
            flags = FeatureFlags.MVP,
            scanSource = SqlScanSource(db),
            conveyor = conveyor,
            schedule = schedule,
        )
        return RunScheduler(run, schedule)
    }

    /**
     * База AI-артистов slopless грузится в рантайме (GPL — не вендорим в APK, CLAUDE.md §10). Если файла
     * `assets/slopless.json` нет — пустой гейт (детект по одним метаданным, каскад 0 всегда мимо).
     */
    private fun loadGate(ctx: Context): SloplessGate =
        runCatching {
            ctx.applicationContext.assets.open("slopless.json").bufferedReader().use { it.readText() }
        }.map(SloplessGate::fromJson)
            .getOrElse { SloplessGate.fromJson("""{"timestamp":"absent","artists":[]}""") }
}
