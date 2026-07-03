package dev.humanonly.yandex

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Подпись запросов get-file-info к API Яндекс.Музыки.
 *
 * message = конкатенация значений параметров в фиксированном порядке, из которых
 * удалены все запятые; sign = base64(HMAC-SHA256(key, message)) без последнего символа.
 *
 * Узел проверен байт-в-байт против Python-эталона в фазе 0 (spike/kt/GoldenVerify.kt,
 * фикстура spike/kt/sign_fixture.json) — решение ADR-0002 (вариант A, Kotlin-натив).
 */
object Signing {
    /** Публичная константа клиента ЯМ (присутствует в открытых клиентах, напр. slopless). Не секрет. */
    const val DEFAULT_SIGN_KEY = "p93jhgh689SBReK6ghtw62"

    /** Порядок значимых для подписи параметров get-file-info. */
    val SIGN_PARAM_ORDER = listOf("ts", "trackId", "quality", "codecs", "transports")

    /** Собирает message из значений параметров (в порядке [SIGN_PARAM_ORDER]) с удалением запятых. */
    fun buildMessage(params: Map<String, String>): String =
        SIGN_PARAM_ORDER.joinToString("") { params.getValue(it) }.replace(",", "")

    /** base64(HMAC-SHA256(key, message)) без последнего символа. */
    fun sign(message: String, key: String = DEFAULT_SIGN_KEY): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(raw).dropLast(1)
    }
}
