package dev.humanonly.yandex

/**
 * Классификатор ответа ЯМ на «челленджи» и троттлинг (ТЗ §6.3, lessons-learned).
 *
 * Транспорт видит только сырые сигналы (код, финальный URL после редиректов, тело). Различить
 * их семантику — задача этого чистого (без сети) классификатора, чтобы слой выше принял разное
 * решение:
 *  - [ChallengeKind.CAPTCHA]        → воркер **paused** (не failed) + push «Требуется проверка» +
 *                                     cooldown RPS ×0.5 на 30 мин (§6.3);
 *  - [ChallengeKind.RATE_LIMIT]     → backoff (усилить интервал);
 *  - [ChallengeKind.SILENT_THROTTLE]→ «тихий троттлинг»: 200 с пустым/урезанным телом — тоже backoff;
 *  - [ChallengeKind.OK]             → нормальный ответ.
 *
 * Капча ЯМ приходит как редирект на passport.yandex.ru/auth с `retpath` (или 403 с тем же URL),
 * либо тело содержит маркеры SmartCaptcha. Различаем капчу и обычный 429/403 именно по URL/маркеру.
 */
enum class ChallengeKind { OK, RATE_LIMIT, SILENT_THROTTLE, CAPTCHA }

data class ChallengeResult(val kind: ChallengeKind, val reason: String) {
    val isThrottleSignal: Boolean
        get() = kind == ChallengeKind.RATE_LIMIT || kind == ChallengeKind.SILENT_THROTTLE || kind == ChallengeKind.CAPTCHA
}

object ChallengeClassifier {

    /** Маркеры страницы капчи/челленджа Яндекса в URL или теле. */
    private val CAPTCHA_URL_MARKERS = listOf("passport.yandex.ru/auth", "passport.yandex.com/auth")
    private val CAPTCHA_BODY_MARKERS = listOf("SmartCaptcha", "showcaptcha", "checkbox-captcha", "js/captcha")

    /**
     * @param statusCode  HTTP-код ответа (после следования редиректам).
     * @param finalUrl    итоговый URL (может быть passport.* если ЯМ увёл на челлендж).
     * @param body        тело (для JSON-запросов; для байтовых можно пустую строку).
     * @param expectsJson ожидали ли JSON (тогда пустое/урезанное тело при 200 = тихий троттлинг).
     */
    fun classify(
        statusCode: Int,
        finalUrl: String,
        body: String,
        expectsJson: Boolean = true,
    ): ChallengeResult {
        // 1) Капча — по URL passport/auth+retpath ИЛИ маркерам в теле (в приоритете над кодом).
        if (looksLikeCaptcha(finalUrl, body)) {
            return ChallengeResult(ChallengeKind.CAPTCHA, "captcha: passport/auth или SmartCaptcha-маркер")
        }
        // 2) Явный rate-limit.
        if (statusCode == 429) return ChallengeResult(ChallengeKind.RATE_LIMIT, "HTTP 429")
        if (statusCode == 403) return ChallengeResult(ChallengeKind.RATE_LIMIT, "HTTP 403 (без passport-паттерна)")
        // 3) Тихий троттлинг: 200 с пустым/урезанным JSON.
        if (expectsJson && statusCode == 200 && isEmptyOrTruncatedJson(body)) {
            return ChallengeResult(ChallengeKind.SILENT_THROTTLE, "200 с пустым/урезанным телом")
        }
        // 4) Прочие не-2xx — не наш класс (транспорт бросит обычную ошибку), но помечаем как OK-неаппликабл.
        return ChallengeResult(ChallengeKind.OK, "ok")
    }

    private fun looksLikeCaptcha(finalUrl: String, body: String): Boolean {
        val url = finalUrl.lowercase()
        val urlHit = CAPTCHA_URL_MARKERS.any { url.contains(it) } &&
            (url.contains("retpath") || url.contains("/auth"))
        if (urlHit) return true
        return CAPTCHA_BODY_MARKERS.any { body.contains(it, ignoreCase = true) }
    }

    /** Пустое тело, либо начатый, но не закрытый JSON (обрыв/усечение). */
    private fun isEmptyOrTruncatedJson(body: String): Boolean {
        val t = body.trim()
        if (t.isEmpty()) return true
        val startsJson = t.startsWith("{") || t.startsWith("[")
        if (!startsJson) return true // на JSON-запросе не-JSON тело = мусор/заглушка
        val balanced = (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
        return !balanced
    }
}
