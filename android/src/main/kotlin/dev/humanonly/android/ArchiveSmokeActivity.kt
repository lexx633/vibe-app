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
import dev.humanonly.archive.Archiver
import dev.humanonly.archive.FsLocalStore
import dev.humanonly.archive.YandexDiskBlobStore
import dev.humanonly.archive.YandexDiskClient
import dev.humanonly.archive.YandexDiskManifestStore
import dev.humanonly.pipeline.DownloadCandidate
import dev.humanonly.pipeline.DownloadStage
import dev.humanonly.pipeline.YandexTrackFetcher
import dev.humanonly.yandex.FlacArchivePreparer
import dev.humanonly.yandex.RateLimiter
import dev.humanonly.yandex.TrackDownloader
import java.security.MessageDigest

/**
 * Живой on-device end-to-end smoke §F6 (§7 шаги 3–4) — БЕЗ adb: composит РЕАЛЬНЫЕ продакшн-стадии
 * [DownloadStage] (get-file-info → скачивание → AES-CTR → демукс Media3 → сырой `.flac` → локальная
 * запись) и [Archiver] (дедуп по хэшу → заливка блоба на Яндекс.Диск → manifest.json → удаление
 * локального) против настоящего Диска. Доказывает, что «скачал → демукснул → положил в архив» работает
 * на живом железе цельной цепочкой, а не по кускам.
 *
 * Два режима (паритет с live-инструментами LiveDiskArchive/LiveS3Archive):
 *   - «§F6 dry-run» — проверяет доступ к Диску (HEAD-проба несуществующего блоба: токен/подпись приняты),
 *     НИЧЕГО не качает и не заливает.
 *   - «§F6 + заливка» — реальный прогон одного трека: скачивание → архивация на Диск → верификация
 *     (блоб докачан обратно, sha256 совпал). Файл ОСТАЁТСЯ на Диске (архив аддитивен — не деструктив).
 *
 * Токены (хард-правило 3/4): токен ЯМ — из [ServiceLocator.tokenStore] (положен в MainActivity, тест-акк);
 * токен Диска вводится здесь в password-поле, НЕ логируется и НЕ сохраняется (живёт только на время прогона).
 * Rate-limit к ЯМ реальный (хард-правило 7). Заливка на СВОЙ Диск аддитивна; запускается только явным
 * тапом по кнопке (это и есть подтверждение на устройстве).
 */
class ArchiveSmokeActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var trackInput: EditText
    private lateinit var diskTokenInput: EditText

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
            text = "Архив §F6 smoke (Яндекс.Диск)"
            textSize = 20f
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Скачать lossless из ЯМ → демукс → залить .flac на Диск (реальная цепочка DownloadStage+Archiver)."
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        })

        trackInput = EditText(this).apply {
            hint = "trackId (необязательно; пусто = первый лайк)"
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(trackInput)

        diskTokenInput = EditText(this).apply {
            hint = "OAuth-токен Диска (пусто = запечённый в APK)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(diskTokenInput)

        root.addView(button("§F6 dry-run (проверить доступ к Диску)") { onDryRun() })
        root.addView(button("§F6 + заливка на Диск") { onExecute() })

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

    private fun onDryRun() {
        val (diskToken, source) = resolveDiskToken()
        if (diskToken.isBlank()) { log("Нет токена Диска: поле пусто и ассет yadisk.token не запечён."); return }
        log("Проверка доступа к Диску ($source)…")
        Thread {
            val report = runCatching {
                val blobs = diskBlobStore(diskToken)
                val probe = Archiver.archivePath("0".repeat(64), "flac")
                val exists = blobs.exists(probe) // 404 → false (норм), исключение → нет доступа
                "доступ к Диску: OK (пробный блоб есть=$exists) — токен принят.\n" +
                    "Готов к заливке. Жми «§F6 + заливка на Диск»."
            }.getOrElse { "доступ к Диску: FAIL — ${it.message}" }
            log(report)
        }.start()
    }

    private fun onExecute() {
        val (diskToken, source) = resolveDiskToken()
        if (diskToken.isBlank()) { log("Нет токена Диска: поле пусто и ассет yadisk.token не запечён."); return }
        val trackId = trackInput.text?.toString()?.trim().orEmpty()
        log("§F6 ($source): скачивание → демукс → заливка на Диск…")
        Thread {
            val report = runCatching { runEndToEnd(trackId, diskToken) }
                .getOrElse { "ОШИБКА: ${it.message}" }
            log(report)
        }.start()
    }

    private fun runEndToEnd(trackIdInput: String, diskToken: String): String {
        val ymToken = ServiceLocator.tokenStore(this).load()
            ?: return "Нет токена ЯМ. Положи токен в MainActivity (тестовый акк)."
        val client = ServiceLocator.yandexClient(ymToken)
        val trackId = trackIdInput.ifBlank {
            val uid = client.accountUid().toString()
            client.likedTrackIds(uid).firstOrNull()
                ?: return "В лайках пусто — укажи trackId вручную."
        }

        // Локальное хранилище скачанных .flac — в cacheDir (Archiver удалит после подтверждённой заливки).
        val local = FsLocalStore(cacheDir.toPath())
        val fetcher = YandexTrackFetcher(
            client = client,
            downloader = TrackDownloader(client, AndroidHttpTransport(), rateLimiter()),
        )
        val downloadStage = DownloadStage(fetcher, FlacArchivePreparer(Media3FlacDemuxer()), local)

        // ── стадия 3: download → prepare → write local ───────────────────────
        val candidate = DownloadCandidate(trackId = trackId, verdict = "clean", detectorVersion = "live-smoke")
        val dl = downloadStage.run(listOf(candidate))
        val dlItem = dl.items.singleOrNull()
        val sb = StringBuilder()
        sb.appendLine("trackId:          $trackId")
        sb.appendLine("download:         downloaded=${dl.downloaded} failed=${dl.failed} reason=${dlItem?.reason ?: "-"}")
        val archiveCandidate = dl.archiveCandidates.singleOrNull()
            ?: return sb.append("СТОП: трек не скачан/не подготовлен (см. reason выше).").toString()
        sb.appendLine("подготовлен .flac: hash=${archiveCandidate.hash.take(12)}… codec=${archiveCandidate.codec}")

        // ── стадия 4: archive → Диск (blob + manifest.json), удаление локального ──
        val blobs = diskBlobStore(diskToken)
        val manifest = YandexDiskManifestStore(diskClient(diskToken))
        val archiver = Archiver(blobs, local, manifest)
        val summary = archiver.run(listOf(archiveCandidate))
        val item = summary.items.single()
        sb.appendLine("archive:          status=${item.status} path=${item.archivePath ?: "-"} reason=${item.reason ?: "-"}")

        // ── верификация: блоб реально на Диске, sha256 совпал ────────────────
        val path = Archiver.archivePath(archiveCandidate.hash, archiveCandidate.codec)
        val back = blobs.get(path)
        val ok = back != null && sha256(back) == archiveCandidate.hash
        sb.appendLine("verify (download): ${if (ok) "OK (${back!!.size}B, sha256 совпал)" else "FAIL"}")
        sb.appendLine("————————————————————")
        sb.append(
            if (ok) "РЕЗУЛЬТАТ: OK ✓ — .flac залит на Диск ($path) и верифицирован. Файл остаётся в архиве."
            else "РЕЗУЛЬТАТ: FAIL ✗ — заливка/верификация не сошлись.",
        )
        return sb.toString()
    }

    /**
     * Источник токена Диска: приоритет у поля ввода (override для другого акка), иначе — запечённый в
     * APK ассет `assets/yadisk.token` (gitignored, в публичный репо/исходники НЕ попадает — хард-правило 4).
     * Возвращает (токен, человекочитаемый источник для лога — БЕЗ самого токена).
     */
    private fun resolveDiskToken(): Pair<String, String> {
        val typed = diskTokenInput.text?.toString()?.trim().orEmpty()
        if (typed.isNotBlank()) return typed to "из поля"
        val baked = bakedDiskToken().orEmpty()
        return baked to if (baked.isNotBlank()) "запечён в APK" else "нет"
    }

    /** Токен Диска из запечённого ассета (см. [BakedTokens]). Токен НЕ логируется (хард-правило 4). */
    private fun bakedDiskToken(): String? = BakedTokens.yandexDisk(this)

    private fun diskClient(token: String) = YandexDiskClient(AndroidDiskHttp(token))
    private fun diskBlobStore(token: String) = YandexDiskBlobStore(diskClient(token))

    private fun rateLimiter() = RateLimiter(
        nowNanos = System::nanoTime,
        sleeper = { waitNanos ->
            if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
        },
    )

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun log(text: String) = runOnUiThread { out.text = text }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
