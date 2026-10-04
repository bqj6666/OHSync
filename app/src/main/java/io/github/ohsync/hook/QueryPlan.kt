package io.github.ohsync.hook

import android.util.Log
import io.github.ohsync.core.RecordMapper
import io.github.ohsync.core.RecordType
import io.github.ohsync.core.SyncRecord

/**
 * 从 OPPO 数据库的真实结构推导出「读什么、怎么翻译」。
 *
 * 关键取舍：表名与列名都取自**运行中的数据库**（sqlite_master + PRAGMA table_info），
 * 不硬编码、不靠 dex 推断。OPPO 改内部类名完全不影响，改表结构也只会在自检里报出来。
 *
 * 单位未经验证的字段一律不写进 Health Connect —— 宁可少同步，也不能写错数据。
 * 未启用的字段由 [Probe] 打印原始样本，拿到证据后再开。
 */
internal class TableSpec(
    val type: RecordType,
    val sql: String,
    val toRecord: (Map<String, String?>) -> SyncRecord?,
)

internal object QueryPlan {

    private const val TAG = "OHSyncPlan"

    fun forTables(reader: TableReader): List<TableSpec> {
        val specs = ArrayList<TableSpec>()
        for (table in reader.tableNames()) {
            val cols = reader.columns(table)
            if (cols.isEmpty()) continue
            stepsSpec(table, cols)?.let { specs.add(it) }
        }
        return specs
    }

    /**
     * 步数：唯一的、语义无歧义的指标（就是次数），所以 P1 先只同步它。
     *
     * 子数据表用 total_steps（按天汇总），明细表用 steps（按段），
     * 两者时间列名不同（total_* 表用 start_time，明细/睡眠表用 start_timestamp），
     * 这里按实际存在的列自适应。
     */
    private fun stepsSpec(table: String, cols: Set<String>): TableSpec? {
        val valueCol = when {
            "total_steps" in cols -> "total_steps"
            "steps" in cols && "start_time" in cols -> "steps"
            else -> return null
        }
        val startCol = timeCol(cols, "start") ?: return null
        val endCol = timeCol(cols, "end")
        if ("_id" !in cols) return null

        val selectEnd = if (endCol != null) ", $endCol" else ""
        val sql = "SELECT _id, $startCol$selectEnd, $valueCol FROM $table " +
            "WHERE $valueCol > 0 ORDER BY $startCol DESC LIMIT ${TableReader.MAX_ROWS}"

        return TableSpec(RecordType.STEPS, sql) { r ->
            val id = r["_id"]
            val start = r[startCol]?.toLongOrNull()
            val end = endCol?.let { r[it]?.toLongOrNull() }
            val value = r[valueCol]?.toDoubleOrNull()
            if (id == null || start == null || start <= 0L || value == null || value <= 0.0) {
                null
            } else {
                SyncRecord(
                    type = RecordType.STEPS.id,
                    sourceKey = "$table:$id",
                    startTime = start,
                    endTime = maxOf(end ?: start, start),
                    values = mapOf(RecordMapper.V_COUNT to value),
                )
            }
        }
    }

    /** 时间列名在不同表里不一致：汇总表是 start_time，明细/睡眠表是 start_timestamp。 */
    private fun timeCol(cols: Set<String>, prefix: String): String? = when {
        "${prefix}_time" in cols -> "${prefix}_time"
        "${prefix}_timestamp" in cols -> "${prefix}_timestamp"
        else -> null
    }
}
