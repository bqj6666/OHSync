package io.github.ohsync.hook

import android.util.Log
import io.github.ohsync.core.RecordType
import org.luckypray.dexkit.DexKitBridge

/**
 * 表名 -> RecordType 的映射。
 *
 * 只按表名关键词判定，不依赖 APP 的类名：类名随时被混淆，Room 的 tableName
 * 反而是这套库里最稳定的锚点。
 *
 * 判定不出的一律 UNKNOWN —— 不写入、不静默丢，会出现在 P0 的映射报告里交用户过目。
 */
class EntityMapping {

    /** 会写入 HC 的表。 */
    val syncable = LinkedHashMap<String, RecordType>()

    /** 按规则废弃的表：HC 1.1.0 无对应记录类型。 */
    val discarded = LinkedHashMap<String, RecordType>()

    /** 认不出来、暂不处理的表。 */
    val unresolved = LinkedHashMap<String, String>()

    fun discover(bridge: DexKitBridge) {
        syncable.clear(); discarded.clear(); unresolved.clear()
        val tables = LinkedHashSet(DexKitLocator.findTables(bridge).keys)
        if (tables.isEmpty()) {
            tables += DexKitLocator.findTablesBySql(bridge)
            Log.i(TAG, "实体注解路径为空，改用 SQL 文本兜底")
        }
        for (t in tables) classify(t)
        Log.i(TAG, "扫描 ${tables.size} 张表：同步 ${syncable.size} / 废弃 ${discarded.size} / 未识别 ${unresolved.size}")
    }

    private fun classify(table: String) {
        val key = table.lowercase()
        val type = TableRules.resolve(key)
        when {
            type == null -> unresolved[table] = key
            type.hcRecord == null -> discarded[table] = type
            else -> syncable[table] = type
        }
    }

    companion object { private const val TAG = "OHSyncMap" }
}
