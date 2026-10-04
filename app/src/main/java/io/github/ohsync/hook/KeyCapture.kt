package io.github.ohsync.hook

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.libxposed.api.XposedInterface

/**
 * 截获 OPPO 健康已经打开好的数据库实例。
 *
 * 实测路径（jadx classes15.dex，com.heytap.databaseengineservice.db.AppDatabase 第 478~500 行）：
 *   AppDatabase.buildReleaseAndOtherDatabase(Context, String databaseKey)
 *     -> databaseKey.getBytes(UTF_8)
 *     -> new net.zetetic.database.sqlcipher.SupportOpenHelperFactory(bytes, hook, true)
 *     -> Room.openHelperFactory(...).create(configuration)
 *   最终落到 com.heytap.databaseengineservice.db.SerialFirstOpenHelper.getReadableDatabase()
 *
 * 我们不拿口令，直接拿「口令已经用过的那个 db 实例」：
 *   - 省掉一整层解密实现与 SQLCipher 版本适配
 *   - 不必把口令握在自己手里
 *   - 零新增依赖，不与 OPPO 进程里已加载的 libsqlcipher 冲突
 */
class KeyCapture(private val xposed: XposedInterface) {

    var onDatabaseReady: ((SupportSQLiteDatabase) -> Unit)? = null

    /** 备份路线拿到的口令，仅在日志里判定是否为空，不外传。 */
    @Volatile var capturedKeyPresent: Boolean = false
        private set

    fun install(classLoader: ClassLoader) {
        var installed = 0

        // 路线一（主）：截 SerialFirstOpenHelper / SupportOpenHelper 返回的 db 实例
        runCatching {
            for (name in DB_HOLDER_CLASSES) {
                val c = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
                for (m in c.methods) {
                    if (m.name in DB_GETTERS && m.parameterCount == 0 &&
                        SupportSQLiteDatabase::class.java.isAssignableFrom(m.returnType)
                    ) {
                        xposed.captureReturn(m, "db.${c.simpleName}.${m.name}") { ret ->
                            (ret as? SupportSQLiteDatabase)?.let { onDatabaseReady?.invoke(it) }
                        }
                        installed++
                    }
                }
            }
        }.onFailure { Log.e(TAG, "数据库实例捕获失败", it) }

        // 路线二（备）：截口令字符串，将来要自己开库时才用得上
        runCatching {
            val appDb = Class.forName(APP_DATABASE, false, classLoader)
            for (m in appDb.declaredMethods) {
                if (m.name == "buildReleaseAndOtherDatabase" && m.parameterCount == 2 &&
                    m.parameterTypes[1] == String::class.java
                ) {
                    xposed.observeHook(m, "key.${m.name}") { args ->
                        val key = args.getOrNull(1) as? String
                        if (!key.isNullOrEmpty()) {
                            capturedKeyPresent = true
                            Log.i(TAG, "已捕获口令（长度 ${key.length}），备用路线可用")
                        }
                    }
                    installed++
                }
            }
        }.onFailure { Log.e(TAG, "口令钩子失败", it) }

        Log.i(TAG, "安装完成，成功挂钩 $installed 处")
        if (installed == 0) Log.e(TAG, "两条路线都没挂上，需重新定位")
    }

    companion object {
        private const val TAG = "OHSyncCapture"

        private const val APP_DATABASE = "com.heytap.databaseengineservice.db.AppDatabase"

        /** 返回 SupportSQLiteDatabase 的方法名。 */
        private val DB_GETTERS = setOf(
            "getReadableDatabase", "getWritableDatabase", "getDatabase",
        )

        /**
         * 候选类按优先级排。SerialFirstOpenHelper 是 jadx 实测存在的；
         * 后两个是兜底 —— OPPO 换版本时实现类名可能变。
         */
        private val DB_HOLDER_CLASSES = listOf(
            "com.heytap.databaseengineservice.db.SerialFirstOpenHelper",
            "net.zetetic.database.sqlcipher.SQLiteOpenHelper",
            "androidx.sqlite.db.SupportSQLiteOpenHelper",
        )
    }
}
