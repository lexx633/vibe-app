package dev.humanonly.security

import java.security.MessageDigest

/**
 * Безопасное для логов/UI представление секретов (хард-правило 4, CLAUDE.md §18): токен/ключ НИКОГДА
 * не попадают в логи/URL целиком — только длина и sha256 (можно префикс). Чистая функция → тестируется
 * на JVM и переиспользуется и в core, и в Android-слое.
 */
object Secrets {

    /**
     * Отпечаток секрета для диагностики: `len=<длина> sha256=<hex[..prefix]>`. По нему нельзя восстановить
     * секрет, но можно сверить «тот же/не тот» и заметить ротацию. Пустой секрет → явная метка (без хеша).
     *
     * @param hexPrefix сколько hex-символов sha256 показать (дефолт 12 из 64) — достаточно для сверки.
     */
    fun fingerprint(secret: String, hexPrefix: Int = 12): String {
        if (secret.isEmpty()) return "len=0 sha256=<empty>"
        val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        val shown = hex.take(hexPrefix.coerceIn(1, hex.length))
        return "len=${secret.length} sha256=$shown…"
    }
}
