package dev.humanonly.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.humanonly.db.Db
import dev.humanonly.db.Schema
import dev.humanonly.db.createSchema

/**
 * Открывает/создаёт индекс SQLite (F3) на устройстве. Connection-PRAGMA (§F3) ставятся в `onConfigure`
 * (вне транзакции), а `onCreate` прогоняет ТОЛЬКО DDL — таблицы + 4 индекса из [dev.humanonly.db.Schema]
 * через [AndroidDb], тот же, что тестируется на JVM. Разделение критично: `getWritableDatabase()`
 * оборачивает `onCreate` в транзакцию, а движок запрещает менять journal_mode/synchronous внутри неё.
 * Миграции пойдут в `onUpgrade` при росте `schema_version` (ТЗ §13).
 */
class CurationOpenHelper(
    context: Context,
) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, SCHEMA_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        // Вне транзакции: WAL — официальным API; остальные connection-PRAGMA (synchronous/temp_store §F3).
        db.enableWriteAheadLogging()
        AndroidDb(db).execScript(Schema.CONNECTION_PRAGMA)
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Внутри транзакции OpenHelper: ТОЛЬКО DDL (таблицы+индексы). PRAGMA journal_mode/synchronous
        // внутри транзакции запрещены → они в onConfigure (WAL — через enableWriteAheadLogging).
        val port: Db = AndroidDb(db)
        port.createSchema()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Пока единственная версия. Ломающее изменение схемы = новый SCHEMA_VERSION + миграция здесь (§13).
    }

    companion object {
        const val DB_NAME = "humanonly-index.db"
        const val SCHEMA_VERSION = 1
    }
}
