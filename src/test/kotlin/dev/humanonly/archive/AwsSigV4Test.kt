package dev.humanonly.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Golden-верификация [AwsSigV4] против официального AWS `aws-sig-v4-test-suite` (`get-vanilla`) —
 * хард-правило 9 (внешний протокол сверяется с референсом, не по памяти). Вектор взят из botocore
 * (`tests/unit/auth/aws4_testsuite/get-vanilla` + `AKIDEXAMPLE`/секрет из `test_sigv4.py`).
 *
 * Если хоть один HMAC/каноникализация уедет — расходятся хэш canonical-request, string-to-sign
 * или финальная подпись, и тест падает. Это единственная защита корректности SigV4 без живого S3.
 */
class AwsSigV4Test {

    private val creds = AwsSigV4.Credentials(
        accessKeyId = "AKIDEXAMPLE",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
    )
    private val region = "us-east-1"
    private val service = "service"
    private val date = "20150830"
    private val amzDate = "20150830T123600Z"

    /** Заголовки get-vanilla: host + x-amz-date, пустое тело. */
    private val headers = linkedMapOf(
        "host" to "example.amazonaws.com",
        "x-amz-date" to amzDate,
    )

    @Test
    fun `canonical request hash совпадает с AWS get-vanilla`() {
        val creq = AwsSigV4.canonicalRequest(
            method = "GET",
            canonicalUri = "/",
            canonicalQuery = "",
            headers = headers,
            payloadHashHex = AwsSigV4.EMPTY_PAYLOAD_SHA256,
        )
        // Точный canonical request из AWS-вектора (get-vanilla.creq).
        val expected = buildString {
            append("GET\n")
            append("/\n")
            append("\n")
            append("host:example.amazonaws.com\n")
            append("x-amz-date:20150830T123600Z\n")
            append("\n")
            append("host;x-amz-date\n")
            append("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        }
        assertEquals(expected, creq)
        assertEquals(
            "bb579772317eb040ac9ed261061d46c1f17a8133879d6129b6e1c25292927e63",
            AwsSigV4.sha256Hex(creq.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `string to sign совпадает с AWS get-vanilla`() {
        val creq = AwsSigV4.canonicalRequest(
            "GET", "/", "", headers, AwsSigV4.EMPTY_PAYLOAD_SHA256,
        )
        val scope = "$date/$region/$service/${AwsSigV4.TERMINATOR}"
        val sts = AwsSigV4.stringToSign(amzDate, scope, creq)
        val expected =
            "AWS4-HMAC-SHA256\n" +
                "20150830T123600Z\n" +
                "20150830/us-east-1/service/aws4_request\n" +
                "bb579772317eb040ac9ed261061d46c1f17a8133879d6129b6e1c25292927e63"
        assertEquals(expected, sts)
    }

    @Test
    fun `итоговая подпись совпадает с AWS get-vanilla`() {
        val signature = AwsSigV4.signature(
            creds = creds,
            method = "GET",
            canonicalUri = "/",
            canonicalQuery = "",
            headers = headers,
            payloadHashHex = AwsSigV4.EMPTY_PAYLOAD_SHA256,
            amzDate = amzDate,
            date = date,
            region = region,
            service = service,
        )
        assertEquals("5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31", signature)
    }

    @Test
    fun `authorization header собран по формату AWS`() {
        val signedHeaders = "host;x-amz-date"
        val signature = "5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"
        val auth = AwsSigV4.authorizationHeader(creds, date, region, service, signedHeaders, signature)
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, " +
                "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            auth,
        )
    }
}
