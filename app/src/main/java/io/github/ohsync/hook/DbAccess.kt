package io.github.ohsync.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * 拿到 OPPO 健康的数据库实例（只读使用）。
 *
 * 两条路，互为兜底：
 *   A. 提前 hook 打开库的那个方法，在首次开库的瞬间截下返回的实例。
 *   B. 定时反射读 AppDatabase.INSTANCE -> Room 的 OpenHelper -> 可读库。
 *      OPPO 开库比 App 创建晚，所以这条路必须**反复重试**，只试一次必然落空。
 *
 * 不自己引入 SQLCipher、不解密、不复制库。
 *
 * 注意：不按返回类型判断（模块与宿主各有自己一份 androidx.sqlite，跨 ClassLoader
 * 是不同的 Class，类型判断永远不成立），只按方法名匹配，拿到对象后按行为验证。
 */
class DbAccess(private val xposed: XposedInterface) {

    var onDatabaseReady: ((Any) -> Unit)? = null

    @Volatile private var delivered = false

    /** 路 A：在 App 创建之前调用，并记录实际找到了什么，便于排障。 */
    fun installEarlyHooks(classLoader: ClassLoader) {
        var installed = 0
        for (name in HOLDER_CLASSES) {
            val c = try {
                Class.forName(name, false, classLoader)
            } catch (t: Throwable) {
                Log.i(TAG, "候选类不存在：$name")
                continue
            }
            val getters = c.methods.filter { it.name in DB_GETTERS && it.parameterCount == 0 }
            Log.i(TAG, "$name 上找到 ${getters.size} 个取库方法：${getters.map { it.name }}")
            for (m in getters) {
                xposed.captureReturn(m, "db.${c.simpleName}.${m.name}") { ret ->
                    if (ret != null) deliver(ret, "hook ${c.simpleName}.${m.name}")
                }
                installed++
            }
        }
        Log.i(TAG, "路 A 完成，挂钩 $installed 处")
    }

    /**
     * 路 B：反复重试直到拿到实例。
     * OPPO 开库的时间点不固定（可能等界面加载数据时才开），单次尝试没有意义。
     */
    fun startFallbackLoop(classLoader: ClassLoader) {
        if (fallbackStarted) return
        fallbackStarted = true
        Thread {
            var tries = 0
            while (!delivered && tries < MAX_TRIES) {
                tries++
                try {
                    tryReflection(classLoader, tries)
                } catch (t: Throwable) {
                    Log.e(TAG, "路 B 第 $tries 次失败", t)
                }
                if (!delivered) Thread.sleep(RETRY_MS)
            }
            if (!delivered) Log.e(TAG, "路 B 重试 $MAX_TRIES 次仍未拿到库实例")
        }.apply { isDaemon = true }.start()
    }

    private var fallbackStarted = false

    /**
     * 主动触发 OPPO 初始化数据库。
     *
     * 必要性：路 A 的 hook 只在 OPPO 自己去开库时才会触发。如果用户没打开 OPPO 健康，
     * 它可能长时间不碰数据库，于是我们永远拿不到实例、点「立即同步」也毫无反应
     * （实测日志会一直刷「路 B … INSTANCE 仍为 null（库未开）」）。
     *
     * 做法就是调它自己的入口 AppDatabase.getInstance(Context) —— 幂等、只读用途，
     * 相当于提前把它本来也会做的初始化做掉，不修改任何健康数据。
     */
    private fun tryTriggerOpen(appDb: Class<*>): Boolean {
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context ?: return false
        return runCatching {
            val m = appDb.getDeclaredMethod("getInstance", android.content.Context::class.java)
            m.isAccessible = true
            val created = m.invoke(null, ctx)
            created != null
        }.onFailure { Log.w(TAG, "主动开库失败", it) }.getOrDefault(false)
    }

    private fun tryReflection(classLoader: ClassLoader, attempt: Int) {
        val appDb = try {
            Class.forName(APP_DATABASE, false, classLoader)
        } catch (t: Throwable) {
            Log.i(TAG, "路 B：AppDatabase 不存在")
            return
        }
        val instance = try {
            appDb.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        } catch (t: Throwable) {
            Log.w(TAG, "路 B：读 INSTANCE 失败", t)
            return
        }
        if (instance == null) {
            // 还没初始化过：主动触发一次，别干等 OPPO 自己想起来开库
            if (attempt == 1 || attempt % 10 == 1) {
                Log.i(TAG, "路 B 第 $attempt 次：INSTANCE 为 null，主动触发开库")
            }
            if (tryTriggerOpen(appDb)) {
                // 触发成功后下一轮就能从 INSTANCE 取到实例
                Log.i(TAG, "已主动触发 OPPO 数据库初始化")
            }
            return
        }
        // Room 的 RoomDatabase.getOpenHelper() -> SupportSQLiteOpenHelper
        val helper = try {
            instance.javaClass.getMethod("getOpenHelper").invoke(instance)
        } catch (t: Throwable) {
            Log.w(TAG, "路 B：getOpenHelper 失败", t)
            return
        }
        val db = try {
            helper?.javaClass?.getMethod("getReadableDatabase")?.invoke(helper)
        } catch (t: Throwable) {
            Log.w(TAG, "路 B：getReadableDatabase 失败", t)
            return
        }
        if (db != null) deliver(db, "reflection AppDatabase.INSTANCE")
    }

    private fun deliver(database: Any, how: String) {
        if (delivered) return
        delivered = true
        Log.i(TAG, "已取得数据库实例（$how）：${database.javaClass.name}")
        onDatabaseReady?.invoke(database)
    }

    companion object {
        private const val TAG = "OHSyncDb"
        private const val APP_DATABASE = "com.heytap.databaseengineservice.db.AppDatabase"
        private const val RETRY_MS = 3000L
        private const val MAX_TRIES = 200

        private val DB_GETTERS = setOf("getReadableDatabase", "getWritableDatabase")

        private val HOLDER_CLASSES = listOf(
            "com.heytap.databaseengineservice.db.SerialFirstOpenHelper",
            "com.heytap.databaseengineservice.db.RelinkerSupportHelper",
        )
    }
}
