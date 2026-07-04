package dev.humanonly.archive

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Живая реализация [DiskHttp] на java.net.http.HttpClient (JDK stdlib — НЕ Ktor/Retrofit, те для
 * Android-слоя; хард-правило про зависимости). Тесты ядра — на fake; этот класс нужен только
 * live-инструменту [dev.humanonly.archive.tools] (реальный Диск, тестовый Диск-аккаунт Owner).
 *
 * Auth (хард-правило 4): `auth=true` вешает `Authorization: OAuth <token>` (вызовы к cloud-api);
 * `auth=false` — без токена (выданный upload/download href самодостаточен). Токен НЕ логируется.
 */
class HttpDiskTransport(
    private val token: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(60),
) : DiskHttp {

    override fun get(url: String, auth: Boolean): DiskResponse {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).GET()
        if (auth) builder.header("Authorization", "OAuth $token")
        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        return DiskResponse(resp.statusCode(), resp.body())
    }

    override fun put(url: String, body: ByteArray?, auth: Boolean): DiskResponse {
        val publisher = if (body != null) HttpRequest.BodyPublishers.ofByteArray(body)
        else HttpRequest.BodyPublishers.noBody()
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).PUT(publisher)
        if (auth) builder.header("Authorization", "OAuth $token")
        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        return DiskResponse(resp.statusCode(), resp.body())
    }
}
