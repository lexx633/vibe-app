package dev.humanonly.yandex

/**
 * Билдеры URL/params запросов к API ЯМ. Транспорт не вызывают — только собирают форму запроса
 * (тестируется офлайн на форме и парсинге фикстур). Живые вызовы — не в этой задаче.
 *
 * Приоритет кодеков и подпись get-file-info — по эталону spike/py/get_flac_reference.py (llistochek-подход):
 * дефолтный download-info отдаёт только mp3, lossless FLAC — только через подписанный get-file-info
 * (lessons-learned 2026-07-03).
 */
object Endpoints {

    /** Порядок кодеков (lossless → FLAC вперёд), точно как в эталоне. */
    const val CODECS = "flac,flac-mp4,mp3,aac,he-aac,aac-mp4,he-aac-mp4"

    data class Request(val url: String, val params: Map<String, String>)

    /**
     * Подписанный get-file-info. [ts] — unix-секунды (инъектируется для детерминизма теста).
     * params строятся в порядке [Signing.SIGN_PARAM_ORDER], затем добавляется `sign` (reuse [Signing]).
     */
    fun getFileInfo(config: YandexConfig, trackId: String, ts: Long): Request {
        val signed = linkedMapOf(
            "ts" to ts.toString(),
            "trackId" to trackId,
            "quality" to "lossless",
            "codecs" to CODECS,
            "transports" to "encraw",
        )
        val sign = Signing.sign(Signing.buildMessage(signed))
        val params = LinkedHashMap(signed).apply { put("sign", sign) }
        return Request("${config.baseUrl}/get-file-info", params)
    }

    /** Статус аккаунта (нужен uid для эндпоинта лайков). Подпись не требуется. */
    fun accountStatus(config: YandexConfig): Request =
        Request("${config.baseUrl}/account/status", emptyMap())

    /** Список лайков. Подпись не требуется — обычный авторизованный GET. */
    fun likes(config: YandexConfig, userId: String): Request =
        Request("${config.baseUrl}/users/$userId/likes/tracks", emptyMap())

    /** Метаданные трека(ов). Подпись не требуется. */
    fun trackMetadata(config: YandexConfig, trackId: String): Request =
        Request("${config.baseUrl}/tracks/$trackId", emptyMap())
}
