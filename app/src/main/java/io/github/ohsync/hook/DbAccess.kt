package io.github.ohsync.hook

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.github.libxposed.api.XposedInterface

/**
 * 拿到 OPPO 健康的数据库实例（只读使用）。
 *
 * 两条路，互为兜底：
 *   A. 提前 hook SerialFirstOpenHelper 的 getReadableDatabase/getWritableDatabase，
 *      在它首次开库的瞬间截下实例。这条路要在 App 创建之前装好。
 *   B. 反射读 AppDatabase.INSTANCE 静态字段，再拿 Room 的 OpenHelper 取库。
 *      就算 A 错过了（比如模块加载晚于开库），这条路仍能补上。
 *
 * 不自己引入 SQLCipher、不解密、不复制库：直接用 OPPO 已经打开的实例，
 * 天然共用它的加密实现，零额外依赖。
 */
class DbAccess(private val xposed: XposedInterface) {

    var onDatabaseReady: ((SupportSQLiteDatabase) -> Unit)? = null

    @Volatile private var delivered = false

    /** 路 A：在 App 创建之前调用，才赶得上首次开库。 */
    fun installEarlyHooks(classLoader: ClassLoader) {
        var installed = 0
        for (name in HOLDER_CLASSES) {
            val c = try {
                Class.forName(name, false, classLoader)
            } catch (t: Throwable) {
                continue
            }
            for (m in c.methods) {
                if (m.name !in DB_GETTERS) continue
                if (m.parameterCount != 0) continue
                if (!SupportSQLiteDatabase::class.java.isAssignableFrom(m.returnType)) continue
                xposed.captureReturn(m, "db.${c.simpleName}.${m.name}") { ret ->
                    (ret as? SupportSQLiteDatabase)?.let { deliver(it, "hook:${c.simpleName}.${m.name}") }
                }
                installed++
            }
        }
        Log.i(TAG, "路 A 安装完成，挂钩 $installed 处")
    }

    /** 路 B：App 已创建之后调用，兜住路 A 错过的场景。 */
    fun tryReflectionFallback(classLoader: ClassLoader) {
        if (delivered) return
        try {
            val appDb = Class.forName(APP_DATABASE, false, classLoader)
            val field = appDb.getDeclaredField("INSTANCE").apply { isAccessible = true }
            val instance = field.get(null) ?: run {
                Log.i(TAG, "路 B：AppDatabase.INSTANCE 仍为 null（库还没开）")
                return
            }
            val helper = instance.javaClass
                .getMethod("getOpenHelper")
                .invoke(instance) as? SupportSQLiteOpenHelper
            val db = helper?.readableDatabase
            if (db != null) {
                deliver(db, "reflection:AppDatabase.INSTANCE")
            } else {
                Log.w(TAG, "路 B：OpenHelper 取不到可读库")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "路 B 失败", t)
        }
    }

    private fun deliver(db: SupportSQLiteDatabase, how: String) {
        if (delivered) return
        delivered = true
        Log.i(TAG, "已取得数据库实例（$how）")
        onDatabaseReady?.invoke(db)
    }

    companion object {
        private const val TAG = "OHSyncDb"
        private const val APP_DATABASE = "com.heytap.databaseengineservice.db.AppDatabase"

        private val DB_GETTERS = setOf("getReadableDatabase", "getWritableDatabase")

        private val HOLDER_CLASSES = listOf(
            "com.heytap.databaseengineservice.db.SerialFirstOpenHelper",
            "com.heytap.databaseengineservice.db.RelinkerSupportHelper",
        )
    }
}
