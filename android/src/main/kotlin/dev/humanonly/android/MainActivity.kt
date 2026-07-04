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
import android.widget.Toast
import androidx.work.WorkInfo
import androidx.work.WorkManager

/**
 * Единственный экран MVP (§4.1). Не «продукт», а операторская панель sideload-сборки: положить/стереть
 * OAuth-токен ЯМ, дёрнуть прогон вручную, увидеть статус. Логики здесь нет — только вызовы
 * [WorkScheduler]/[ServiceLocator]/[TokenStore], всё уже оттестировано на JVM.
 *
 * Хард-правило 4: токен в UI НИКОГДА не показываем — только безопасный отпечаток
 * ([TokenStore.fingerprint]: длина + sha256). Поле ввода — `textPassword`, после сохранения очищается.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var tokenInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        seedTestTokenIfEmpty()
        refresh()
    }

    /**
     * Тест-режим: если в Keystore токена ЯМ ещё нет, подставляем запечённый в APK тестовый токен
     * ([BakedTokens], gitignored-ассет) — чтобы не вводить руками на устройстве (хард-правило 3: тест-акк).
     * Токен НЕ показывается и НЕ логируется (хард-правило 4) — только кладётся в store; в статусе будет
     * лишь отпечаток. Если токен уже положен вручную — не трогаем.
     */
    private fun seedTestTokenIfEmpty() {
        val store = runCatching { ServiceLocator.tokenStore(this) }.getOrNull() ?: return
        val alreadyHas = runCatching { store.load() != null }.getOrDefault(false)
        if (alreadyHas) return
        val baked = BakedTokens.yandexMusic(this) ?: return
        runCatching { store.save(baked) }
    }

    private fun buildUi(): ScrollView {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "humanonly"
            textSize = 22f
            setPadding(0, 0, 0, dp(8))
        })

        status = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(status)

        tokenInput = EditText(this).apply {
            hint = "OAuth-токен ЯМ (тестовый акк)"
            // Не показываем вводимый токен на экране (хард-правило 4).
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(tokenInput)

        root.addView(button("Сохранить токен") { onSaveToken() })
        root.addView(button("Очистить токен") { onClearToken() })
        root.addView(button("Запустить прогон сейчас") { onRunNow() })
        root.addView(button("Проверить трек на ИИ (детект-smoke)") { onOpenDetect() })
        root.addView(button("Чистка библиотеки (мёртвые + ИИ)") { onOpenCleanup() })
        root.addView(button("Ревью серой зоны") { onOpenReview() })
        root.addView(button("Media3 FLAC smoke") { onOpenSmoke() })
        root.addView(button("Архив §F6 smoke (Диск)") { onOpenArchiveSmoke() })
        root.addView(button("Обновить статус") { refresh() })

        return ScrollView(this).apply { addView(root) }
    }

    private fun onSaveToken() {
        val token = tokenInput.text?.toString().orEmpty()
        if (token.isBlank()) {
            toast("Пустой токен")
            return
        }
        runCatching { ServiceLocator.tokenStore(this).save(token) }
            .onSuccess {
                tokenInput.setText("") // не держим плейнтекст в UI (хард-правило 4)
                toast("Токен сохранён")
                refresh()
            }
            .onFailure { toast("Не удалось сохранить токен") }
    }

    private fun onClearToken() {
        runCatching { ServiceLocator.tokenStore(this).clear() }
            .onSuccess { toast("Токен стёрт"); refresh() }
            .onFailure { toast("Не удалось стереть токен") }
    }

    private fun onRunNow() {
        WorkScheduler.runNow(this)
        toast("Прогон поставлен в очередь")
        refresh()
    }

    private fun onOpenDetect() = startActivity(android.content.Intent(this, DetectSmokeActivity::class.java))

    private fun onOpenCleanup() = startActivity(android.content.Intent(this, CleanupActivity::class.java))

    private fun onOpenReview() = startActivity(android.content.Intent(this, ReviewActivity::class.java))

    private fun onOpenSmoke() = startActivity(android.content.Intent(this, SmokeActivity::class.java))

    private fun onOpenArchiveSmoke() = startActivity(android.content.Intent(this, ArchiveSmokeActivity::class.java))

    /** Собирает статус вне UI-потока (Keystore + Future WorkManager) и постит в [status]. */
    private fun refresh() {
        status.text = "Загрузка статуса…"
        Thread {
            val fp = runCatching { ServiceLocator.tokenStore(this).fingerprint() }.getOrNull()
            val work = runCatching {
                WorkManager.getInstance(this)
                    .getWorkInfosForUniqueWork(WorkScheduler.UNIQUE_WORK_NAME).get()
                    .firstOrNull()?.state
            }.getOrNull()
            val text = renderStatus(fp, work)
            runOnUiThread { status.text = text }
        }.start()
    }

    private fun renderStatus(fingerprint: String?, work: WorkInfo.State?): String {
        val s = ServiceLocator.schedule
        val tokenLine = if (fingerprint != null) "есть ($fingerprint)" else "нет — live-стадии пропускаются"
        val everyHours = s.intervalMs / (60 * 60 * 1000)
        return buildString {
            appendLine("Токен ЯМ: $tokenLine")
            appendLine("Расписание: каждые $everyHours ч, только Wi-Fi, батарея не низкая")
            appendLine("Состояние периодической работы: ${work ?: "не запланирована"}")
        }.trimEnd()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
