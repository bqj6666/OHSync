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
            // 首次拿到数据库时推一次；用户设了「仅手动」则不自动推
            if (!pushedOnce && (RemoteConfig.fetch()?.intervalMinutes ?: 60) > 0) {
                pushedOnce = true
                // 首次拿库走完整窗口，把历史补上
                pushNow(withProbe = true, incremental = false)
            }
            startPeriodicSync()
        }

        // 兜底：路 A 错过首次开库时，反复重试从 AppDatabase.INSTANCE 反射取
        dbAccess.startFallbackLoop(cl)
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
     * 周期性推送。
     *
     * Health Connect 没有推送/订阅写入 API，OPPO 的写库点也没有稳定的挂钩位置
     * （DAO 是实现类、名字随版本变；SQLCipher 连接层挂钩要处理多线程重入）。
     * 所以实时性用「定时推送 + clientRecordId 幂等覆盖」实现：每次推的是权威全量值，
     * 重复推送只会覆盖同一条记录，不会产生重复数据。
     *
     * 间隔由用户在 OHSync 里设置，每轮开始时向主进程取一次；设为「仅手动」则空转等待。
     */
    private fun startPeriodicSync() {
        if (periodicStarted) return
        periodicStarted = true
        Thread {
            var lastPush = 0L
            while (true) {
                try {
                    // 30 秒一轮：轻量地看一眼有没有手动请求；真正的推送按用户设的间隔走
                    Thread.sleep(30_000L)
                    val cfg = RemoteConfig.fetch(force = true) ?: continue

                    val manual = cfg.backfillRequested
                    val minutes = cfg.intervalMinutes
                    val due = minutes > 0 &&
                        System.currentTimeMillis() - lastPush >= minutes * 60_000L

                    if (manual || due) {
                        if (!reader.isReady()) {
                            Log.w(TAG, "数据库未就绪，稍后再试")
                            continue
                        }
                        pushNow(withProbe = manual, incremental = !manual)
                        lastPush = System.currentTimeMillis()
                        if (manual) RemoteConfig.reportBackfillDone()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "周期推送异常", t)
                    try {
                        Thread.sleep(60_000L)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        Log.i(TAG, "推送循环已启动（间隔由主进程下发）")
    }

    private var periodicStarted = false

    /** UI 点「同步历史数据」时也会走这里。 */
    /** 用户点「立即同步」：走完整窗口。 */
    fun pushFullHistory() = pushNow(withProbe = true, incremental = false)

    /**
     * @param withProbe   顺带跑一次证据探针（只在手动同步时，探针输出很啰嗦）
     * @param incremental 只取上次同步之后的新数据；手动同步传 false 走完整窗口
     */
    private fun pushNow(withProbe: Boolean, incremental: Boolean = false) {
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
                Pusher.push(reader, incremental)
            }.onFailure { Log.e(TAG, "推送失败", it) }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "OHSyncHook"
    }
}
