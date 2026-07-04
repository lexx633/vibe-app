package dev.humanonly.db

import java.sql.Connection
import java.sql.DriverManager

/**
 * Тестовая реализация [Db] на org.xerial:sqlite-jdbc — РЕАЛЬНЫЙ SQLite в памяти. Гоняет [Schema] DDL
 * и SQL адаптеров против живого движка, ловя опечатки в SQL (чего не даст fake-БД). В APK не входит:
 * на Android тот же [Db]-порт реализуется поверх framework `SQLiteDatabase`.
 *
 * Строки материализуются в Map (курсор не переживает закрытие) → [Row] читает по имени колонки.
 */
class JdbcDb(url: String = "jdbc:sqlite::memory:") : Db, AutoCloseable {
    private val conn: Connection = DriverManager.getConnection(url)

    override fun execScript(statements: List<String>) {
        conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
    }

    override fun update(sql: String, args: List<Any?>): Int =
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeUpdate()
        }

    override fun <T> query(sql: String, args: List<Any?>, map: (Row) -> T): List<T> =
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                val cols = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                val out = ArrayList<T>()
                while (rs.next()) {
                    val values = HashMap<String, Any?>(cols.size)
                    for (c in cols) values[c] = rs.getObject(c)
                    out += map(MapRow(values))
                }
                out
            }
        }

    private fun bind(ps: java.sql.PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
    }

    override fun close() = conn.close()

    private class MapRow(private val v: Map<String, Any?>) : Row {
        override fun string(col: String): String? = v[col]?.toString()
        override fun long(col: String): Long? = (v[col] as? Number)?.toLong()
        override fun int(col: String): Int? = (v[col] as? Number)?.toInt()
        override fun double(col: String): Double? = (v[col] as? Number)?.toDouble()
        override fun bool(col: String): Boolean? = (v[col] as? Number)?.let { it.toInt() != 0 }
    }
}
