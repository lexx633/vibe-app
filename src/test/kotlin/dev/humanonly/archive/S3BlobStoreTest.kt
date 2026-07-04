package dev.humanonly.archive

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [S3BlobStore] на fake-транспорте: проверяем path-style адресацию, маппинг HTTP-кодов на
 * [BlobStore]-контракт (exists/put/get) и что КАЖДЫЙ запрос уходит подписанным (Authorization +
 * x-amz-content-sha256 + x-amz-date). Живой S3 не нужен — [FakeS3Http] инспектирует запросы.
 */
class S3BlobStoreTest {

    /** Записанный запрос для инспекции. */
    private data class Sent(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray?)

    /** Fake S3: хранит блобы по canonical-uri, отдаёт заранее заданные коды. */
    private class FakeS3Http : S3Http {
        val sent = mutableListOf<Sent>()
        val objects = HashMap<String, ByteArray>() // ключ = путь после host, напр. /bucket/flac/ab/hash.flac
        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): S3Response {
            sent += Sent(method, url, headers, body)
            val path = url.substringAfter("://").substringAfter('/').let { "/$it" }
            return when (method) {
                "PUT" -> { objects[path] = body ?: ByteArray(0); S3Response(200, ByteArray(0)) }
                "HEAD" -> S3Response(if (path in objects) 200 else 404, ByteArray(0))
                "GET" -> objects[path]?.let { S3Response(200, it) } ?: S3Response(404, ByteArray(0))
                else -> S3Response(405, ByteArray(0))
            }
        }
    }

    private fun store(http: S3Http) = S3BlobStore(
        http = http,
        endpoint = "s3.us-east-1.amazonaws.com",
        bucket = "vibe-archive",
        region = "us-east-1",
        creds = AwsSigV4.Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"),
        clock = { 1_440_938_160_000L }, // фикс для детерминизма подписи (20150830T123600Z)
    )

    @Test
    fun `put — PUT с телом, подписан, exists видит объект`() {
        val http = FakeS3Http()
        val s = store(http)
        val content = byteArrayOf(1, 2, 3, 4, 5)

        assertTrue(s.put("flac/ab/abc123.flac", content))
        assertTrue(s.exists("flac/ab/abc123.flac"))

        val put = http.sent.first { it.method == "PUT" }
        assertEquals("https://s3.us-east-1.amazonaws.com/vibe-archive/flac/ab/abc123.flac", put.url)
        assertArrayEquals(content, put.body)
        // Каждый запрос подписан.
        assertTrue(put.headers["Authorization"]!!.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"))
        assertEquals(AwsSigV4.sha256Hex(content), put.headers["x-amz-content-sha256"])
        assertEquals("20150830T123600Z", put.headers["x-amz-date"])
    }

    @Test
    fun `get — вернёт байты для существующего, null для 404`() {
        val http = FakeS3Http()
        val s = store(http)
        val content = byteArrayOf(9, 8, 7)
        s.put("flac/cd/x.flac", content)

        assertArrayEquals(content, s.get("flac/cd/x.flac"))
        assertNull(s.get("flac/cd/missing.flac"))
    }

    @Test
    fun `exists — false для отсутствующего (HEAD 404)`() {
        val s = store(FakeS3Http())
        assertFalse(s.exists("flac/zz/none.flac"))
    }

    @Test
    fun `пустое тело GET-подписи использует SHA256 пустого payload`() {
        val http = FakeS3Http()
        store(http).exists("flac/ab/x.flac")
        val head = http.sent.single { it.method == "HEAD" }
        assertEquals(AwsSigV4.EMPTY_PAYLOAD_SHA256, head.headers["x-amz-content-sha256"])
    }
}
