# Android-адаптеры планировщика и БД — референс + реальный модуль `:android`

Корневой модуль (`:`) — чистый `kotlin("jvm")`: вся логика framework-agnostic и покрыта JVM-тестами.
Android-склейка (androidx.work + framework SQLite) живёт в реальном Gradle-модуле **`android/`** (AGP
application). Он собирается только с Android SDK/AGP — JVM-CI его не трогает (`:android` включается в
`settings.gradle.kts` лишь при наличии `local.properties`). Адаптеры лишь мостят уже оттестированные
`RunScheduler` и `Db`-порт в Android API. Логики в них нет — значит и тестировать в них нечего (маппинг 1:1);
паритет `AndroidDb` с `JdbcDb` проверит instrumented-тест (androidTest, отдельный чанк).

Реальные файлы (`android/src/main/kotlin/dev/humanonly/android/`): `AndroidDb`, `CurationOpenHelper`,
`CurationWorker`, `WorkScheduler`, `DeviceStateReader`, `ServiceLocator`. Сниппеты ниже — пояснение к ним.

Тулчейн (проверено, `./gradlew :android:assembleDebug` зелёный): AGP **8.13.0**, Gradle 8.13, Kotlin 2.0.21,
`androidx.work:work-runtime-ktx:2.9.1`, minSdk 26, **compileSdk 36** (Android 16, buildTools 36.0.0). compileSdk 37
(Android 17) использует новую minor-versioned схему платформы (папка `android-37.0`, SDK XML v4), которую читает
только AGP 9.1+ → Gradle 9.3.1 + Kotlin 2.2.10 (мажор, пересборка верифицированного core новым компилятором) —
отложено осознанно. Для тонкого адаптера разница API 36↔37 несущественна.

## 1. CoroutineWorker поверх RunScheduler

`RunScheduler.execute(deviceState, attempt, nowMs)` уже решает, что делать по итогу прогона
([NextAction]). Воркер только собирает `DeviceState`, зовёт тело и маппит `NextAction` → `Result`.

```kotlin
class CurationWorker(
    appContext: Context,
    params: WorkerParameters,
    private val scheduler: RunScheduler,      // собран из CurationRun + RunSchedule (DI/ServiceLocator)
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val device = readDeviceState(applicationContext)      // системные сигналы → DeviceState
        val res = scheduler.execute(device, attempt = runAttemptCount + 1, nowMs = System.currentTimeMillis())
        when (val next = res.next) {
            is NextAction.Reschedule -> Result.success()      // PeriodicWorkRequest сам перезапустит
            is NextAction.Retry      -> Result.retry()        // backoff задан в PeriodicWorkRequest ниже
            NextAction.Drop          -> Result.failure()
        }
    }
}

/** Снимок устройства из системных сигналов (карта в RunConstraints). */
private fun readDeviceState(ctx: Context): DeviceState {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
    val bm = ctx.getSystemService(BatteryManager::class.java)
    val charging = bm.isCharging
    // batteryLow/idle — из BatteryManager / PowerManager.isDeviceIdleMode
    return DeviceState(metered = metered, charging = charging, batteryLow = /*…*/ false, idle = /*…*/ true)
}
```

Планирование периодической работы (поля берём 1:1 из `RunSchedule`/`RunConstraints`/`BackoffPolicy`):

```kotlin
fun schedule(ctx: Context, s: RunSchedule) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(if (s.constraints.requiresUnmeteredNetwork) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(s.constraints.requiresCharging)
        .setRequiresBatteryNotLow(s.constraints.requiresBatteryNotLow)
        .setRequiresDeviceIdle(s.constraints.requiresDeviceIdle)
        .build()

    val request = PeriodicWorkRequestBuilder<CurationWorker>(
        s.intervalMs, TimeUnit.MILLISECONDS,
        s.flexMs.coerceAtLeast(1), TimeUnit.MILLISECONDS,
    )
        .setConstraints(constraints)
        .setBackoffCriteria(
            if (s.backoff.kind == BackoffKind.EXPONENTIAL) BackoffPolicy.EXPONENTIAL else BackoffPolicy.LINEAR,
            s.backoff.initialDelayMs, TimeUnit.MILLISECONDS,
        )
        .build()

    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        "humanonly-curation", ExistingPeriodicWorkPolicy.KEEP, request,
    )
}
```

> Примечание: у WorkManager backoff завязан на `runAttemptCount` внутри одного work; `RunScheduler` возвращает
> ту же величину (`BackoffPolicy.delayForAttemptMs`) для логирования/UI «когда следующая попытка».

## 2. Db поверх framework SQLite

`dev.humanonly.db.Db`/`Row` — тонкий порт. На JVM в тестах он реализован через JDBC (`JdbcDb`), на Android —
через `android.database.sqlite.SQLiteDatabase`. Оба гоняют один SQL из `Schema` и SQL-адаптеров.

```kotlin
class AndroidDb(private val db: SQLiteDatabase) : Db {

    override fun execScript(statements: List<String>) {
        db.beginTransaction()
        try { statements.forEach(db::execSQL); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }

    override fun update(sql: String, args: List<Any?>): Int {
        db.compileStatement(sql).use { st ->
            bind(st, args)
            return st.executeUpdateDelete()
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (Row) -> T): List<T> {
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
            val out = ArrayList<T>()
            while (cursor.moveToNext()) out += map(CursorRow(cursor))
            return out
        }
    }

    private fun bind(st: SQLiteStatement, args: List<Any?>) = args.forEachIndexed { i, a ->
        when (a) {
            null -> st.bindNull(i + 1)
            is Long -> st.bindLong(i + 1, a)
            is Int -> st.bindLong(i + 1, a.toLong())
            is Double -> st.bindDouble(i + 1, a)
            else -> st.bindString(i + 1, a.toString())
        }
    }

    private class CursorRow(private val c: Cursor) : Row {
        private fun idx(col: String) = c.getColumnIndexOrThrow(col)
        override fun string(col: String) = idx(col).let { if (c.isNull(it)) null else c.getString(it) }
        override fun long(col: String) = idx(col).let { if (c.isNull(it)) null else c.getLong(it) }
        override fun int(col: String) = idx(col).let { if (c.isNull(it)) null else c.getInt(it) }
        override fun double(col: String) = idx(col).let { if (c.isNull(it)) null else c.getDouble(it) }
        override fun bool(col: String) = int(col)?.let { it != 0 }
    }
}
```

> `SQLiteOpenHelper.onCreate` вызывает `AndroidDb(db).initSchema()` (PRAGMA WAL/synchronous/temp_store из
> `Schema.PRAGMA` + таблицы + 4 индекса). Дальше `SqlScanSource`/`SqlVerdictSink`/`SqlActionQueue`/… работают
> без изменений — они зависят только от `Db`, а не от драйвера.

## Статус `:android` модуля

Сделано (`./gradlew :android:assembleDebug` → `android-debug.apk` зелёный, core дексуется):
- AGP + модуль `android/` + `settings.gradle.kts` (условный `include(":android")` по `local.properties`).
- `AndroidDb` (Db-порт поверх `SQLiteDatabase`), `CurationOpenHelper` (`onCreate → initSchema`).
- `CurationWorker : CoroutineWorker` (маппинг `NextAction → Result`), `WorkScheduler.schedule()`.
- `readDeviceState` (Connectivity/Battery/Power → `DeviceState`).
- `ServiceLocator` (MVP: gate+метаданные, `SqlScanSource`/`SqlVerdictSink`; auto_dislike/archive off).

Остаётся (отдельные чанки, нужен ДА):
- Отдельный CI-джоб `assembleDebug` (с Android SDK) — JVM-CI это не собирает.
- Полный DI: `YandexConfig` (токен из EncryptedSharedPreferences), `YandexClient` + Android-транспорт
  (OkHttp/HttpURLConnection — `java.net.http` на Android нет), `ActionDispatcher`, `Archiver`, `restore`.
- Загрузка базы slopless в рантайме (assets/скачивание, GPL — не вендорить), `MetaResolver` из DTO ЯМ.
- Апгрейд AGP+Gradle → вернуть compileSdk 37 (minor-versioned платформа).
- Instrumented-тест (androidTest) на устройстве/эмуляторе: `AndroidDb` против `Schema` (паритет с `JdbcDb`).
- UI (обзор/review-очередь), точка входа (`Activity`/`Application`, регистрация `WorkScheduler.schedule`).
