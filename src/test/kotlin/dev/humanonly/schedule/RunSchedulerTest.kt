package dev.humanonly.schedule

import dev.humanonly.config.FeatureFlags
import dev.humanonly.detector.DetectionCascade
import dev.humanonly.detector.MetadataScorer
import dev.humanonly.detector.SloplessGate
import dev.humanonly.pipeline.ScanConveyor
import dev.humanonly.pipeline.VerdictSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * «Тело воркера» [RunScheduler]: маппинг [RunOutcome] → [NextAction] (зеркало WorkManager Result +
 * перепланирование). Прогоняет реальный [CurationRun] на JVM-fakes — без androidx.work.
 */
class RunSchedulerTest {

    private val hour = 60 * 60 * 1000L
    private fun conveyor() = ScanConveyor(
        DetectionCascade(SloplessGate.fromJson("""{"timestamp":"t","artists":[1]}"""), MetadataScorer()),
        VerdictSink { _, _, _, _ -> },
    )

    private val schedule = RunSchedule(intervalMs = 6 * hour, backoff = BackoffPolicy(BackoffKind.EXPONENTIAL, initialDelayMs = 30_000, maxDelayMs = 5 * hour))

    private fun scheduler(run: CurationRun) = RunScheduler(run, schedule)

    @Test
    fun `SUCCESS → Reschedule на следующий период`() {
        val run = CurationRun(FeatureFlags(), scanSource = { emptyList() }, conveyor = conveyor(), schedule = schedule)
        val res = scheduler(run).execute(DeviceState(metered = false), attempt = 1, nowMs = 1000L)

        assertEquals(RunOutcome.SUCCESS, res.report.outcome)
        assertEquals(NextAction.Reschedule(1000L + 6 * hour), res.next)
    }

    @Test
    fun `RETRY (transient) → Retry с backoff по номеру попытки`() {
        val run = CurationRun(FeatureFlags(), scanSource = { throw TransientException("503") }, conveyor = conveyor(), schedule = schedule)
        val res = scheduler(run).execute(attempt = 3, nowMs = 0L)

        assertEquals(RunOutcome.RETRY, res.report.outcome)
        // exponential: 30_000 · 2^(3-1) = 120_000
        assertEquals(NextAction.Retry(120_000), res.next)
    }

    @Test
    fun `ограничения не выполнены → RETRY → Retry (без побочек)`() {
        val run = CurationRun(FeatureFlags(), scanSource = { error("не должно вызваться") }, conveyor = conveyor(), schedule = schedule)
        val res = scheduler(run).execute(DeviceState(metered = true), attempt = 1)

        assertEquals(RunOutcome.RETRY, res.report.outcome)
        assertEquals(CurationRun.REASON_CONSTRAINTS_NOT_MET, res.report.skippedReason)
        assertTrue(res.next is NextAction.Retry)
    }

    @Test
    fun `FAILURE → Drop`() {
        val run = CurationRun(FeatureFlags(), scanSource = { throw IllegalStateException("битый конфиг") }, conveyor = conveyor(), schedule = schedule)
        val res = scheduler(run).execute()

        assertEquals(RunOutcome.FAILURE, res.report.outcome)
        assertEquals(NextAction.Drop, res.next)
    }

    @Test
    fun `attempt меньше 1 подтягивается к 1 (без исключения backoff)`() {
        val run = CurationRun(FeatureFlags(), scanSource = { throw TransientException("сеть") }, conveyor = conveyor(), schedule = schedule)
        val res = scheduler(run).execute(attempt = 0)
        assertEquals(NextAction.Retry(30_000), res.next) // как attempt=1
    }
}
