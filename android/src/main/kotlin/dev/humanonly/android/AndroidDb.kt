package dev.humanonly.android

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import dev.humanonly.db.Db
import dev.humanonly.db.Row

/**
 * Реализация [Db]-порта поверх framework SQLite (Android). Зеркало тестового `JdbcDb` на JVM: гоняет
 * тот же SQL из [dev.humanonly.db.Schema] и `Sql*`-адаптеров — логики нет, маппинг 1:1. Паритет с JVM
 * проверяется Robolectric-юнит-тестом (`AndroidDbParityTest`, реальный framework SQLite на JVM).
 */
class AndroidDb(private val db: SQLiteDatabase) : Db {

    override fun execScript(statements: List<String>) {
        // DDL/PRAGMA по одному (PRAGMA journal_mode нельзя внутри транзакции). ВАЖНО: `PRAGMA x=y`
        // может ВОЗВРАЩАТЬ строку (journal_mode → "wal"/"memory"), а framework execSQL это запрещает
        // ("Queries ... using query or rawQuery only") → упало бы на первой инициализации БД. Такой
        // PRAGMA исполняем через rawQuery (он исполняет стейтмент и не против возврата). JVM-порт JdbcDb
        // на это не натыкается: JDBC execute() безразличен к возврату. Паритет — AndroidDbParityTest.
        statements.forEach { sql ->
            if (sql.trimStart().startsWith("PRAGMA", ignoreCase = true) && sql.contains('=')) {
                db.rawQuery(sql, null).use { it.moveToFirst() }
            } else {
                db.execSQL(sql)
            }
        }
    }

    override fun update(sql: String, args: List<Any?>): Int =
        db.compileStatement(sql).use { st ->
            bind(st, args)
            st.executeUpdateDelete()
        }

    override fun <T> query(sql: String, args: List<Any?>, map: (Row) -> T): List<T> =
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
            val out = ArrayList<T>(cursor.count)
            while (cursor.moveToNext()) out += map(CursorRow(cursor))
            out
        }

    private fun bind(st: SQLiteStatement, args: List<Any?>) = args.forEachIndexed { i, a ->
        val p = i + 1
        when (a) {
            null -> st.bindNull(p)
            is Long -> st.bindLong(p, a)
            is Int -> st.bindLong(p, a.toLong())
            is Boolean -> st.bindLong(p, if (a) 1 else 0)
            is Double -> st.bindDouble(p, a)
            is Float -> st.bindDouble(p, a.toDouble())
            is ByteArray -> st.bindBlob(p, a)
            else -> st.bindString(p, a.toString())
        }
    }

    /** Курсор → [Row]: NULL-safe геттеры по имени колонки. */
    private class CursorRow(private val c: Cursor) : Row {
        private fun idx(col: String) = c.getColumnIndexOrThrow(col)
        override fun string(col: String) = idx(col).let { if (c.isNull(it)) null else c.getString(it) }
        override fun long(col: String) = idx(col).let { if (c.isNull(it)) null else c.getLong(it) }
        override fun int(col: String) = idx(col).let { if (c.isNull(it)) null else c.getInt(it) }
        override fun double(col: String) = idx(col).let { if (c.isNull(it)) null else c.getDouble(it) }
        override fun bool(col: String) = int(col)?.let { it != 0 }
    }
}
