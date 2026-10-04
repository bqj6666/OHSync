package io.github.ohsync.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * Hook 侧总编排：挂载数据库 -> DexKit 扫表 -> 建映射 -> 推送。
 *
 * 顺序有讲究：先挂数据库捕获（它依赖 OPPO 自己首次开库的那一刻，错过就没了），
 * 再做 DexKit 扫描，最后才挂写库实时钩子。
 */
class HookCoordinator(private val xposed: XposedInterface) {

    private val reader = TableReader()
    private val mapping = EntityMapping()
    private val capture = KeyCapture(xposed)

    @Volatile private var mappingDone = false
    @Volatile private var pushedOnce = false
    private var lastPushAt = 0L

    fun onPackageReady(cl: ClassLoader) {
        AppContextHolder.init()
        // 1. 数据库捕获：必须在 OPPO 自己首次开库前挂上
        capture.onDatabaseReady = { db ->
            reader.attach(db)
            runCatching { discoverSchema(cl) }.onFailure { Log.e(TAG, "扫描失败", it) }
            runCatching { SleepProbe.run(reader) }
                .onFailure { Log.e(TAG, "睡眠分段自检失败", it) }
            if (!pushedOnce) {
                pushedOnce = true
                // 历史全量：拿到库就推一次，之后只做增量
                pushFullHistory()
            }
        }
        runCatching { capture.install(cl) }.onFailure { Log.e(TAG, "捕获器安装失败", it) }

        // 2. DexKit 扫描不依赖数据库，可以先跑
        runCatching { discoverSchema(cl) }.onFailure { Log.e(TAG, "DexKit 失败", it) }

        // 3. 写库实时钩子
        runCatching { installWriteHooks(cl) }.onFailure { Log.e(TAG, "实时挂钩失败", it) }
    }

    private fun discoverSchema(cl: ClassLoader) {
        if (mappingDone) return
        val bridge = DexKitLocator.create(cl)
        mapping.discover(bridge)
        bridge.close()
        mappingDone = true
        Log.i(TAG, "映射完成：同步 ${mapping.syncable.size} / 废弃 ${mapping.discarded.size}")
        mapping.syncable.forEach { (t, rt) -> Log.i(TAG, "  [同步] $t -> ${rt.label}") }
        mapping.discarded.forEach { (t, rt) -> Log.i(TAG, "  [废弃] $t -> ${rt.label}") }
        if (mapping.unresolved.isNotEmpty()) {
            Log.w(TAG, "未识别 ${mapping.unresolved.size} 张表：${mapping.unresolved.keys}")
        }
    }

    /**
     * 实时同步：挂钩 Room 生成的 Dao_Impl.insert。
     *
     * 不用 SportHealthDataService —— jadx 实测它的方法名与写库无关；
     * 而 XxxDao_Impl.insert(List) 是写库必经点，命名稳定且由 Room 生成不易变。
     */
    private fun installWriteHooks(cl: ClassLoader) {
        var hooked = 0
        val daoPackage = "com.heytap.databaseengineservice.db.dao"
        for (m in DexKitLocator.findDaoImplClasses(cl, daoPackage)) {
            val c = runCatching { Class.forName(m, false, cl) }.getOrNull() ?: continue
            for (fn in c.methods) {
                if (fn.name.startsWith("insert") && fn.parameterCount >= 1) {
                    xposed.observeHook(fn, "dao.${c.simpleName}.${fn.name}") { schedulePush() }
                    hooked++
                }
            }
        }
        Log.i(TAG, "实时钩子挂上 $hooked 处")
        if (hooked == 0) Log.w(TAG, "没挂到写库入口，实时同步不可用，仅保留回填")
    }

    /** 合并高频写：2 秒内多次触发只推一次。 */
    private fun schedulePush() {
        if (!reader.isReady()) return
        val now = System.currentTimeMillis()
        if (now - lastPushAt < 2000) return
        lastPushAt = now
        Thread {
            runCatching { Pusher.push(reader, mapping) }
                .onFailure { Log.e(TAG, "增量推送失败", it) }
        }.apply { isDaemon = true }.start()
    }

    /** UI 点「同步历史数据」时全量推一次。 */
    fun pushFullHistory() {
        if (!reader.isReady()) { Log.w(TAG, "数据库未就绪，无法回填"); return }
        Thread {
            runCatching { Pusher.push(reader, mapping) }
                .onFailure { Log.e(TAG, "回填失败", it) }
        }.apply { isDaemon = true }.start()
    }

    companion object { private const val TAG = "OHSyncHook" }
}
