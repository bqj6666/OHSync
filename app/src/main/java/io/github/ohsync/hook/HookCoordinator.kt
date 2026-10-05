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
            startSyncScheduler()
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
     * 同步调度：事件驱动 + 本地计时，不做轮询。
     *
     *   - 用户点「立即同步」/ 改设置 → 主进程发广播 → 立即唤醒
     *   - 自动同步 → 本地按用户设的间隔计时
     *
     * 平时只在本地 wait，不产生任何跨进程调用。这一点很重要：早期版本每 5 秒
     * 查一次配置，等于把 OHSync 主进程永久钉在内存里（实测常驻 PSS 约 55 MB）。
     *
     * 另留一个低频兜底刷新（[CONFIG_REFRESH_MS]），防止广播被省电策略丢弃。
     */
    private fun startSyncScheduler() {
        if (schedulerStarted) return
        schedulerStarted = true

        val ctx = AppContextHolder.context
        if (ctx == null) {
            Log.w(TAG, "没有 Context，事件通道未注册，只能靠本地计时")
        } else {
            SyncTrigger.register(
                ctx,
                onSyncNow = { manualRequest = true; configDirty = true; wake() },
                onConfigChanged = { configDirty = true; wake() },
                onPing = { RemoteConfig.ping() },
            )
        }

        Thread {
            var lastPush = 0L
            var cfg: HookConfig? = null
            var cfgAt = 0L
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    val stale = cfg == null || now - cfgAt >= CONFIG_REFRESH_MS
                    if (stale || configDirty) {
                        RemoteConfig.fetch(force = true)?.let {
                            cfg = it
                            cfgAt = now
                            configDirty = false
                        }
                    }

                    val manual = manualRequest
                    val minutes = cfg?.intervalMinutes ?: 0
                    val due = minutes > 0 && now - lastPush >= minutes * 60_000L

                    if (manual || due) {
                        if (reader.isReady()) {
                            pushNow(withProbe = manual, incremental = !manual)
                            lastPush = System.currentTimeMillis()
                            manualRequest = false
                        } else {
                            Log.w(TAG, "数据库未就绪，稍后再试")
                        }
                    }
                    waitForEvent(TICK_MS)
                } catch (t: Throwable) {
                    Log.e(TAG, "同步调度异常", t)
                    waitForEvent(60_000L)
                }
            }
        }.apply { isDaemon = true }.start()
        Log.i(TAG, "同步调度已启动（事件驱动，本地计时）")
    }

    private var schedulerStarted = false

    /** 用户在应用里点了「立即同步」。 */
    @Volatile private var manualRequest = false

    /** 配置有变化，下一轮重新取。 */
    @Volatile private var configDirty = false

    private fun wake() = synchronized(wakeLock) { wakeLock.notifyAll() }

    private fun waitForEvent(ms: Long) {
        synchronized(wakeLock) {
            try {
                wakeLock.wait(ms)
            } catch (_: InterruptedException) {
            }
        }
    }

    private val wakeLock = Object()

    /** UI 点「同步历史数据」时也会走这里。 */
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

        /** 本地计时粒度。只 sleep，不做任何 IPC。 */
        private const val TICK_MS = 30_000L

        /** 兜底刷新配置的间隔（正常情况下由事件驱动）。 */
        private const val CONFIG_REFRESH_MS = 3_600_000L
    }
}
