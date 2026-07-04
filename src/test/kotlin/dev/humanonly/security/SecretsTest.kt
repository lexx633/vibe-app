package dev.humanonly.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Отпечаток секрета (хард-правило 4): не раскрывает секрет, но детерминирован и сверяем. */
class SecretsTest {

    @Test
    fun `отпечаток не содержит самого секрета`() {
        val token = "y0_AgAAAABsupersecrettoken12345"
        val fp = Secrets.fingerprint(token)
        assertFalse(fp.contains(token), "секрет не должен попадать в отпечаток")
        assertFalse(fp.contains("supersecret"), "фрагмент секрета не должен попадать в отпечаток")
    }

    @Test
    fun `отпечаток детерминирован и показывает длину`() {
        val a = Secrets.fingerprint("abcdef")
        val b = Secrets.fingerprint("abcdef")
        assertEquals(a, b, "один и тот же секрет → один отпечаток")
        assertTrue(a.startsWith("len=6 sha256="), "должна быть длина и префикс sha256: $a")
    }

    @Test
    fun `разные секреты дают разные отпечатки (ротация видна)`() {
        assertFalse(Secrets.fingerprint("token-old") == Secrets.fingerprint("token-new"))
    }

    @Test
    fun `известный вектор sha256 (сверка с эталоном)`() {
        // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals("len=3 sha256=ba7816bf8f01…", Secrets.fingerprint("abc"))
    }

    @Test
    fun `пустой секрет помечается явно, без хеша`() {
        assertEquals("len=0 sha256=<empty>", Secrets.fingerprint(""))
    }

    @Test
    fun `hexPrefix ограничивает длину показанного хеша`() {
        val fp = Secrets.fingerprint("abc", hexPrefix = 4)
        assertEquals("len=3 sha256=ba78…", fp)
    }
}
