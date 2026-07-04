package dev.humanonly.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.humanonly.security.Secrets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Хранилище OAuth-токена ЯМ (хард-правило 3/4). Плейнтекст токена в память отдаётся только вызывающему
 * (сборка [dev.humanonly.yandex.YandexConfig]); в логи/UI — НИКОГДА, только [Secrets.fingerprint].
 */
interface TokenStore {
    /** Сохранить/перезаписать токен (ротация). */
    fun save(token: String)

    /** Токен или null, если не сохранён. */
    fun load(): String?

    /** Стереть токен (logout/утечка → ротация). */
    fun clear()

    /** Безопасный отпечаток сохранённого токена для логов (len+sha256) или null. */
    fun fingerprint(): String?
}

/**
 * [TokenStore] на аппаратном **AndroidKeyStore**: AES-256/GCM-ключ неизвлекаем (живёт в TEE/StrongBox
 * где есть), шифротекст+IV лежат в обычном SharedPreferences. Без внешних зависимостей (депрекейтнутый
 * androidx.security-crypto не тянем). Ключ не требует user-auth — фоновому воркеру он нужен без разблокировки.
 */
class KeystoreTokenStore(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : TokenStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun save(token: String) {
        require(token.isNotBlank()) { "пустой токен" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_IV, b64(cipher.iv))
            .putString(KEY_CT, b64(ct))
            .apply()
    }

    override fun load(): String? {
        val iv = prefs.getString(KEY_IV, null)?.let(::unb64) ?: return null
        val ct = prefs.getString(KEY_CT, null)?.let(::unb64) ?: return null
        val key = existingKey() ?: return null // ключ сброшен (переустановка/бэкап) → токен нечитаем
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    override fun clear() {
        prefs.edit().remove(KEY_IV).remove(KEY_CT).apply()
        // Ключ оставляем — новый save() переиспользует; при желании полной зачистки удалить alias отдельно.
    }

    override fun fingerprint(): String? = load()?.let { Secrets.fingerprint(it) }

    private fun getOrCreateKey(): SecretKey = existingKey() ?: generateKey()

    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun generateKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val DEFAULT_KEY_ALIAS = "humanonly.token.v1"
        private const val PREFS_NAME = "humanonly.secure"
        private const val KEY_IV = "token_iv"
        private const val KEY_CT = "token_ct"
    }
}
