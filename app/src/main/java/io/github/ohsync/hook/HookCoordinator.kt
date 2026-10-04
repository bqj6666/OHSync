package io.github.ohsync.hook

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge

/**
 * Hook 侧总编排：捕获数据库 -> DexKit 扫表 -> 建映射 -> 推送。
 *
 * 顺序有讲究：先挂数据库捕获（依赖 OPPO 自己首次开库的那一刻，错过就没了），
 * 再做 DexKit 扫描（不依赖数据库，可并行），最后挂写库实时钩子。
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

        capture.onDatabaseReady = { db: SupportSQLiteDatabase ->
            reader.attach(db)
            runCatching { discoverSchema(cl) }.onFailure { Log.e(TAG, "扫描失败", it) }
            if (!pushedOnce) {
                pushedOnce = true
                pushFullHistory()
            }
        }
        runCatching { capture.install(cl) }.onFailure { Log.e(TAG, "捕获器安装失败", it) }

        runCatching { discoverSchema(cl) }.onFailure { Log.e(TAG, "DexKit 失败", it) }
        runCatching { installWriteHooks(cl) }.onFailure { Log.e(TAG, "实时挂钩失败", it) }
    }

    /** 扫表 + 建映射。DexKit bridge 只建一次，后续实时挂钩复用。 */
    private fun discoverSchema(cl: ClassLoader) {
        if (mappingDone) return
        val bridge = DexKitLocator.create(cl)
        bridgeRef = bridge
        mapping.discover(bridge)
        mappingDone = true
        Log.i(TAG, "映射完成：同步 ${mapping.syncable.size} / 废弃 ${mapping.discarded.size}")
        mapping.syncable.forEach { (table, type) -> Log.i(TAG, "  [同步] $table -> ${type.label}") }
        mapping.discarded.forEach { (table, type) -> Log.i(TAG, "  [废弃] $table -> ${type.label}") }
        if (mapping.unresolved.isNotEmpty()) {
            Log.w(TAG, "未识别 ${mapping.unresolved.size} 张表：${mapping.unresolved.keys}")
        }
    }

    private var bridgeRef: DexKitBridge? = null

    /**
     * 实时同步：挂钩 Room 生成的 Dao_Impl.insert。
     *
     * 不用 SportHealthDataService —— jadx 实测它的方法名与写库无关；
     * 而 XxxDao_Impl.insert(List) 是 Room 生成的写库必经点，命名稳定不易变。
     */
    private fun installWriteHooks(cl: ClassLoader) {
        val bridge = bridgeRef ?: DexKitLocator.create(cl).also { bridgeRef = it }
        val daoPackage = "com.heytap.databaseengineservice.db.dao"
        val implClasses = DexKitLocator.findDaoImplClasses(bridge, daoPackage)
        var hooked = 0
        for (name in implClasses) {
            val c = runCatching { Class.forName(name, false, cl) }.getOrNull() ?: continue
            for (fn in c.methods) {
                if (fn.name.startsWith("insert") && fn.parameterCount >= 1) {
                    xposed.observeHook(fn, "dao.${c.simpleName}.${fn.name}") { schedulePush() }
                    hooked++
                }
            }
        }
        Log.i(TAG, "实时钩子挂上 $hooked 处（候选 Dao_Impl ${implClasses.size} 个）")
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
        if (!reader.isReady()) {
            Log.w(TAG, "数据库未就绪，无法回填")
            return
        }
        Thread {
            runCatching {
                SleepProbe.run(reader)
                Pusher.push(reader, mapping)
            }.onFailure { Log.e(TAG, "回填失败", it) }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "OHSyncHook"
    }
}
