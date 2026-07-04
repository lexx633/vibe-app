package dev.humanonly.android

import dev.humanonly.yandex.YandexThrottleException
import dev.humanonly.yandex.YandexTransport
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Android-реализация [YandexTransport] на платформенном `HttpURLConnection` (без новых зависимостей;
 * `java.net.http` на Android нет — это зеркало тестового `HttpYandexTransport` с той же семантикой).
 * Логики нет — маппинг 1:1; контракт покрыт JVM-тестами `YandexClient` через fake-транспорт.
 *
 * - выставляет заголовки клиента ЯМ (Authorization и т.п. из `YandexConfig`);
 * - `Range: bytes=from-to` для докачки (CDN отдаёт 206);
 * - 429/403 и «тихий троттлинг» (200 с пустым телом на JSON) → [YandexThrottleException] → backoff (§6.3).
 */
class AndroidHttpTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : YandexTransport {

    override fun getJson(url: String, params: Map<String, String>, headers: Map<String, String>): String {
        val (status, body) = requestString(buildUrl(url, params), method = "GET", headers = headers, formBody = null)
        if (status == 429 || status == 403) throw YandexThrottleException(status, "throttled: HTTP $status")
        if (status == 200 && body.isBlank()) throw YandexThrottleException(200, "throttled: пустое тело при 200")
        if (status !in 200..299) error("HTTP $status для запроса JSON")
        return body
    }

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): String {
        val encoded = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val (status, body) = requestString(url, method = "POST", headers = headers, formBody = encoded)
        if (status == 429 || status == 403) throw YandexThrottleException(status, "throttled: HTTP $status")
        if (status !in 200..299) error("HTTP $status для POST-запроса")
        return body
    }

    override fun getBytes(url: String, headers: Map<String, String>): ByteArray =
        readBytes(url, headers, range = null)

    override fun getRange(url: String, from: Long, to: Long?, headers: Map<String, String>): ByteArray =
        readBytes(url, headers, range = "bytes=$from-${to ?: ""}")

    private fun readBytes(url: String, headers: Map<String, String>, range: String?): ByteArray {
        val conn = open(url, method = "GET", headers = headers, range = range)
        try {
            val status = conn.responseCode
            if (status == 429 || status == 403) throw YandexThrottleException(status, "throttled: HTTP $status")
            // 200 (полный блоб) и 206 (Range-срез) — оба валидны.
            if (status != 200 && status != 206) error("HTTP $status для запроса байт")
            return conn.inputStream.use { it.readAllBytesCompat() }
        } finally {
            conn.disconnect()
        }
    }

    private fun requestString(
        url: String,
        method: String,
        headers: Map<String, String>,
        formBody: String?,
    ): Pair<Int, String> {
        val conn = open(url, method, headers, range = null)
        try {
            if (formBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(formBody.toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = conn.responseCode
            // 2xx → inputStream, иначе errorStream (может быть null).
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { String(it.readAllBytesCompat(), StandardCharsets.UTF_8) } ?: ""
            return status to body
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, method: String, headers: Map<String, String>, range: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (range != null) conn.setRequestProperty("Range", range)
        return conn
    }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        if (params.isEmpty()) return base
        val query = params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return "$base?$query"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** minSdk 26 — Stream.readAllBytes() доступен только с API 33; читаем совместимо. */
private fun java.io.InputStream.readAllBytesCompat(): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
