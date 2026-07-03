package dev.humanonly.yandex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Офлайн, детерминированный тест подписи против golden-фикстуры фазы 0.
 * Сети/аккаунта/секретов не требует.
 */
class SigningTest {

    private val fixture: JsonObject by lazy {
        val text = javaClass.getResourceAsStream("/sign_fixture.json")!!
            .readBytes().decodeToString()
        Json.parseToJsonElement(text) as JsonObject
    }

    @Test
    fun `message собирается из значений параметров без запятых`() {
        val params = (fixture["params"] as JsonObject)
            .mapValues { it.value.jsonPrimitive.content }
        val expected = fixture["message"]!!.jsonPrimitive.content
        assertEquals(expected, Signing.buildMessage(params))
    }

    @Test
    fun `sign совпадает с golden expected_sign`() {
        val message = fixture["message"]!!.jsonPrimitive.content
        val key = fixture["sign_key"]!!.jsonPrimitive.content
        val expected = fixture["expected_sign"]!!.jsonPrimitive.content
        assertEquals(expected, Signing.sign(message, key))
    }

    @Test
    fun `дефолтный ключ подписи совпадает с фикстурой`() {
        assertEquals(fixture["sign_key"]!!.jsonPrimitive.content, Signing.DEFAULT_SIGN_KEY)
    }
}
