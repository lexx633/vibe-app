package dev.humanonly.config

/**
 * Feature flags (ТЗ §4.1). Единая точка включения слоёв — оркестратор ([dev.humanonly.schedule.CurationRun])
 * и каскад читают только отсюда, без хардкода. Дефолты — профиль **MVP**:
 *  - `detectorMetadata=true`, `detectorAudio=false` — аудио-слой отложен (лицензия/патенты deezer, §10);
 *  - `autoDislike=false` — деструктив по умолчанию выключен (хард-правило 5: включается осознанно + бэкап);
 *  - `archive=true`, `review=true` — архив и human-in-the-loop активны в MVP;
 *  - `restore=true` — восстановление доступно;
 *  - `partialDownload=false`, `cloudDetector=false`, `sync=false` — отложены (см. CLAUDE.md «мёртвое/отложенное»).
 *
 * Чистые данные (immutable) — тривиально тестируются и переопределяются в конфиге устройства.
 */
data class FeatureFlags(
    val detectorMetadata: Boolean = true,
    val detectorAudio: Boolean = false,
    val autoDislike: Boolean = false,
    val archive: Boolean = true,
    val restore: Boolean = true,
    val review: Boolean = true,
    val partialDownload: Boolean = false,
    val cloudDetector: Boolean = false,
    val sync: Boolean = false,
) {
    companion object {
        /** Профиль MVP по умолчанию (см. выше). */
        val MVP = FeatureFlags()
    }
}
