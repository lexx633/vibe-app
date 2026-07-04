package dev.humanonly.android

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.humanonly.review.ReviewDecision
import dev.humanonly.review.ReviewItem
import dev.humanonly.review.ReviewQueue

/**
 * Экран ревью-очереди (§F4-UI). Человек проходит серую зону детекции (`review_required`) и по каждому
 * треку жмёт **«ИИ»** (→ ai_confirmed, дальше авто-дизлайк по режиму) или **«не ИИ»** (→ human_confirmed,
 * whitelist). Именно так штатно рождается `ai_confirmed` — детект по метаданным сам его не ставит.
 *
 * Логики нет — только [ReviewQueue] из [ServiceLocator], оттестированный на JVM. title/artist рендерятся
 * из локального индекса как подсказка человеку (display-only, §12); в audit уходят лишь id/переход/ts.
 * БД-операции — вне UI-потока (как в [MainActivity]).
 */
class ReviewActivity : Activity() {

    private lateinit var queue: ReviewQueue
    private lateinit var header: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = ServiceLocator.reviewQueue(this)
        setContentView(buildUi())
        refresh()
    }

    private fun buildUi(): ScrollView {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Ревью серой зоны"
            textSize = 22f
            setPadding(0, 0, 0, dp(8))
        })

        header = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(header)

        root.addView(button("Обновить") { refresh() })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        return ScrollView(this).apply { addView(root) }
    }

    /** Читает первую страницу очереди вне UI-потока и перерисовывает список. */
    private fun refresh() {
        header.text = "Загрузка…"
        list.removeAllViews()
        Thread {
            val page = runCatching { queue.page(limit = PAGE_LIMIT) }.getOrNull()
            runOnUiThread {
                if (page == null) {
                    header.text = "Не удалось прочитать очередь"
                    return@runOnUiThread
                }
                header.text = if (page.total == 0) {
                    "Очередь пуста — треков на ревью нет"
                } else {
                    "На ревью: ${page.total}${if (page.hasMore) " (показаны первые ${page.items.size})" else ""}"
                }
                page.items.forEach { list.addView(itemRow(it)) }
            }
        }.start()
    }

    /** Строка трека: подпись (title/artist/score) + две кнопки решения. */
    private fun itemRow(item: ReviewItem): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(TextView(this).apply {
            text = renderLabel(item)
            textSize = 14f
            setTextIsSelectable(true)
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("ИИ") { decide(item, ReviewDecision.AI) }, rowWeight())
        buttons.addView(button("не ИИ") { decide(item, ReviewDecision.NOT_AI) }, rowWeight())
        row.addView(buttons)
        return row
    }

    private fun renderLabel(item: ReviewItem): String {
        val name = listOfNotNull(item.artist, item.title).joinToString(" — ").ifBlank { item.trackId }
        val score = item.metaScore?.let { " · score=%.2f".format(it) } ?: ""
        return "$name$score"
    }

    /** Отправляет решение в [ReviewQueue] вне UI-потока; при успехе перечитывает очередь. */
    private fun decide(item: ReviewItem, decision: ReviewDecision) {
        Thread {
            val outcome = runCatching {
                queue.decide(item.trackId, item.currentState, decision)
            }.getOrNull()
            runOnUiThread {
                when {
                    outcome == null -> toast("Ошибка записи решения")
                    outcome.accepted -> {
                        toast(if (decision == ReviewDecision.AI) "Помечен ИИ" else "Обелён")
                        refresh()
                    }
                    else -> toast("Отклонено: ${outcome.refusedReason}")
                }
            }
        }.start()
    }

    private fun rowWeight() =
        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val PAGE_LIMIT = 50
    }
}
