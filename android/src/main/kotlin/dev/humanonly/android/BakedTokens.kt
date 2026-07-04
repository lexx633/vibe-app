package dev.humanonly.android

import android.content.Context

/**
 * Тестовые токены, запечённые в APK как gitignored-ассеты (`assets/<имя>.token`, паттерн `.token`
 * в .gitignore) — чтобы на устройстве не вводить руками (хард-правило 3: тестовые акки). В исходники
 * и публичный репо токены НЕ попадают: ассеты живут только в локальном бинаре.
 *
 * Хард-правило 4: токен НИКОГДА не логируется/не показывается — только используется. Здесь нет ни печати,
 * ни возврата в UI-строку; вызывающий подставляет значение в store/поле, не отображая его.
 *
 * Формат ассета — implicit-flow JSON `{"access_token": "...", ...}` (как выдаёт OAuth ЯМ/Диска).
 * Парсинг — регэксп по одному полю: kotlinx-serialization в :android нет, тащить зависимость ради
 * одного поля нельзя (хард-правило 8).
 */
object BakedTokens {

    /**
     * OAuth-токен Яндекс.Музыки. Приоритет: РАБОЧИЙ акк (`ym_prod.token`), если ассет запечён; иначе —
     * тестовый (`ym_test.token`). Оба ассета gitignored (`*.token`) — в исходники/публичный репо не попадают.
     * Переход на прод (хард-правило 3): Owner кладёт свой реальный токен в `assets/ym_prod.token` — код тот же,
     * значение токена здесь не читается и не логируется, только подставляется в store.
     */
    fun yandexMusic(ctx: Context): String? = read(ctx, "ym_prod.token") ?: read(ctx, "ym_test.token")

    /**
     * Метка активного акк-источника для UI/логов БЕЗ утечки токена (хард-правило 4): `"prod"`, `"test"`
     * или `"none"`. Показывать на экране чистки, чтобы Owner видел, против какого акка идёт деструктив.
     */
    fun yandexMusicSource(ctx: Context): String = when {
        read(ctx, "ym_prod.token") != null -> "prod"
        read(ctx, "ym_test.token") != null -> "test"
        else -> "none"
    }

    /** OAuth-токен Яндекс.Диска (`yadisk.token`), либо null если ассет не запечён. */
    fun yandexDisk(ctx: Context): String? = read(ctx, "yadisk.token")

    private val ACCESS_TOKEN = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"")

    private fun read(ctx: Context, asset: String): String? = runCatching {
        val text = ctx.assets.open(asset).bufferedReader().use { it.readText() }
        ACCESS_TOKEN.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
