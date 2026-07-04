package dev.humanonly.android

import android.app.Activity
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
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
import android.widget.Toast
import dev.humanonly.yandex.ContainerDetect
import dev.humanonly.yandex.ContainerFormat
import dev.humanonly.yandex.FlacArchivePreparer
import dev.humanonly.yandex.RateLimiter
import dev.humanonly.yandex.TrackDownloader
import java.io.File

/**
 * Живой on-device smoke Media3-демуксера (§F6) — БЕЗ adb и инструментальных тестов: экран, который
 * пользователь запускает на реальном телефоне (Robolectric ненадёжен для формы упаковки STREAMINFO,
 * классдок [Media3FlacDemuxer] явно требует живой прогон на устройстве).
 *
 * Что проверяет: контейнер flac-mp4 (ЯМ lossless) → [Media3FlacDemuxer] демукс → [FlacRemux] сборка
 * сырого `.flac` → распаковка STREAMINFO из собранного файла (offsets 18–21) сверяется с тем, что видит
 * платформенный [MediaExtractor] по этому же `.flac`. Совпадение sampleRate/channels ⇒ STREAMINFO упакован
 * корректно (34-байтное тело без «висящего» 4-байтного METADATA_BLOCK_HEADER).
 *
 * Два источника входа (любой):
 *   - «Скачать lossless из ЯМ» — берёт сохранённый токен ([ServiceLocator.tokenStore]), тянет реальный
 *     блоб продакшн-путём (getFileInfo → [TrackDownloader] → расшифровка). Нужен токен (хард-правило 3 —
 *     тестовый акк). trackId из поля, либо авто-выбор первого лайка.
 *   - «Выбрать файл (SAF)» — локальный flac-mp4/.flac без сети (ACTION_OPEN_DOCUMENT).
 *
 * Хард-правило 4: токен/ключ/PII на экран НЕ выводим — только контейнер, размеры, частота/каналы, sha256.
 * Хард-правило 7: скачивание идёт через реальный [RateLimiter] клиента (не отключаем).
 */
class SmokeActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var trackInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Media3 FLAC smoke"
            textSize = 20f
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Демукс flac-mp4 → raw .flac и сверка упаковки STREAMINFO с платформенным MediaExtractor."
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        })

        trackInput = EditText(this).apply {
            hint = "trackId (необязательно; пусто = первый лайк)"
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(trackInput)

        root.addView(button("Скачать lossless из ЯМ → демукс") { onDownloadAndSmoke() })
        root.addView(button("Выбрать файл (flac-mp4 / .flac)") { onPickFile() })

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

    // ── Источник 1: реальный ЯМ-путь ──────────────────────────────────────────
    private fun onDownloadAndSmoke() {
        val trackId = trackInput.text?.toString()?.trim().orEmpty()
        log("Скачивание из ЯМ…")
        Thread {
            val report = runCatching {
                val token = ServiceLocator.tokenStore(this).load()
                    ?: return@runCatching "Нет токена. Положи токен в MainActivity (тестовый акк)."
                val client = ServiceLocator.yandexClient(token)
                val id = trackId.ifBlank {
                    val uid = client.accountUid().toString()
                    client.likedTrackIds(uid).firstOrNull()
                        ?: return@runCatching "В лайках пусто — укажи trackId вручную."
                }
                val info = client.getFileInfo(id, System.currentTimeMillis() / 1000)
                val downloader = TrackDownloader(client, AndroidHttpTransport(), rateLimiter())
                val result = downloader.download(info)
                buildString {
                    appendLine("trackId:        $id")
                    appendLine("codec ЯМ:       ${info.codec}${info.quality?.let { " / $it" } ?: ""}")
                    appendLine("скачано:        ${result.decrypted.size} B (sha256 ${result.sha256.take(12)}…)")
                    append(smokeReport(result.decrypted))
                }
            }.getOrElse { "ОШИБКА: ${it.message}" }
            log(report)
        }.start()
    }

    // ── Источник 2: локальный файл через SAF ──────────────────────────────────
    private fun onPickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        log("Чтение файла…")
        Thread {
            val report = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@runCatching "Не удалось прочитать файл."
                "файл:           ${bytes.size} B\n" + smokeReport(bytes)
            }.getOrElse { "ОШИБКА: ${it.message}" }
            log(report)
        }.start()
    }

    /**
     * Ядро smoke: контейнер → prepare (демукс+сборка) → распаковка STREAMINFO из собранного .flac →
     * сверка с MediaExtractor. Возвращает готовый текст-отчёт (без секретов).
     */
    private fun smokeReport(decrypted: ByteArray): String {
        val container = ContainerDetect.detect(decrypted)
        val prepared = FlacArchivePreparer(Media3FlacDemuxer()).prepare(decrypted)
        val flac = prepared.flac

        val si = parseStreamInfo(flac)
        val tmp = File(cacheDir, "smoke.flac").also { it.writeBytes(flac) }
        val ex = extractWithPlatform(tmp)

        val srMatch = ex != null && ex.sampleRate == si.sampleRate
        val chMatch = ex != null && ex.channels == si.channels
        val ok = srMatch && chMatch

        return buildString {
            appendLine("контейнер:      ${container.name}")
            appendLine("remuxed:        ${prepared.remuxed}")
            appendLine(".flac собран:   ${flac.size} B (sha256 ${prepared.sha256.take(12)}…)")
            appendLine("fLaC-маркер:    ${if (hasFlacMarker(flac)) "OK" else "НЕТ ✗"}")
            appendLine("— STREAMINFO (из собранного .flac) —")
            appendLine("  sampleRate:   ${si.sampleRate} Hz")
            appendLine("  channels:     ${si.channels}")
            appendLine("  bitsPerSmpl:  ${si.bitsPerSample}")
            appendLine("— MediaExtractor (платформа) —")
            if (ex == null) {
                appendLine("  НЕ РАСПОЗНАН ✗ (платформа не приняла .flac)")
            } else {
                appendLine("  mime:         ${ex.mime}")
                appendLine("  sampleRate:   ${ex.sampleRate} Hz  ${mark(srMatch)}")
                appendLine("  channels:     ${ex.channels}  ${mark(chMatch)}")
            }
            appendLine("————————————————————")
            append(if (ok) "РЕЗУЛЬТАТ: OK ✓ — STREAMINFO упакован корректно."
            else "РЕЗУЛЬТАТ: FAIL ✗ — упаковка/распознавание не сошлись.")
        }
    }

    private data class StreamInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int)

    /**
     * STREAMINFO из собранного `.flac` (RFC 9639): `fLaC`(4) + METADATA_BLOCK_HEADER(4) + тело(34).
     * Тело начинается на offset 8; частота/каналы/битность лежат на абс. offsets 18–21.
     */
    private fun parseStreamInfo(flac: ByteArray): StreamInfo {
        require(flac.size >= 42) { "слишком короткий .flac (${flac.size} B)" }
        fun b(i: Int) = flac[i].toInt() and 0xFF
        val sampleRate = (b(18) shl 12) or (b(19) shl 4) or (b(20) ushr 4)
        val channels = ((b(20) ushr 1) and 0x07) + 1
        val bitsPerSample = (((b(20) and 0x01) shl 4) or (b(21) ushr 4)) + 1
        return StreamInfo(sampleRate, channels, bitsPerSample)
    }

    private fun hasFlacMarker(flac: ByteArray): Boolean =
        flac.size >= 4 && flac[0].toInt() == 0x66 && flac[1].toInt() == 0x4C &&
            flac[2].toInt() == 0x61 && flac[3].toInt() == 0x43

    private data class Extracted(val mime: String, val sampleRate: Int, val channels: Int)

    /** Прогоняет собранный .flac через платформенный MediaExtractor (независимая проверка упаковки). */
    private fun extractWithPlatform(file: File): Extracted? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            if (ex.trackCount == 0) return null
            val fmt = ex.getTrackFormat(0)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: "?"
            val sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else -1
            val ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else -1
            Extracted(mime, sr, ch)
        } catch (e: Exception) {
            null
        } finally {
            ex.release()
        }
    }

    private fun rateLimiter() = RateLimiter(
        nowNanos = System::nanoTime,
        sleeper = { waitNanos ->
            if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
        },
    )

    private fun mark(ok: Boolean) = if (ok) "✓" else "✗"

    private fun log(text: String) = runOnUiThread { out.text = text }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_PICK = 4201
    }
}
