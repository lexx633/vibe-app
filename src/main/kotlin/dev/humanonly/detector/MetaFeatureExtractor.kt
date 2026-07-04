package dev.humanonly.detector

/**
 * Извлечение признаков метаданных (каскад 1, ТЗ §10) из «сырых» строковых полей трека ЯМ в
 * булев/числовой [TrackMetaFeatures]. Чистая, детерминированная, офлайн-тестируемая функция — без сети.
 *
 * PII (§12): на вход подаются имена/лейблы (title / имя артиста / имя лейбла), на выходе — ТОЛЬКО
 * booleans. Исходные строки здесь не хранятся и не логируются; ответственность вызывающего слоя —
 * не персистить их (см. [dev.humanonly.db.MetaResolver]).
 *
 * Сигналы MVP считаются из ОДНОГО ответа `tracks/{id}` (без доп-запросов):
 *   - `templateNameHit` — title ИЛИ имя артиста матчит шаблон AI/шаблонного нейминга ([config.namePatterns]);
 *   - `suspiciousLabel` — имя лейбла релиза входит в список подозрительных ([config.suspiciousLabels], без регистра).
 *
 * `releasesInWindow` (каденция релизов) требует отдельного artist-эндпоинта → в MVP не считается
 * (остаётся null, сигнал в скорере не учитывается). Это осознанный отложенный сигнал, не заглушка навсегда.
 */
class MetaFeatureExtractor(private val config: MetaSignalConfig = MetaSignalConfig()) {

    /**
     * @param title        название трека (nullable — API может не отдать).
     * @param artistNames  имена артистов трека (пустой список — если не распарсились).
     * @param labelNames   имена лейблов релиза (пустой список — если нет).
     */
    fun extract(
        title: String?,
        artistNames: List<String> = emptyList(),
        labelNames: List<String> = emptyList(),
    ): TrackMetaFeatures {
        val names = buildList {
            title?.let(::add)
            addAll(artistNames)
        }
        val templateHit = names.any { name -> config.namePatterns.any { it.containsMatchIn(name) } }
        val labelHit = labelNames.any { label ->
            val l = label.trim()
            config.suspiciousLabels.any { it.equals(l, ignoreCase = true) }
        }
        return TrackMetaFeatures(
            templateNameHit = templateHit,
            suspiciousLabel = labelHit,
            releasesInWindow = null,
        )
    }
}

/**
 * Конфигурация сигналов каскада 1 (веса — в [MetadataRules], здесь — ЧТО считать сигналом).
 * Значения по умолчанию — КОНСЕРВАТИВНАЯ стартовая эвристика (tunable, не авторитетный список):
 * маркеры генеративных инструментов и шаблонного «фермерского» контента. Правится по мере наблюдений
 * (расширение — отдельный чанк с данными, не по памяти).
 */
data class MetaSignalConfig(
    val namePatterns: List<Regex> = DEFAULT_NAME_PATTERNS,
    val suspiciousLabels: Set<String> = DEFAULT_SUSPICIOUS_LABELS,
) {
    companion object {
        /**
         * Маркеры в названии/имени артиста. Регистр игнорируется. Держим набор МАЛЕНЬКИМ и специфичным,
         * чтобы не ловить живых артистов (ложные срабатывания дороже пропусков — вердикт всё равно уходит
         * в review_required, решает человек).
         */
        val DEFAULT_NAME_PATTERNS: List<Regex> = listOf(
            """(?i)\bsuno\b""".toRegex(),
            """(?i)\budio\b""".toRegex(),
            """(?i)\bai[\s-]?generated\b""".toRegex(),
            """(?i)\bgenerated\s+by\s+ai\b""".toRegex(),
            """(?i)\bai\s+cover\b""".toRegex(),
            """(?i)\bprod\.?\s*(?:by\s+)?ai\b""".toRegex(),
            """(?i)\btype\s*beat\b""".toRegex(),
            """(?i)\btext[\s-]?to[\s-]?music\b""".toRegex(),
        )

        /**
         * Подозрительные лейблы (AI-фермы релизов). По умолчанию ПУСТ: авторитетного списка пока нет,
         * заполняется отдельной поставкой (как база slopless). Пустой набор → сигнал не срабатывает.
         */
        val DEFAULT_SUSPICIOUS_LABELS: Set<String> = emptySet()
    }
}
