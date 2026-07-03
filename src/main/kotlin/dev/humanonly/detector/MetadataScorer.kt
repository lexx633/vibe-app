package dev.humanonly.detector

/**
 * Каскад 1 (ТЗ §10, ADR-0005): эвристики по метаданным ЯМ → `meta_score` ∈ [0,1].
 *
 * Сигналы из ТЗ: «каденция релизов, лейбл, шаблонные имена». Реализованы как прозрачный
 * конфигурируемый линейный набор правил (веса — НЕ хардкод, ТЗ §10): сработавшие сигналы
 * суммируются, результат зажимается в [0,1]. Внешние кросс-чеки метаданных — v1.1 (не здесь).
 *
 * Скорер чистый и детерминированный: НЕ ходит в сеть и НЕ парсит DTO — на вход подаются уже
 * извлечённые признаки [TrackMetaFeatures]. Это держит детектор офлайн-тестируемым (как LinearDetector).
 *
 * PII (§12): признаки булевы/числовые, имена/лейблы в скорер НЕ передаются и не логируются.
 */
class MetadataScorer(private val rules: MetadataRules = MetadataRules()) {

    /** Версия правил → пишется как metadata_rules_version (§13). */
    val rulesVersion: String get() = rules.rulesVersion

    /** meta_score ∈ [0,1]: сумма весов сработавших сигналов, зажатая в [0,1]. */
    fun score(features: TrackMetaFeatures): Double {
        var s = 0.0
        if (features.templateNameHit) s += rules.templateNameWeight
        if (features.suspiciousLabel) s += rules.suspiciousLabelWeight
        val releases = features.releasesInWindow
        if (releases != null && releases >= rules.releaseFloodThreshold) s += rules.releaseFloodWeight
        return s.coerceIn(0.0, 1.0)
    }
}

/**
 * Извлечённые признаки метаданных трека (вход каскада 1). Булевы/числовые — без PII.
 * Извлечение из DTO ЯМ — ответственность вызывающего слоя (не детектора).
 */
data class TrackMetaFeatures(
    /** Имя артиста/трека совпало с шаблоном подозрительного AI-нейминга. */
    val templateNameHit: Boolean = false,
    /** Лейбл входит в список подозрительных (AI-фермы релизов). */
    val suspiciousLabel: Boolean = false,
    /** Каденция: сколько релизов артиста за окно наблюдения; null — неизвестно (сигнал не считается). */
    val releasesInWindow: Int? = null,
)

/**
 * Конфигурируемые веса/пороги правил метаданных (ТЗ §10 — «не хардкод»).
 * Веса подобраны так, чтобы одиночный слабый сигнал не давал уверенного вердикта (см. каскад):
 * без аудио серая зона всегда уходит в review_required.
 */
data class MetadataRules(
    val templateNameWeight: Double = 0.5,
    val suspiciousLabelWeight: Double = 0.6,
    val releaseFloodWeight: Double = 0.4,
    /** Порог «флуда релизов»: при таком числе релизов за окно сигнал срабатывает. */
    val releaseFloodThreshold: Int = 30,
    val rulesVersion: String = "meta-v1",
) {
    init {
        require(templateNameWeight >= 0 && suspiciousLabelWeight >= 0 && releaseFloodWeight >= 0) {
            "веса правил не могут быть отрицательными"
        }
        require(releaseFloodThreshold >= 1) { "releaseFloodThreshold должен быть ≥1" }
    }
}
