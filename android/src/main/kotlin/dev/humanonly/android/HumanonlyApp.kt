package dev.humanonly.android

import android.app.Application
import androidx.work.Configuration

/**
 * Точка входа приложения (§4.1). Единственная задача — при старте процесса поставить периодическую
 * работу куратора в WorkManager из оттестированного [ServiceLocator.schedule]. Логики прогона здесь
 * НЕТ: планирование делегируется [WorkScheduler] (маппинг `RunSchedule`→WorkManager, тест на JVM),
 * а сам прогон — [CurationWorker]→[dev.humanonly.schedule.RunScheduler].
 *
 * **On-demand инициализация WorkManager** (implements [Configuration.Provider] + default-initializer
 * снят в манифесте): первый `WorkManager.getInstance` лениво поднимает движок из [workManagerConfiguration].
 * Так корректнее startup-провайдера — работает и в проде, и в Robolectric-юнит-тестах (где androidx.startup
 * не гоняется), поэтому `AndroidDbParityTest` с этим Application не падает на неинициализированном WM.
 *
 * `enqueueUniquePeriodicWork(..., KEEP, ...)` идемпотентна: повторный старт процесса НЕ пересоздаёт
 * уже запланированную работу (переустановка расписания — только при смене полей, отдельный чанк).
 */
class HumanonlyApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        // Токен на этом этапе не нужен: без него live-стадии пропускаются (ServiceLocator).
        WorkScheduler.schedule(this, ServiceLocator.schedule)
    }
}
