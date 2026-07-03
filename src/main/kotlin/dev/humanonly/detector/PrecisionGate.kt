package dev.humanonly.detector

/**
 * Precision-gate режима «удалять» (ТЗ §10, §16). Необратимое удаление из лайков (режим F4-c) НЕ
 * разрешается, пока детектор не доказал точность на живой разметке:
 *   - размечено человеком **≥ [minSamples]** suspected-треков (дефолт 200);
 *   - precision = TP / (TP + FP) **≥ [minPrecision]** (дефолт 0.90).
 *
 * TP — suspected, подтверждённые человеком как ИИ («ИИ»); FP — suspected, обелённые («не ИИ»).
 * Пока гейт закрыт — режим (c) недоступен (падаем на (b) перенос в плейлист). Гейт чистый и
 * детерминированный: (счётчики) → решение; никаких сайд-эффектов. Пороги configurable (не хардкод, §10).
 */
class PrecisionGate(
    val minSamples: Int = 200,
    val minPrecision: Double = 0.90,
) {
    init {
        require(minSamples >= 1) { "minSamples должен быть ≥ 1" }
        require(minPrecision in 0.0..1.0) { "minPrecision в [0,1]" }
    }

    /** Оценка гейта по накопленной разметке. */
    fun evaluate(stats: PrecisionStats): GateDecision {
        if (stats.labeled < minSamples) {
            return GateDecision(
                allowed = false, labeled = stats.labeled, precision = stats.precision,
                reason = REASON_INSUFFICIENT_SAMPLES,
            )
        }
        val p = stats.precision ?: 0.0
        if (p < minPrecision) {
            return GateDecision(
                allowed = false, labeled = stats.labeled, precision = stats.precision,
                reason = REASON_PRECISION_BELOW,
            )
        }
        return GateDecision(allowed = true, labeled = stats.labeled, precision = stats.precision, reason = REASON_OK)
    }

    /** Короткий предикат: можно ли включать необратимое удаление (режим c). */
    fun isRemovalAllowed(stats: PrecisionStats): Boolean = evaluate(stats).allowed

    companion object {
        const val REASON_INSUFFICIENT_SAMPLES = "insufficient_samples"
        const val REASON_PRECISION_BELOW = "precision_below_threshold"
        const val REASON_OK = "ok"
    }
}

/**
 * Накопленная разметка suspected: [truePositives] — подтверждены как ИИ, [falsePositives] — обелены.
 * precision = TP/(TP+FP); при пустой разметке — null (не 0, чтобы отличать «нет данных»).
 */
data class PrecisionStats(
    val truePositives: Int,
    val falsePositives: Int,
) {
    init {
        require(truePositives >= 0 && falsePositives >= 0) { "счётчики не могут быть отрицательными" }
    }

    /** Размечено suspected всего = TP + FP. */
    val labeled: Int get() = truePositives + falsePositives

    /** Precision ∈ [0,1] или null, если нет разметки. */
    val precision: Double? get() = if (labeled == 0) null else truePositives.toDouble() / labeled
}

/** Решение гейта: allowed + наблюдаемость (размечено/precision/код причины). */
data class GateDecision(
    val allowed: Boolean,
    val labeled: Int,
    val precision: Double?,
    val reason: String,
)
