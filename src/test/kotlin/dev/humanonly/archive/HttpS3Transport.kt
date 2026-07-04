package dev.humanonly.archive

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Живая реализация [S3Http] на java.net.http.HttpClient (JDK stdlib — без AWS SDK; хард-правило про
 * зависимости). Ядро [S3BlobStore]/[AwsSigV4] тестируется на fake + golden-векторе; этот транспорт
 * нужен только live-инструменту (реальный B2/S3-бакет). Заголовки подписи уже собраны в [S3BlobStore] —
 * транспорт лишь дословно перекладывает их в HTTP-запрос. Никаких секретов не логирует.
 *
 * HEAD маппится на `method("HEAD", noBody())` (JDK не имеет отдельного HEAD-хелпера). Redirect НЕ
 * следуем автоматически: S3 использует 3xx редко, а следование сломало бы подпись (URL меняется).
 */
class HttpS3Transport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(120),
) : S3Http {

    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): S3Response {
        val publisher = if (body != null) HttpRequest.BodyPublishers.ofByteArray(body)
        else HttpRequest.BodyPublishers.noBody()
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).method(method, publisher)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        return S3Response(resp.statusCode(), resp.body())
    }
}
