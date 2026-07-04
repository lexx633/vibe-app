package dev.humanonly.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.humanonly.db.Db
import dev.humanonly.db.initSchema

/**
 * Открывает/создаёт индекс SQLite (F3) на устройстве. `onCreate` прогоняет [initSchema] (PRAGMA WAL/
 * synchronous/temp_store + таблицы + 4 индекса из [dev.humanonly.db.Schema]) через [AndroidDb] — тот же
 * DDL, что тестируется на JVM. Миграции пойдут в `onUpgrade` при росте `schema_version` (ТЗ §13).
 */
class CurationOpenHelper(
    context: Context,
) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, SCHEMA_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        // WAL включаем через официальный API до onCreate/onOpen (PRAGMA journal_mode из скрипта дублирует намерение).
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        val port: Db = AndroidDb(db)
        port.initSchema()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Пока единственная версия. Ломающее изменение схемы = новый SCHEMA_VERSION + миграция здесь (§13).
    }

    companion object {
        const val DB_NAME = "humanonly-index.db"
        const val SCHEMA_VERSION = 1
    }
}
