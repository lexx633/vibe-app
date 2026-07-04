package dev.humanonly.archive

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * S3-совместимое хранилище FLAC-блобов (ТЗ §9: B2 дефолт, но подходит любой S3-совместимый: AWS S3,
 * Backblaze B2, MinIO, Cloudflare R2). ТОЛЬКО блобы — НЕ синк (§9: S3-как-БД синка — антипаттерн).
 *
 * Подпись запросов — AWS Signature Version 4, реализация [AwsSigV4] сверена по официальному алгоритму
 * (docs.aws.amazon.com/general SigV4) и покрыта golden-вектором из AWS `aws-sig-v4-test-suite`
 * (`get-vanilla`) — хард-правило 9. Только javax.crypto (Mac/HMAC-SHA256) + MessageDigest — доступно и
 * на Android, и на JVM, БЕЗ AWS SDK (лишняя тяжёлая зависимость; §8 — новые зависимости обсуждаются).
 *
 * Path-style адресация (`https://<endpoint>/<bucket>/<key>`) — совместима с B2/MinIO/R2, где
 * virtual-host стиль требует wildcard-домена. Ключи (пути блобов) детерминированы из хэша ([Archiver]).
 */

/** HTTP-ответ S3-слоя: код + сырое тело. */
data class S3Response(val status: Int, val body: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is S3Response && status == other.status && body.contentEquals(other.body))

    override fun hashCode(): Int = 31 * status + body.contentHashCode()
}

/** Транспорт S3: произвольный метод (GET/PUT/HEAD) с заголовками и опц. телом. Живая реализация — в test-source. */
interface S3Http {
    fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): S3Response
}

/**
 * Реализация AWS Signature Version 4 (HMAC-SHA256). Чистые функции (canonical request → string to sign →
 * signing key → signature) вынесены наружу для golden-верификации. Сверено с AWS `get-vanilla`-вектором.
 */
object AwsSigV4 {
    const val ALGORITHM = "AWS4-HMAC-SHA256"
    const val TERMINATOR = "aws4_request"

    /** SHA-256 пустого тела (для GET/HEAD/DELETE без payload) — обязательный x-amz-content-sha256 у S3. */
    const val EMPTY_PAYLOAD_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    data class Credentials(val accessKeyId: String, val secretAccessKey: String)

    /** Итог подписи: значение заголовка Authorization + полный набор заголовков к отправке. */
    data class Signed(val authorization: String, val headers: Map<String, String>)

    /**
     * Каноничный запрос (spec: method\nuri\nquery\ncanonicalHeaders\n\nsignedHeaders\npayloadHash).
     * [headers] — набор к подписи (обязательно host + x-amz-*), значения будут trimmed, имена → lowercase.
     */
    fun canonicalRequest(
        method: String,
        canonicalUri: String,
        canonicalQuery: String,
        headers: Map<String, String>,
        payloadHashHex: String,
    ): String {
        val sorted = headers.entries.sortedBy { it.key.lowercase() }
        val canonicalHeaders = sorted.joinToString("") { "${it.key.lowercase()}:${it.value.trim()}\n" }
        val signedHeaders = sorted.joinToString(";") { it.key.lowercase() }
        return buildString {
            append(method).append('\n')
            append(canonicalUri).append('\n')
            append(canonicalQuery).append('\n')
            append(canonicalHeaders).append('\n') // блок заголовков + пустая строка-разделитель
            append(signedHeaders).append('\n')
            append(payloadHashHex)
        }
    }

    /** Строка к подписи: ALGORITHM\namzDate\nscope\nHex(SHA256(canonicalRequest)). */
    fun stringToSign(amzDate: String, scope: String, canonicalRequest: String): String =
        "$ALGORITHM\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"

    /** Цепочка HMAC: AWS4+secret → date → region → service → aws4_request. */
    fun signingKey(secretKey: String, date: String, region: String, service: String): ByteArray {
        val kDate = hmac("AWS4$secretKey".toByteArray(Charsets.UTF_8), date)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        return hmac(kService, TERMINATOR)
    }

    /** Итоговая подпись (hex) для запроса. */
    fun signature(
        creds: Credentials,
        method: String,
        canonicalUri: String,
        canonicalQuery: String,
        headers: Map<String, String>,
        payloadHashHex: String,
        amzDate: String,
        date: String,
        region: String,
        service: String,
    ): String {
        val creq = canonicalRequest(method, canonicalUri, canonicalQuery, headers, payloadHashHex)
        val scope = "$date/$region/$service/$TERMINATOR"
        val sts = stringToSign(amzDate, scope, creq)
        val key = signingKey(creds.secretAccessKey, date, region, service)
        return hex(hmac(key, sts))
    }

    /** Собрать заголовок Authorization из scope + signedHeaders + signature. */
    fun authorizationHeader(
        creds: Credentials, date: String, region: String, service: String,
        signedHeaders: String, signature: String,
    ): String {
        val scope = "$date/$region/$service/$TERMINATOR"
        return "$ALGORITHM Credential=${creds.accessKeyId}/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    fun sha256Hex(data: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(data))

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * [BlobStore] поверх S3-совместимого хранилища. Path-style: `https://<endpoint>/<bucket>/<encodedKey>`.
 * [put]=PUT (тело+content-sha256), [exists]=HEAD (200/404), [get]=GET (200 байты / 404 → null).
 * Каждый запрос подписан [AwsSigV4]. [clock] отдаёт unix-время (в тестах фиксируется для детерминизма).
 */
class S3BlobStore(
    private val http: S3Http,
    private val endpoint: String, // host[:port] без схемы, напр. "s3.us-east-1.amazonaws.com" или "s3.eu-central-003.backblazeb2.com"
    private val bucket: String,
    private val region: String,
    private val creds: AwsSigV4.Credentials,
    private val service: String = "s3",
    private val secure: Boolean = true,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : BlobStore {

    override fun exists(path: String): Boolean = when (send("HEAD", path, null).status) {
        200 -> true
        404 -> false
        else -> error("S3 HEAD '$path': неожиданный HTTP ${send("HEAD", path, null).status}")
    }

    override fun put(path: String, content: ByteArray): Boolean {
        val status = send("PUT", path, content).status
        return status in 200..299
    }

    override fun get(path: String): ByteArray? {
        val resp = send("GET", path, null)
        return when (resp.status) {
            in 200..299 -> resp.body
            404 -> null
            else -> error("S3 GET '$path': неожиданный HTTP ${resp.status}")
        }
    }

    private fun send(method: String, path: String, body: ByteArray?): S3Response {
        val canonicalUri = "/$bucket/${encodeKey(path)}"
        val payloadHash = if (body != null) AwsSigV4.sha256Hex(body) else AwsSigV4.EMPTY_PAYLOAD_SHA256
        val now = clock()
        val amzDate = amzDate(now)
        val date = amzDate.substring(0, 8)

        val signedHeadersMap = linkedMapOf(
            "host" to endpoint,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate,
        )
        val signature = AwsSigV4.signature(
            creds, method, canonicalUri, canonicalQuery = "", headers = signedHeadersMap,
            payloadHashHex = payloadHash, amzDate = amzDate, date = date, region = region, service = service,
        )
        val signedHeaders = signedHeadersMap.keys.joinToString(";")
        val authorization = AwsSigV4.authorizationHeader(creds, date, region, service, signedHeaders, signature)

        val headers = LinkedHashMap(signedHeadersMap).apply { put("Authorization", authorization) }
        val url = "${if (secure) "https" else "http"}://$endpoint$canonicalUri"
        return http.send(method, url, headers, body)
    }

    /** AWS UriEncode для ключа: сохраняем '/', остальное percent-encode (кроме A-Za-z0-9-._~). */
    private fun encodeKey(key: String): String = key.trimStart('/').split('/').joinToString("/") { seg ->
        seg.toByteArray(Charsets.UTF_8).joinToString("") { b ->
            val c = b.toInt() and 0xFF
            if (c.toChar().let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' || it == '_' || it == '~' }) {
                c.toChar().toString()
            } else {
                "%%%02X".format(c)
            }
        }
    }

    /** ISO8601 basic UTC (yyyyMMdd'T'HHmmss'Z') без внешних форматтеров — детерминированно от epoch-ms. */
    private fun amzDate(epochMs: Long): String {
        val totalSec = epochMs / 1000
        val days = Math.floorDiv(totalSec, 86_400L)
        val secOfDay = Math.floorMod(totalSec, 86_400L).toInt()
        val (y, mo, d) = civilFromDays(days)
        val hh = secOfDay / 3600
        val mm = (secOfDay % 3600) / 60
        val ss = secOfDay % 60
        return "%04d%02d%02dT%02d%02d%02dZ".format(y, mo, d, hh, mm, ss)
    }

    /** Григорианская дата из числа дней от 1970-01-01 (алгоритм Howard Hinnant, days_from_civil обратно). */
    private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }
}
