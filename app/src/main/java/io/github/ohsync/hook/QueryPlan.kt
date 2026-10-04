package io.github.ohsync.hook

import android.util.Log
import io.github.ohsync.core.RecordMapper
import io.github.ohsync.core.RecordType
import io.github.ohsync.core.SyncRecord

/**
 * 一张表的读取与翻译方案：SQL + 行 -> SyncRecord 的函数。
 *
 * 字段名与单位全部来自 jadx 实测 classes15.dex 的 @ColumnInfo，
 * 不凭 OPPO 的中文界面猜。改映射只动这一个文件。
 */
internal class TableSpec(
    val type: RecordType,
    val sql: String,
    val toRecord: (Map<String, String?>) -> SyncRecord?,
)

internal object QueryPlan {

    private const val TAG = "OHSyncPlan"

    /** 按已映射的表生成查询方案。表不存在就跳过，不报错。 */
    fun forTables(reader: TableReader, mapping: EntityMapping): Map<String, TableSpec> {
        val out = LinkedHashMap<String, TableSpec>()
        for ((table, type) in mapping.syncable) {
            if (!reader.hasTable(table)) { Log.w(TAG, "表 $table 不存在，跳过"); continue }
            val spec = build(table, type) ?: continue
            out[table] = spec
        }
        return out
    }

    private fun build(table: String, type: RecordType): TableSpec? = when (type) {
        // 步数：日汇总表，实测列 total_steps / total_distance / total_calories
        RecordType.STEPS -> TableSpec(
            type,
            "SELECT _id, start_timestamp, end_timestamp, total_steps, total_distance, " +
                "total_calories, timezone FROM $table ORDER BY _id DESC LIMIT ${TableReader.MAX_ROWS}",
        ) { r -> r.syncRecord(type, "_id", "start_timestamp", "end_timestamp") { v ->
            v[RecordMapper.V_COUNT] = r.num("total_steps")
        } }

        // 距离：同一张日汇总表里取
        RecordType.DISTANCE -> TableSpec(
            type,
            "SELECT _id, start_timestamp, end_timestamp, total_distance, timezone FROM $table " +
                "ORDER BY _id DESC LIMIT ${TableReader.MAX_ROWS}",
        ) { r -> r.syncRecord(type, "_id", "start_timestamp", "end_timestamp") { v ->
            v[RecordMapper.V_DISTANCE_M] = r.num("total_distance")
        } }

        RecordType.ACTIVE_CALORIES -> TableSpec(
            type,
            "SELECT _id, start_timestamp, end_timestamp, total_calories, timezone FROM $table " +
                "ORDER BY _id DESC LIMIT ${TableReader.MAX_ROWS}",
        ) { r -> r.syncRecord(type, "_id", "start_timestamp", "end_timestamp") { v ->
            v[RecordMapper.V_CALORIES] = r.num("total_calories")
        } }

        else -> null
    }

    /** 组装 SyncRecord：主键 -> sourceKey，时间列必填。 */
    private fun Map<String, String?>.syncRecord(
        type: RecordType,
        pk: String,
        startCol: String,
        endCol: String,
        extra: (MutableMap<String, Double>) -> Unit,
    ): SyncRecord? {
        val key = this[pk] ?: return null
        val start = num(startCol)?.toLong() ?: return null
        val end = num(endCol)?.toLong() ?: start
        if (start <= 0) return null
        val values = HashMap<String, Double>()
        extra(values)
        if (values.values.all { it == 0.0 }) return null
        return SyncRecord(
            type = type.id,
            sourceKey = key,
            startTime = start,
            endTime = if (end < start) start else end,
            values = values,
            zoneOffsetSeconds = null,
            metadata = HashMap(),
        )
    }

    private fun Map<String, String?>.num(col: String): Double? = this[col]?.toDoubleOrNull()
}
