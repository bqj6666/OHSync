package io.github.ohsync.hook

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.AnnotationMatcher

/**
 * DexKit 定位：从 OPPO 健康的 dex 里找出 Room 实体与它们对应的真实表名。
 *
 * 两条路径，主备关系：
 *   1. 找带 androidx.room.Entity 注解的类，读注解里的 tableName（准确）
 *   2. 找不到时退化为扫 CREATE TABLE 语句文本（实体被混淆时仍可用）
 *
 * 宁可如实报空，也不猜表名。
 */
object DexKitLocator {

    private const val TAG = "OHSyncDexKit"
    const val TARGET_PREFIX = "com.heytap."
    private const val ENTITY_ANNOTATION = "Landroidx/room/Entity;"

    fun create(cl: ClassLoader): DexKitBridge =
        DexKitBridge.create(cl, false)
            .also { Log.i(TAG, "DexKit 就绪") }

    /** 返回 表名 -> 实体类名。 */
    fun findTables(bridge: DexKitBridge): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val query = FindClass()
            .searchPackages(TARGET_PREFIX)
            .matcher { matcher ->
                matcher.addAnnotation(
                    AnnotationMatcher().apply { type = ENTITY_ANNOTATION }
                )
            }
        runCatching { bridge.findClass(query) }
            .onSuccess { classes ->
                classes.forEach { cd ->
                    val table = runCatching {
                        cd.annotations
                            .firstOrNull { it.typeDescriptor == ENTITY_ANNOTATION }
                            ?.elements
                            ?.firstOrNull { it.name == "tableName" }
                            ?.value
                            ?.stringValue()
                    }.getOrNull()
                    if (!table.isNullOrBlank()) out[table] = cd.name
                }
            }
            .onFailure { Log.e(TAG, "实体扫描失败", it) }
        Log.i(TAG, "扫到实体 ${out.size} 个")
        return out
    }

    /** 备用路径：直接抓 dex 里的 CREATE TABLE 语句。 */
    fun findTablesBySql(bridge: DexKitBridge): List<String> {
        val found = LinkedHashSet<String>()
        val query = FindClass()
            .searchPackages(TARGET_PREFIX)
            .matcher { matcher ->
                matcher.usingStrings(listOf("CREATE TABLE"), StringMatchType.Contains, false)
            }
        runCatching { bridge.findClass(query) }
            .onSuccess { classes ->
                classes.forEach { cd ->
                    runCatching {
                        cd.methods.forEach { method ->
                            method.usingStrings.forEach { s ->
                                CREATE_TABLE.find(s)?.groupValues?.get(1)?.let { found.add(it) }
                            }
                        }
                    }
                }
            }
            .onFailure { Log.e(TAG, "SQL 扫描失败", it) }
        Log.i(TAG, "SQL 文本兜底扫到 ${found.size} 张表")
        return found.toList()
    }

    private val CREATE_TABLE = Regex("CREATE TABLE(?: IF NOT EXISTS)? [`\"]?([A-Za-z0-9_]+)")

    /**
     * 找出 Room 生成的 Dao_Impl 类名（com.heytap.**.db.dao.*Dao_Impl）。
     *
     * 用 DexKit 扫而不是拼字符串猜：包路径在 OPPO 各版本间挪过位置
     * （DBSportDataStat 在 db.table，而 dao 在 db.dao），拼错就是静默失效。
     */
    fun findDaoImplClasses(bridge: DexKitBridge, daoPackage: String): List<String> {
        val out = LinkedHashSet<String>()
        val query = FindClass()
            .searchPackages(daoPackage.substringBeforeLast('.'))
            .matcher { matcher ->
                matcher.className(".*Dao_Impl$", StringMatchType.SimilarRegex, false)
            }
        runCatching { bridge.findClass(query) }
            .onSuccess { classes -> out += classes.map { it.name } }
            .onFailure { Log.e(TAG, "Dao_Impl 扫描失败", it) }
        return out.toList()
    }
}
