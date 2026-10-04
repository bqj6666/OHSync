package io.github.ohsync.hook

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 只读访问 OPPO 健康的数据库。
 *
 * 关键设计：不自己引入 SQLCipher、不自己解密。
 * 直接复用 OPPO 已经打开好的 SupportSQLiteDatabase 实例 —— 通过 hook
 * com.heytap.databaseengineservice.db.SerialFirstOpenHelper.getReadableDatabase()
 * 在它首次打开数据库的瞬间把实例截下来。
 *
 * 这样做的原因：
 *   - 自己再打进一份 libsqlcipher 会和 OPPO 进程里已加载的同符号库冲突
 *   - 自己复制 138MB 主库再解密，慢、占空间、还多一份口令的存活期
 *   - 复用现成实例天然共用同一套加密实现，零额外依赖
 *
 * 全程只发 SELECT，不写不改不删。
 */
class TableReader {

    @Volatile private var db: SupportSQLiteDatabase? = null

    @Volatile private var ready = false

    fun attach(database: SupportSQLiteDatabase) {
        if (db != null) return
        db = database
        ready = true
        Log.i(TAG, "已挂接 OPPO 已打开的数据库实例：${database.path}")
        runCatching {
            database.query("SELECT count(*) FROM sqlite_master WHERE type='table'").use { c ->
                c.moveToFirst()
                Log.i(TAG, "表数量 = ${c.getInt(0)}")
            }
        }.onFailure { Log.e(TAG, "统计表数量失败", it) }
    }

    fun isReady(): Boolean = ready

    fun tableNames(): List<String> {
        val d = db ?: return emptyList()
        return runCatching {
            d.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'android_%' " +
                    "AND name NOT LIKE 'sqlite_%' ORDER BY name"
            ).use { c ->
                val out = ArrayList<String>()
                while (c.moveToNext()) out.add(c.getString(0))
                out
            }
        }.getOrElse {
            Log.e(TAG, "枚举表名失败", it); emptyList()
        }
    }

    /** 只读查询。列名必须来自白名单，绝不接受外部字符串拼接。 */
    fun query(sql: String): List<Map<String, String?>> {
        val d = db ?: return emptyList()
        return runCatching {
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
        }.getOrElse {
            Log.e(TAG, "查询失败: ${sql.take(80)}", it); emptyList()
        }
    }

    fun hasTable(table: String): Boolean = query(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='${table.safeIdent()}' LIMIT 1"
    ).isNotEmpty()

    companion object {
        private const val TAG = "OHSyncRead"

        /** 单次查询返回上限，防止一次性把整表读进内存。 */
        const val MAX_ROWS = 20000

        /** 表名/列名只允许字母数字下划线，杜绝 SQL 注入面。 */
        fun String.safeIdent(): String = replace(Regex("[^A-Za-z0-9_]"), "")
    }
}
