package dev.humanonly.yandex

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * HTTP-реализация [YandexTransport] на java.net.http.HttpClient (JDK stdlib, чистый JVM —
 * НЕ Ktor/Retrofit, те для Android-слоя позже; хард-правило про зависимости).
 *
 * - выставляет заголовки клиента ЯМ (Authorization и т.п. — приходят из [YandexConfig]);
 * - поддерживает `Range: bytes=from-to` для докачки (CDN отдаёт 206);
 * - 429/403 и «тихий троттлинг» (200 с пустым телом на JSON-запросе) → [YandexThrottleException],
 *   чтобы rate-limiter поднял backoff (lessons-learned §6.3).
 *
 * Реальные вызовы этой задачей не выполняются (тесты — на fake-транспорте). Класс — для живого слоя (1c).
 */
class HttpYandexTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) : YandexTransport {

    override fun getJson(url: String, params: Map<String, String>, headers: Map<String, String>): String {
        val resp = send(buildUrl(url, params), headers, range = null, asBytes = false)
        val body = resp.body() as String
        if (resp.statusCode() == 429 || resp.statusCode() == 403) {
            throw YandexThrottleException(resp.statusCode(), "throttled: HTTP ${resp.statusCode()}")
        }
        // Тихий троттлинг: 200 с пустым телом — тоже сигнал бэкоффа (§6.3).
        if (resp.statusCode() == 200 && body.isBlank()) {
            throw YandexThrottleException(200, "throttled: пустое тело при 200")
        }
        if (resp.statusCode() !in 200..299) {
            error("HTTP ${resp.statusCode()} для запроса JSON")
        }
        return body
    }

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): String {
        val body = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val text = resp.body()
        if (resp.statusCode() == 429 || resp.statusCode() == 403) {
            throw YandexThrottleException(resp.statusCode(), "throttled: HTTP ${resp.statusCode()}")
        }
        if (resp.statusCode() !in 200..299) {
            error("HTTP ${resp.statusCode()} для POST-запроса")
        }
        return text
    }

    override fun getBytes(url: String, headers: Map<String, String>): ByteArray =
        readBytes(url, headers, range = null)

    override fun getRange(url: String, from: Long, to: Long?, headers: Map<String, String>): ByteArray {
        val rangeHeader = "bytes=$from-${to ?: ""}"
        return readBytes(url, headers, range = rangeHeader)
    }

    private fun readBytes(url: String, headers: Map<String, String>, range: String?): ByteArray {
        @Suppress("UNCHECKED_CAST")
        val resp = send(url, headers, range, asBytes = true) as HttpResponse<ByteArray>
        if (resp.statusCode() == 429 || resp.statusCode() == 403) {
            throw YandexThrottleException(resp.statusCode(), "throttled: HTTP ${resp.statusCode()}")
        }
        // 200 (полный блоб) и 206 (Range-срез) — оба валидны.
        if (resp.statusCode() !in intArrayOf(200, 206)) {
            error("HTTP ${resp.statusCode()} для запроса байт")
        }
        return resp.body()
    }

    private fun send(url: String, headers: Map<String, String>, range: String?, asBytes: Boolean): HttpResponse<*> {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).GET()
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (range != null) builder.header("Range", range)
        val req = builder.build()
        return if (asBytes) {
            client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        } else {
            client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }
    }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        if (params.isEmpty()) return base
        val query = params.entries.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
        }
        return "$base?$query"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
