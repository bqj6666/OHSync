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
 *
 * 注意 DexKit 的 matcher DSL 是**接收者 lambda**（this 即 ClassMatcher），
 * 不是带参数的 lambda，写成 `matcher { x -> x.foo() }` 会编译失败。
 */
object DexKitLocator {

    private const val TAG = "OHSyncDexKit"
    const val TARGET_PREFIX = "com.heytap."
    private const val ENTITY_ANNOTATION = "Landroidx/room/Entity;"

    fun create(cl: ClassLoader): DexKitBridge =
        DexKitBridge.create(cl, false).also { Log.i(TAG, "DexKit 就绪") }

    /** 返回 表名 -> 实体类名。 */
    fun findTables(bridge: DexKitBridge): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val query = FindClass()
            .searchPackages(TARGET_PREFIX)
            .matcher {
                addAnnotation(AnnotationMatcher().apply { type = ENTITY_ANNOTATION })
            }
        val classes = try {
            bridge.findClass(query)
        } catch (t: Throwable) {
            Log.e(TAG, "实体扫描失败", t)
            return out
        }
        for (cd in classes) {
            val table: String? = cd.annotations
                .firstOrNull { it.typeDescriptor == ENTITY_ANNOTATION }
                ?.elements
                ?.firstOrNull { it.name == "tableName" }
                ?.value
                ?.stringValue()
            if (!table.isNullOrBlank()) out[table] = cd.name
        }
        Log.i(TAG, "扫到实体 ${out.size} 个")
        return out
    }

    /** 备用路径：直接抓 dex 里的 CREATE TABLE 语句。 */
    fun findTablesBySql(bridge: DexKitBridge): List<String> {
        val found = LinkedHashSet<String>()
        val query = FindClass()
            .searchPackages(TARGET_PREFIX)
            .matcher {
                usingStrings(listOf("CREATE TABLE"), StringMatchType.Contains, false)
            }
        val classes = try {
            bridge.findClass(query)
        } catch (t: Throwable) {
            Log.e(TAG, "SQL 扫描失败", t)
            return emptyList()
        }
        for (cd in classes) {
            for (method in cd.methods) {
                for (s in method.usingStrings) {
                    CREATE_TABLE.find(s)?.groupValues?.getOrNull(1)?.let { found.add(it) }
                }
            }
        }
        Log.i(TAG, "SQL 文本兜底扫到 ${found.size} 张表")
        return found.toList()
    }

    /**
     * 找出 Room 生成的 Dao_Impl 类名。
     *
     * 用 DexKit 扫而不是拼字符串猜：包路径在 OPPO 各版本间挪过位置
     * （DBSportDataStat 在 db.table，而 dao 在 db.dao），拼错就是静默失效。
     */
    fun findDaoImplClasses(bridge: DexKitBridge, daoPackage: String): List<String> {
        val query = FindClass()
            .searchPackages(daoPackage.substringBeforeLast('.'))
            .matcher {
                className(".*Dao_Impl$", StringMatchType.SimilarRegex, false)
            }
        return try {
            bridge.findClass(query).map { it.name }
        } catch (t: Throwable) {
            Log.e(TAG, "Dao_Impl 扫描失败", t)
            emptyList()
        }
    }

    private val CREATE_TABLE = Regex("CREATE TABLE(?: IF NOT EXISTS)? [`\"]?([A-Za-z0-9_]+)")
}
