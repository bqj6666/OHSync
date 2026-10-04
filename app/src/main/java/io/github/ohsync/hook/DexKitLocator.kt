package io.github.ohsync.hook

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * DexKit 定位：找出 OPPO 健康里所有 Room 实体与它们对应的真实表名。
 *
 * 思路（不硬编码类名，APP 改包名/类名也能重新定位）：
 *   1. 找带 androidx.room.Entity 注解的类
 *   2. 读注解里的 tableName
 *   3. 顺带抓类里出现的 CREATE TABLE 字符串，交叉验证表名
 *
 * 找不到就如实报空，绝不猜表名。
 */
object DexKitLocator {

    private const val TAG = "OHSyncDexKit"
    const val TARGET_PREFIX = "com.heytap."
    private const val ENTITY_ANNOTATION = "Landroidx/room/Entity;"

    fun create(cl: ClassLoader): DexKitBridge =
        DexKitBridge.create(cl, /* cacheDirName = */ null, /* enableCache = */ false)
            .also { Log.i(TAG, "DexKit 就绪，dex 数=${it.dexNum}") }

    /** 返回 表名 -> 实体类名。 */
    fun findTables(bridge: DexKitBridge): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching {
            bridge.findClass(
                FindClass()
                    .searchPackages(TARGET_PREFIX)
                    .matcher { m -> m.addAnnotation { a -> a.type = ENTITY_ANNOTATION } }
            )
        }.onSuccess { classes ->
            classes.forEach { cd ->
                runCatching {
                    val table = cd.annotations
                        .firstOrNull { it.typeDescriptor == ENTITY_ANNOTATION }
                        ?.elements?.firstOrNull { it.name == "tableName" }
                        ?.value?.stringValue()
                        ?: return@runCatching
                    out[table] = cd.name
                }
            }
        }.onFailure { Log.e(TAG, "实体扫描失败", it) }
        Log.i(TAG, "扫到实体 ${out.size} 个")
        return out
    }

    /** 备用路径：直接抓 dex 里的 CREATE TABLE 语句（Entity 注解被混淆时用）。 */
    fun findTablesBySql(bridge: DexKitBridge): List<String> {
        val found = LinkedHashSet<String>()
        runCatching {
            bridge.findClass(
                FindClass()
                    .searchPackages(TARGET_PREFIX)
                    .matcher { m ->
                        m.usingStrings(listOf("CREATE TABLE"), StringMatchType.Contains, false)
                    }
            )
        }.onSuccess { classes ->
            classes.forEach { cd ->
                runCatching {
                    cd.methods.forEach { method ->
                        method.usingStrings.forEach { s ->
                            Regex("CREATE TABLE IF NOT EXISTS `([^`]+)`").find(s)
                                ?.groupValues?.get(1)?.let { found.add(it) }
                        }
                    }
                }
            }
        }.onFailure { Log.e(TAG, "SQL 扫描失败", it) }
        return found.toList()
    }
}

    /**
     * 找出 Room 生成的 Dao_Impl 类名（com.heytap.**.db.dao.*Dao_Impl）。
     *
     * 用 DexKit 而不是拼字符串猜：Room 生成类的命名规则虽稳定，
     * 但包路径在 OPPO 各版本间挪过位置（DBSportDataStat 在 db.table，
     * 而 dao 在 db.dao），拼错就是静默失效。
     */
    fun findDaoImplClasses(bridge: DexKitBridge, daoPackage: String): List<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            bridge.findClass(
                FindClass()
                    .searchPackages(daoPackage.substringBeforeLast('.'))
                    .matcher { m ->
                        m.className(Regex(".*Dao_Impl$").pattern, StringMatchType.SimilarRegex, false)
                    }
            )
        }.onSuccess { classes ->
            out += classes.map { it.name }
        }.onFailure { Log.e(TAG, "Dao_Impl 扫描失败", it) }
        return out.toList()
    }
}
