package io.github.ohsync.hook

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge

/**
 * Hook 侧总编排：捕获数据库 -> 扫真实表结构 -> 推送 -> 挂实时钩子。
 *
 * 顺序有讲究：先挂数据库捕获（依赖 OPPO 自己首次开库的那一刻，错过就没了），
 * 再做扫描，最后挂写库实时钩子。
 *
 * 同步路径只依赖**运行中的数据库**（sqlite_master + PRAGMA），
 * DexKit 只用来出一份可读的实体/表名对照报告 —— 它是信息，不是依赖。
 */
class HookCoordinator(private val xposed: XposedInterface) {

    private val reader = TableReader()
    private val mapping = EntityMapping()
    private val capture = KeyCapture(xposed)

    @Volatile private var mapped = false
    @Volatile private var pushedOnce = false
    private var lastPushAt = 0L
    private var bridge: DexKitBridge? = null

    fun onPackageReady(cl: ClassLoader) {
        AppContextHolder.init()

        capture.onDatabaseReady = { db: SupportSQLiteDatabase ->
            reader.attach(db)
            reportTables()
            if (!pushedOnce) {
                pushedOnce = true
                pushFullHistory()
            }
        }
        runCatching { capture.install(cl) }.onFailure { Log.e(TAG, "捕获器安装失败", it) }

        runCatching { dexKitReport(cl) }.onFailure { Log.e(TAG, "DexKit 报告失败", it) }
        runCatching { installWriteHooks(cl) }.onFailure { Log.e(TAG, "实时挂钩失败", it) }
    }

    /**
     * DexKit 报告：从 dex 里找 Room 实体与 tableName。
     * 只写日志，不参与同步决策 —— 同步用的是运行时真实表名。
     */
    private fun dexKitReport(cl: ClassLoader) {
        if (mapped) return
        mapped = true
        val b = DexKitLocator.create(cl)
        bridge = b
        mapping.discover(b)
        Log.i(TAG, "DexKit 报告：实体 ${mapping.syncable.size + mapping.discarded.size + mapping.unresolved.size} 个")
        mapping.syncable.forEach { (table, type) -> Log.i(TAG, "  [实体] $table -> ${type.label}") }
        mapping.discarded.forEach { (table, type) -> Log.i(TAG, "  [废弃] $table -> ${type.label}") }
    }

    /**
     * 真实表结构报告：表名取自 sqlite_master，是权威来源。
     * 认不出语义的表照实列出来，不猜。
     */
    private fun reportTables() {
        val tables = reader.tableNames()
        Log.i(TAG, "数据库共 ${tables.size} 张表")
        var known = 0
        for (t in tables) {
            val type = TableRules.resolve(t.lowercase())
            if (type != null) {
                known++
                val mark = if (type.hcRecord == null) "（无 HC 对应，废弃）" else ""
                Log.i(TAG, "  [表] $t -> ${type.label}$mark")
            }
        }
        val unknown = tables.filter { TableRules.resolve(it.lowercase()) == null }
        Log.i(TAG, "可识别 $known / 共 ${tables.size}；未识别 $unknown")
    }

    /**
     * 实时同步：挂钩 Room 生成的 Dao_Impl.insert。
     *
     * 不用 SportHealthDataService —— jadx 实测它的方法名与写库无关；
     * 而 XxxDao_Impl.insert(List) 是 Room 生成的写库必经点，命名稳定不易变。
     */
    private fun installWriteHooks(cl: ClassLoader) {
        val b = bridge
        val implClasses = if (b != null) {
            DexKitLocator.findDaoImplClasses(b, DAO_PACKAGE)
        } else {
            emptyList()
        }
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
            runCatching { Pusher.push(reader) }.onFailure { Log.e(TAG, "增量推送失败", it) }
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
                Probe.run(reader)
                Pusher.push(reader)
            }.onFailure { Log.e(TAG, "回填失败", it) }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "OHSyncHook"
        private const val DAO_PACKAGE = "com.heytap.databaseengineservice.db.dao"
    }
}
