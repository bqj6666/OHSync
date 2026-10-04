package io.github.ohsync.hook

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 只读访问 OPPO 健康的数据库。
 *
 * 关键设计：不自己引入 SQLCipher、不自己解密。直接复用 OPPO 已经打开好的
 * SupportSQLiteDatabase 实例 —— 通过 hook
 * com.heytap.databaseengineservice.db.SerialFirstOpenHelper.getReadableDatabase()
 * 在它首次打开数据库的瞬间把实例截下来。
 *
 * 这样做的原因：
 *   - 自己再打进一份 libsqlcipher 会和 OPPO 进程里已加载的同符号库冲突
 *   - 自己复制 138MB 主库再解密，慢、占空间、还多一份口令的存活期
 *   - 复用现成实例天然共用同一套加密实现，零额外依赖
 *
 * 全程只发 SELECT 与 PRAGMA table_info，不写不改不删。
 */
class TableReader {

    @Volatile private var db: SupportSQLiteDatabase? = null

    @Volatile private var ready = false

    private val columnCache = HashMap<String, Set<String>>()

    fun attach(database: SupportSQLiteDatabase) {
        if (db != null) return
        db = database
        ready = true
        Log.i(TAG, "已挂接 OPPO 已打开的数据库实例：${database.path}")
        Log.i(TAG, "表数量 = ${tableNames().size}")
    }

    fun isReady(): Boolean = ready

    /**
     * 真实表名清单，来自 sqlite_master。
     *
     * 这是表名的权威来源 —— 比从 dex 里推断可靠得多：OPPO 改内部类名不影响它，
     * 而且能拿到运行时动态建的表。
     */
    fun tableNames(): List<String> {
        val d = db ?: return emptyList()
        return try {
            d.query(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%' ORDER BY name"
            ).use { c ->
                val out = ArrayList<String>(c.count)
                while (c.moveToNext()) c.getString(0)?.let { out.add(it) }
                out
            }
        } catch (t: Throwable) {
            Log.e(TAG, "枚举表名失败", t)
            emptyList()
        }
    }

    /** 某张表的真实列名集合，来自 PRAGMA table_info。 */
    fun columns(table: String): Set<String> = columnCache.getOrPut(table) {
        val d = db ?: return@getOrPut emptySet()
        try {
            d.query("PRAGMA table_info(`${table.safeIdent()}`)").use { c ->
                val nameIdx = c.getColumnIndex("name")
                val out = HashSet<String>()
                while (c.moveToNext()) {
                    if (nameIdx >= 0) c.getString(nameIdx)?.let { out.add(it) }
                }
                out
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取 $table 列信息失败", t)
            emptySet()
        }
    }

    /** 只读查询，返回每行的列名->字符串值。 */
    fun query(sql: String): List<Map<String, String?>> {
        val d = db ?: return emptyList()
        return try {
            d.query(sql).use { c ->
                val cols = c.columnNames
                val out = ArrayList<Map<String, String?>>(c.count)
                while (c.moveToNext()) {
                    val row = LinkedHashMap<String, String?>(cols.size)
                    for (i in cols.indices) {
                        row[cols[i]] = if (c.isNull(i)) null else c.getString(i)
                    }
                    out.add(row)
                }
                out
            }
        } catch (t: Throwable) {
            Log.w(TAG, "查询失败: ${sql.take(90)}", t)
            emptyList()
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
