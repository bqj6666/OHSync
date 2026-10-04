package io.github.ohsync.hook

import android.database.Cursor
import android.util.Log

/**
 * 只读访问 OPPO 健康的数据库。
 *
 * 这里刻意**不**把实例声明成 androidx.sqlite.db.SupportSQLiteDatabase：
 * 模块和宿主各有自己的一份 androidx.sqlite，跨 ClassLoader 时是两个不同的 Class，
 * 强转/类型判断都会失败。所以实例按 Any 持有，只用反射调 query(String)，
 * 返回的 android.database.Cursor 属于 boot classpath，两边是同一个类，可安全使用。
 *
 * 全程只发 SELECT 与 PRAGMA table_info，不写不改不删。
 */
class TableReader {

    @Volatile private var db: Any? = null

    @Volatile private var ready = false

    private var queryMethod: java.lang.reflect.Method? = null

    private val columnCache = HashMap<String, Set<String>>()

    fun attach(database: Any) {
        if (db != null) return
        val m = try {
            database.javaClass.getMethod("query", String::class.java)
        } catch (t: Throwable) {
            Log.e(TAG, "实例上没有 query(String) 方法：${database.javaClass.name}", t)
            return
        }
        db = database
        queryMethod = m
        ready = true
        Log.i(TAG, "已挂接数据库实例：${database.javaClass.name}")
        Log.i(TAG, "表数量 = ${tableNames().size}")
    }

    fun isReady(): Boolean = ready

    /** 真实表名清单，来自 sqlite_master —— 表名的权威来源，比从 dex 推断可靠。 */
    fun tableNames(): List<String> =
        query(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        ).mapNotNull { it["name"] }

    /** 某张表的真实列名集合，来自 PRAGMA table_info。 */
    fun columns(table: String): Set<String> = columnCache.getOrPut(table) {
        query("PRAGMA table_info(`${table.safeIdent()}`)").mapNotNull { it["name"] }.toSet()
    }

    /** 只读查询，返回每行的列名 -> 字符串值。 */
    fun query(sql: String): List<Map<String, String?>> {
        val d = db
        val m = queryMethod
        if (d == null || m == null) return emptyList()
        var cursor: Cursor? = null
        return try {
            cursor = m.invoke(d, sql) as? Cursor ?: return emptyList()
            val cols = cursor.columnNames
            val out = ArrayList<Map<String, String?>>(cursor.count)
            while (cursor.moveToNext()) {
                val row = LinkedHashMap<String, String?>(cols.size)
                for (i in cols.indices) {
                    row[cols[i]] = if (cursor.isNull(i)) null else cursor.getString(i)
                }
                out.add(row)
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "查询失败: ${sql.take(90)}", t)
            emptyList()
        } finally {
            try {
                cursor?.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TAG = "OHSyncRead"

        /** 单表读取上限，防止一次性把整库读进内存。 */
        const val MAX_ROWS = 20000

        /** 表名/列名只允许字母数字下划线，杜绝拼接注入面。 */
        fun String.safeIdent(): String = replace(Regex("[^A-Za-z0-9_]"), "")
    }
}
