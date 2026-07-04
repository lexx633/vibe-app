package dev.humanonly.android

import dev.humanonly.archive.DiskHttp
import dev.humanonly.archive.DiskResponse
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android-реализация [DiskHttp] на платформенном `HttpURLConnection` (без новых зависимостей;
 * `java.net.http` на Android нет — зеркало тестового `HttpDiskTransport` с той же семантикой). Ядро
 * Диска ([dev.humanonly.archive.YandexDiskClient] и store'ы) покрыто JVM-тестами на fake; здесь — только
 * транспорт для живого прогона на устройстве.
 *
 * Auth (хард-правило 4): `auth=true` вешает `Authorization: OAuth <token>` (вызовы к cloud-api);
 * `auth=false` — без токена (выданный upload/download href самодостаточен). Токен НЕ логируется.
 * Редиректы следуем (download-href уходит 302 на storage-хост).
 */
class AndroidDiskHttp(
    private val token: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : DiskHttp {

    override fun get(url: String, auth: Boolean): DiskResponse = request("GET", url, body = null, auth = auth)

    override fun put(url: String, body: ByteArray?, auth: Boolean): DiskResponse =
        request("PUT", url, body = body, auth = auth)

    private fun request(method: String, url: String, body: ByteArray?, auth: Boolean): DiskResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            if (auth) conn.setRequestProperty("Authorization", "OAuth $token")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            // 2xx → inputStream; иначе errorStream (может быть null — например 404 на exists/download).
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readAllBytesCompat() } ?: ByteArray(0)
            return DiskResponse(status, bytes)
        } finally {
            conn.disconnect()
        }
    }
}

/** minSdk 26 — Stream.readAllBytes() c API 33; читаем совместимо (тот же приём, что в AndroidHttpTransport). */
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
