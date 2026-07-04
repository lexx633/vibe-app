package dev.humanonly.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import dev.humanonly.schedule.DeviceState

/**
 * Снимок системных сигналов устройства → [DeviceState] (карта для [dev.humanonly.schedule.RunConstraints]).
 * Логики нет — только чтение системных сервисов; WorkManager уже отфильтровал по `Constraints`, это
 * защитный повтор гейта в теле воркера (§4.1).
 */
internal fun readDeviceState(ctx: Context): DeviceState {
    val app = ctx.applicationContext
    val cm = app.getSystemService(ConnectivityManager::class.java)
    val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    // metered = НЕ помечена как NOT_METERED (нет сети → считаем лимитной, консервативно).
    val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true

    val bm = app.getSystemService(BatteryManager::class.java)
    val charging = bm?.isCharging ?: false
    val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    val batteryLow = batteryPct in 0..LOW_BATTERY_PCT

    val pm = app.getSystemService(PowerManager::class.java)
    val idle = pm?.isDeviceIdleMode ?: true

    return DeviceState(metered = metered, charging = charging, batteryLow = batteryLow, idle = idle)
}

/** Порог «низкого заряда» для батарейного гейта (WorkManager `setRequiresBatteryNotLow` ~15%). */
private const val LOW_BATTERY_PCT = 15
