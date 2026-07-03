package dev.humanonly.yandex

/**
 * Конфигурация клиента ЯМ: OAuth-токен и базовый URL.
 *
 * Хард-правило 4 (CLAUDE.md §18): токен приходит извне, НЕ хардкодится и НЕ логируется.
 * [toString] намеренно не раскрывает токен (только его наличие/длину), чтобы случайный
 * лог/дамп объекта не утёк секрет.
 *
 * Токен OAuth ЯМ (с мая 2026 — единственный путь, веб-API снесён, см. lessons-learned).
 */
class YandexConfig(
    private val accessToken: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    /** Значение X-Yandex-Music-Client; часть клиентов ЯМ требует его. Не секрет. */
    val clientHeader: String? = null,
) {
    init {
        require(accessToken.isNotBlank()) { "accessToken пуст" }
    }

    /** Обязательные заголовки авторизованного запроса к API ЯМ. */
    fun authHeaders(): Map<String, String> = buildMap {
        put("Authorization", "OAuth $accessToken")
        if (clientHeader != null) put("X-Yandex-Music-Client", clientHeader)
    }

    /** Без токена — только факт наличия и длина (хард-правило 4). */
    override fun toString(): String =
        "YandexConfig(baseUrl=$baseUrl, tokenLen=${accessToken.length}, hasClientHeader=${clientHeader != null})"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.music.yandex.net"
    }
}
