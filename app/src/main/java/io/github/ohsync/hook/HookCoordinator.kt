package io.github.ohsync.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * Hook 侧总编排。
 *
 * 两个阶段，对应 LSPosed 的两个回调：
 *   onPackageLoaded —— App 创建**之前**。只装 hook，不碰数据库（此时还没有 Context）。
 *   onPackageReady  —— App 创建**之后**。拿 Context、兜底取库、出报告、推送。
 *
 * 之所以分两段：OPPO 健康在 Application 初始化时就打开数据库，若等 onPackageReady
 * 再挂钩，首次开库已经过去了，拿不到实例。
 */
class HookCoordinator(private val xposed: XposedInterface) {

    private val reader = TableReader()
    private val dbAccess = DbAccess(xposed)

    @Volatile private var reported = false
    @Volatile private var pushedOnce = false
    private var lastPushAt = 0L

    /** 阶段一：App 创建之前。 */
    fun onPackageLoaded(cl: ClassLoader) {
        runCatching { dbAccess.installEarlyHooks(cl) }
            .onFailure { Log.e(TAG, "提前挂钩失败", it) }
    }

    /** 阶段二：App 创建之后。 */
    fun onPackageReady(cl: ClassLoader) {
        AppContextHolder.init()

        dbAccess.onDatabaseReady = { db ->
            reader.attach(db)
            reportTables()
            if (!pushedOnce) {
                pushedOnce = true
                pushNow(withProbe = true)
            }
            startPeriodicSync()
        }

        // 兜底：路 A 错过首次开库时，从 AppDatabase.INSTANCE 反射取
        runCatching { dbAccess.tryReflectionFallback(cl) }
            .onFailure { Log.e(TAG, "反射兜底失败", it) }
    }

    /**
     * 真实表结构报告：表名取自 sqlite_master，是权威来源。
     * 认不出语义的表照实列出来（进未识别清单），不猜。
     */
    private fun reportTables() {
        if (reported) return
        reported = true
        val tables = reader.tableNames()
        Log.i(TAG, "数据库共 ${tables.size} 张表")
        var known = 0
        for (t in tables) {
            val type = TableRules.resolve(t.lowercase()) ?: continue
            known++
            val mark = if (type.hcRecord == null) "（无 HC 对应，废弃）" else ""
            Log.i(TAG, "  [表] $t -> ${type.label}$mark")
        }
        val unknown = tables.filter { TableRules.resolve(it.lowercase()) == null }
        Log.i(TAG, "可识别 $known / 共 ${tables.size}")
        Log.i(TAG, "未识别：$unknown")
    }

    /**
     * 周期性增量推送。
     *
     * Health Connect 没有推送/订阅写入 API，而 OPPO 的写库点又没有稳定的挂钩位置
     * （它的 DAO 是实现类，名字随版本变；SQLCipher 的连接层挂钩要处理多线程重入）。
     * 所以实时性用「定时推送 + clientRecordId 幂等覆盖」实现：
     * 每次推的是全量最新值，重复推送只会覆盖同一条记录，不会产生重复数据。
     */
    private fun startPeriodicSync() {
        if (periodicStarted) return
        periodicStarted = true
        Thread {
            while (true) {
                try {
                    Thread.sleep(INTERVAL_MS)
                    if (reader.isReady()) pushNow(withProbe = false)
                } catch (t: Throwable) {
                    Log.e(TAG, "周期推送异常", t)
                }
            }
        }.apply { isDaemon = true }.start()
        Log.i(TAG, "周期同步已启动，间隔 ${INTERVAL_MS / 1000} 秒")
    }

    private var periodicStarted = false

    /** UI 点「同步历史数据」时也会走这里。 */
    fun pushFullHistory() = pushNow(withProbe = true)

    private fun pushNow(withProbe: Boolean) {
        if (!reader.isReady()) {
            Log.w(TAG, "数据库未就绪，跳过推送")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastPushAt < 1500) return
        lastPushAt = now
        Thread {
            runCatching {
                if (withProbe) Probe.run(reader)
                Pusher.push(reader)
            }.onFailure { Log.e(TAG, "推送失败", it) }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "OHSyncHook"
        private const val INTERVAL_MS = 120_000L
    }
}
