package dev.humanonly.android

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.humanonly.detector.MetaFeatureExtractor
import dev.humanonly.detector.TrackMetaFeatures
import dev.humanonly.state.TrackState

/**
 * Живой on-device детект-smoke (§10, каскад детекции) — БЕЗ adb: гоняет РЕАЛЬНЫЙ продакшн-каскад
 * [dev.humanonly.detector.DetectionCascade] (hard gate slopless + метаданные) на одном треке из ЯМ и
 * показывает вердикт на экране. Даёт видимую «проверку на ИИ / human», которой не было в UI (детект
 * штатно идёт молча внутри планового прогона и пишет вердикт в SQLite-индекс).
 *
 * Что меряется (ровно продакшн-путь [ServiceLocator.detectionCascade]):
 *   - Каскад 0 (hard gate): `primary artist_id` трека ищется в базе slopless (~140k id). Hit → `SUSPECTED`.
 *   - Каскад 1 (метаданные): признаки трека извлекаются на устройстве из ТОГО ЖЕ ответа `tracks/{id}`
 *     ([MetaFeatureExtractor]: шаблонный AI-нейминг в title/имени артиста, подозрительный лейбл) →
 *     [TrackMetaFeatures] → meta_score. Серая зона (meta ≥ порога, но < high) → `REVIEW_REQUIRED`.
 *   - Каскад 2 (аудио): отложен по лицензии deezer (CC BY-NC + патенты, CLAUDE.md §10) → аудио-скор null,
 *     поэтому серая зона решается человеком (`REVIEW_REQUIRED`), а не автоматом.
 *
 * Кнопка «Проба правил по названию» — чисто офлайн демонстрация каскада 1 без сети: вводишь title,
 * прогоняется [MetaFeatureExtractor] + каскад с ПУСТЫМ artist_id (гейт мимо) → видно, как шаблонный
 * нейминг детерминированно даёт `REVIEW_REQUIRED` (серую зону).
 *
 * Токен ЯМ — тестовый (хард-правило 3): из [ServiceLocator.tokenStore], иначе запечённый [BakedTokens].
 * Токен НЕ логируется (хард-правило 4). Запрос метаданных идёт через реальный rate-limiter клиента
 * (хард-правило 7). Ничего не мутирует — только читает метаданные и считает вердикт.
 *
 * PII (§12): имя артиста/название НЕ выводим — только id, вердикт, скор, машинный reason.
 */
class DetectSmokeActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var trackInput: EditText
    private lateinit var titleInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        showGateInfo()
    }

    private fun buildUi(): ScrollView {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Детект-smoke (ИИ / human)"
            textSize = 20f
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Прогнать реальный каскад детекции (slopless hard-gate + метаданные) на одном треке ЯМ."
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        })

        trackInput = EditText(this).apply {
            hint = "trackId (пусто = первый лайк)"
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(trackInput)

        root.addView(button("Проверить трек") { onDetect() })

        root.addView(TextView(this).apply {
            text = "— или офлайн-проба правил каскада 1 по названию (без сети, без токена) —"
            textSize = 12f
            setPadding(0, dp(12), 0, dp(4))
        })
        titleInput = EditText(this).apply {
            hint = "название трека (напр. «Chill Lofi Type Beat»)"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(titleInput)
        root.addView(button("Проба правил по названию (офлайн)") { onTitleProbe() })

        out = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
            text = "Готов."
        }
        root.addView(out)

        return ScrollView(this).apply { addView(root) }
    }

    /** Показать состояние базы slopless сразу — видно, работает ли hard-gate (не пустой ли он). */
    private fun showGateInfo() {
        Thread {
            val info = runCatching {
                val gate = ServiceLocator.sloplessGate(this)
                if (gate.size == 0) {
                    "База slopless НЕ загружена (0 артистов) — hard-gate спит, вердикт только по метаданным.\n" +
                        "Положи slopless.json в assets/ или filesDir."
                } else {
                    "База slopless: ${gate.size} артистов, версия ${gate.version}. Готов проверять."
                }
            }.getOrElse { "Ошибка загрузки базы slopless: ${it.message}" }
            log(info)
        }.start()
    }

    private fun onDetect() {
        val trackIdInput = trackInput.text?.toString()?.trim().orEmpty()
        log("Детект…")
        Thread {
            val report = runCatching { runDetect(trackIdInput) }.getOrElse { "ОШИБКА: ${it.message}" }
            log(report)
        }.start()
    }

    private fun runDetect(trackIdInput: String): String {
        val token = ServiceLocator.tokenStore(this).load() ?: BakedTokens.yandexMusic(this)
            ?: return "Нет токена ЯМ. Положи токен в MainActivity (тестовый акк)."
        val client = ServiceLocator.yandexClient(token)

        val trackId = trackIdInput.ifBlank {
            val uid = client.accountUid().toString()
            client.likedTrackIds(uid).firstOrNull() ?: return "В лайках пусто — укажи trackId вручную."
        }

        val meta = client.trackMetadata(trackId).firstOrNull()
            ?: return "Трек $trackId не найден в ЯМ (пустые метаданные)."
        val artistIds = meta.artists.map { it.artistId }
        val primary = meta.primaryArtistId()

        // Каскад 1: признаки из ТОГО ЖЕ ответа tracks/{id} (без доп-запросов). Имена артистов/лейблов
        // живут только тут (PII §12) — наружу уходит только булев [TrackMetaFeatures].
        val features = ServiceLocator.metaFeatureExtractor().extract(
            title = meta.title,
            artistNames = meta.artists.mapNotNull { it.name },
            labelNames = meta.albums.flatMap { it.labels }.mapNotNull { it.name },
        )

        val gate = ServiceLocator.sloplessGate(this)
        val cascade = ServiceLocator.detectionCascade(this)
        // Продакшн-путь: гейтим по primary artist_id (как LiveScanSource/ArtistEnricher) + метапризнаки.
        val result = cascade.detect(primary ?: "", features)
        // Доп-сигнал: членство ВСЕХ артистов трека в базе (фичеринг с AI-артистом виден, даже если
        // primary чист — продакшн-гейт пока смотрит только primary, это осознанное упрощение MVP).
        val hits = artistIds.filter { gate.isAiArtist(it) }

        return buildString {
            appendLine("trackId:        $trackId")
            appendLine("artist_id:      primary=${primary ?: "-"} всего=${artistIds.size} ${artistIds.joinToString(",", "[", "]")}")
            appendLine("slopless-гейт:  primary в базе=${primary?.let { gate.isAiArtist(it) } ?: false}; всего совпало=${hits.size} ${if (hits.isNotEmpty()) hits.joinToString(",", "[", "]") else ""}")
            appendLine("метапризнаки:   ${featuresRu(features)}")
            appendLine("meta_score:     ${result.metaScore ?: "-"}")
            appendLine("————————————————————")
            appendLine("ВЕРДИКТ:        ${verdictRu(result.verdict)}  [${result.reason}]")
            if (hits.isNotEmpty() && result.verdict != TrackState.SUSPECTED) {
                appendLine("⚠ фичеринг-артист в базе slopless, но primary чист — продакшн-гейт primary-only.")
            }
            append(hint(result.verdict, gate.size))
        }.trimEnd()
    }

    private fun onTitleProbe() {
        val title = titleInput.text?.toString()?.trim().orEmpty()
        if (title.isBlank()) {
            log("Введи название трека для офлайн-пробы правил каскада 1.")
            return
        }
        // Полностью офлайн, без токена/сети: только чистый экстрактор + каскад с пустым artist_id
        // (slopless-гейт мимо) — видно, как один шаблонный сигнал даёт серую зону REVIEW_REQUIRED.
        val features = ServiceLocator.metaFeatureExtractor().extract(title = title)
        val result = ServiceLocator.detectionCascade(this).detect("", features)
        log(
            buildString {
                appendLine("офлайн-проба (artist_id пуст, гейт мимо)")
                appendLine("метапризнаки:   ${featuresRu(features)}")
                appendLine("meta_score:     ${result.metaScore ?: "-"}")
                appendLine("————————————————————")
                append("ВЕРДИКТ:        ${verdictRu(result.verdict)}  [${result.reason}]")
            },
        )
    }

    /** Булевы метапризнаки каскада 1 в читаемую строку (без имён — PII §12). */
    private fun featuresRu(f: TrackMetaFeatures): String =
        "шаблон.нейминг=${f.templateNameHit}, подозр.лейбл=${f.suspiciousLabel}, каденция=${f.releasesInWindow ?: "-"}"

    private fun verdictRu(v: TrackState): String = when (v) {
        TrackState.SUSPECTED -> "ИИ (SUSPECTED) — артист в базе slopless"
        TrackState.REVIEW_REQUIRED -> "СЕРАЯ ЗОНА (REVIEW_REQUIRED) — на ручное ревью"
        TrackState.CLEAN -> "HUMAN (CLEAN) — по гейту+метаданным чисто"
        else -> v.name
    }

    private fun hint(v: TrackState, gateSize: Int): String = when {
        v == TrackState.CLEAN && gateSize == 0 ->
            "\nПодсказка: база slopless пуста — «HUMAN» здесь значит лишь «не пойман метаданными»."
        v == TrackState.CLEAN ->
            "\nПодсказка: чтобы увидеть «ИИ», проверь трек артиста из базы slopless."
        else -> ""
    }

    private fun log(text: String) = runOnUiThread { out.text = text }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
