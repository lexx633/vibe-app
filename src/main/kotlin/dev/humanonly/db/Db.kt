package dev.humanonly.db

/**
 * Тонкий порт над SQL-соединением (F3 ТЗ). Отделяет SQL-адаптеры от драйвера: на JVM в тестах —
 * JDBC-SQLite (org.xerial, ловит опечатки в SQL против живой БД), на Android — `SQLiteDatabase`
 * из framework. Обе реализации гоняют один и тот же SQL из [Schema] и адаптеров.
 *
 * Минимальный контракт: DDL/PRAGMA скриптом + параметризованные update/query (только bind-параметры,
 * никакой конкатенации значений — защита от инъекций на границе). Курсор скрыт за [Row].
 */
interface Db {
    /** Выполнить последовательность DDL/PRAGMA-стейтментов (без bind-параметров). */
    fun execScript(statements: List<String>)

    /** INSERT/UPDATE/DELETE с bind-параметрами `?` по порядку. Возвращает число затронутых строк. */
    fun update(sql: String, args: List<Any?> = emptyList()): Int

    /** SELECT с bind-параметрами; каждая строка маппится [map] в доменный тип. */
    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (Row) -> T): List<T>
}

/**
 * Доступ к колонкам одной строки результата по имени. Скрывает `ResultSet`/`Cursor`.
 * Все геттеры nullable — SQL NULL отдаётся как `null`.
 */
interface Row {
    fun string(col: String): String?
    fun long(col: String): Long?
    fun int(col: String): Int?
    fun double(col: String): Double?

    /** INTEGER 0/1 → Boolean (SQLite не имеет отдельного bool). null — если колонка NULL. */
    fun bool(col: String): Boolean?
}

/**
 * Только схема: таблицы + индексы (§F3). Идемпотентна (`IF NOT EXISTS`). БЕЗ PRAGMA — их место
 * зависит от платформы: на Android connection-PRAGMA нельзя внутри транзакции `onCreate`, поэтому
 * там гоняется именно [createSchema], а PRAGMA — в `onConfigure` (см. `CurationOpenHelper`).
 */
fun Db.createSchema() {
    execScript(Schema.CREATE_TABLES)
    execScript(Schema.INDEXES)
}

/** Полная инициализация на свежем соединении ВНЕ транзакции (JVM/тест): PRAGMA + схема (§F3). */
fun Db.initSchema() {
    execScript(Schema.PRAGMA)
    createSchema()
}
