package dev.humanonly.yandex

/**
 * Транспорт-агностичный интерфейс сетевого слоя ЯМ.
 *
 * Инъектируемый (хард-правило 9: внешние вызовы за интерфейсом, реализация — по референс-репо).
 * Позволяет тестировать ядро на fake-транспорте без реальной сети и токена.
 *
 * Все методы синхронные: оркестрацию/корутины/Android наводит слой выше (не в этой задаче).
 */
interface YandexTransport {

    /** GET url с query-параметрами и заголовками → тело как JSON-строка. */
    fun getJson(url: String, params: Map<String, String>, headers: Map<String, String>): String

    /** GET url → сырые байты (аудио-блоб CDN). */
    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray

    /**
     * GET url с `Range: bytes=from-to` → срез байт (для докачки/resume).
     * [to] == null → «от from до конца». CDN ЯМ отдаёт 206 byte-exact (lessons-learned 2026-07-03).
     */
    fun getRange(url: String, from: Long, to: Long?, headers: Map<String, String> = emptyMap()): ByteArray
}

/**
 * Сигнал троттлинга от ЯМ, который транспорт поднимает наверх для rate-limiter'а.
 * Не только HTTP 429/403, но и «тихий троттлинг»: 200 с пустым/урезанным телом (lessons-learned §6.3).
 */
class YandexThrottleException(val status: Int, message: String) : RuntimeException(message)
