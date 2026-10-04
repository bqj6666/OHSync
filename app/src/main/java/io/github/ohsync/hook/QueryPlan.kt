package io.github.ohsync.hook

import android.util.Log
import io.github.ohsync.core.RecordMapper
import io.github.ohsync.core.RecordType
import io.github.ohsync.core.SyncRecord

/**
 * 一张表的读取与翻译方案：SQL + 行 -> SyncRecord 的函数。
 *
 * 列名与单位全部来自 jadx 实测 classes15.dex 的 @ColumnInfo，不凭界面文案猜。
 * 改映射只动这一个文件。
 */
internal class TableSpec(
    val type: RecordType,
    val sql: String,
    val toRecord: (Map<String, String?>) -> SyncRecord?,
)

internal object QueryPlan {

    private const val TAG = "OHSyncPlan"

    fun forTables(reader: TableReader, mapping: EntityMapping): Map<String, TableSpec> {
        val out = LinkedHashMap<String, TableSpec>()
        for ((table, type) in mapping.syncable) {
            if (!reader.hasTable(table)) {
                Log.w(TAG, "表 $table 不存在，跳过")
                continue
            }
            build(table, type)?.let { out["$table#${type.id}"] = it }
        }
        return out
    }

    private fun build(table: String, type: RecordType): TableSpec? = when (type) {
        RecordType.STEPS -> daily(table, type, RecordMapper.V_COUNT, "total_steps")
        RecordType.DISTANCE -> daily(table, type, RecordMapper.V_DISTANCE_M, "total_distance")
        RecordType.ACTIVE_CALORIES -> daily(table, type, RecordMapper.V_CALORIES, "total_calories")
        RecordType.HEART_RATE -> heartRate(table, type)
        RecordType.BLOOD_PRESSURE -> bloodPressure(table, type)
        RecordType.BLOOD_OXYGEN -> bloodOxygen(table, type)
        else -> null
    }

    /** DBSportDataStat / DBSportDataDetail 共用的日汇总形态：起止时间 + 一个数值列。 */
    private fun daily(table: String, type: RecordType, valueKey: String, column: String): TableSpec {
        val sql = "SELECT _id, start_timestamp, end_timestamp, $column FROM $table " +
            "ORDER BY _id DESC LIMIT ${TableReader.MAX_ROWS}"
        val mapper: (Map<String, String?>) -> SyncRecord? = { r ->
            val id = r["_id"]
            val start = r["start_timestamp"]?.toLongOrNull()
            val end = r["end_timestamp"]?.toLongOrNull()
            val value = r[column]?.toDoubleOrNull()
            if (id == null || start == null || start <= 0L || value == null || value == 0.0) {
                null
            } else {
                SyncRecord(
                    type = type.id,
                    sourceKey = id,
                    startTime = start,
                    endTime = maxOf(end ?: start, start),
                    values = mapOf(valueKey to value),
                )
            }
        }
        return TableSpec(type, sql, mapper)
    }

    /** DBHeartRate：data_created_timestamp / heart_rate_value / heart_rate_type。 */
    private fun heartRate(table: String, type: RecordType): TableSpec {
        val sql = "SELECT _id, data_created_timestamp, heart_rate_value FROM $table " +
            "WHERE heart_rate_value > 0 ORDER BY data_created_timestamp DESC " +
            "LIMIT ${TableReader.MAX_ROWS}"
        val mapper: (Map<String, String?>) -> SyncRecord? = { r ->
            val id = r["_id"]
            val ts = r["data_created_timestamp"]?.toLongOrNull()
            val bpm = r["heart_rate_value"]?.toDoubleOrNull()
            if (id == null || ts == null || ts <= 0L || bpm == null || bpm <= 0.0) {
                null
            } else {
                SyncRecord(
                    type = type.id,
                    sourceKey = id,
                    startTime = ts,
                    endTime = ts,
                    // HC 的 HeartRateRecord 需要样本列表；单点数据就产出一个样本
                    values = mapOf(
                        RecordMapper.V_BPM to bpm,
                        "sample_0_bpm" to bpm,
                        "sample_0_at" to ts.toDouble(),
                    ),
                )
            }
        }
        return TableSpec(type, sql, mapper)
    }

    /** DBBloodPressure：measure_timestamp / systolic / diastolic，单位 mmHg。 */
    private fun bloodPressure(table: String, type: RecordType): TableSpec {
        val sql = "SELECT measure_timestamp, systolic, diastolic FROM $table " +
            "WHERE systolic > 0 AND diastolic > 0 ORDER BY measure_timestamp DESC " +
            "LIMIT ${TableReader.MAX_ROWS}"
        val mapper: (Map<String, String?>) -> SyncRecord? = { r ->
            val ts = r["measure_timestamp"]?.toLongOrNull()
            val sys = r["systolic"]?.toDoubleOrNull()
            val dia = r["diastolic"]?.toDoubleOrNull()
            if (ts == null || ts <= 0L || sys == null || dia == null) {
                null
            } else {
                SyncRecord(
                    type = type.id,
                    // 该表主键是 (ssoid, device_unique_id, measure_timestamp)，
                    // 时间戳加两个读数足以唯一标识一次测量
                    sourceKey = "${ts}_${sys.toInt()}_${dia.toInt()}",
                    startTime = ts,
                    endTime = ts,
                    values = mapOf(
                        RecordMapper.V_PRESSURE_SYS to sys,
                        RecordMapper.V_PRESSURE_DIA to dia,
                    ),
                )
            }
        }
        return TableSpec(type, sql, mapper)
    }

    /** DBBloodOxygenSaturation：blood_oxygen_saturation_value，单位百分比。 */
    private fun bloodOxygen(table: String, type: RecordType): TableSpec {
        val sql = "SELECT _id, data_created_timestamp, blood_oxygen_saturation_value FROM $table " +
            "WHERE blood_oxygen_saturation_value > 0 ORDER BY data_created_timestamp DESC " +
            "LIMIT ${TableReader.MAX_ROWS}"
        val mapper: (Map<String, String?>) -> SyncRecord? = { r ->
            val id = r["_id"]
            val ts = r["data_created_timestamp"]?.toLongOrNull()
            val v = r["blood_oxygen_saturation_value"]?.toDoubleOrNull()
            // 小于 0 或大于 100 的读数显然是脏数据，直接丢弃而不是写进 HC
            if (id == null || ts == null || ts <= 0L || v == null || v <= 0.0 || v > 100.0) {
                null
            } else {
                SyncRecord(
                    type = type.id, sourceKey = id, startTime = ts, endTime = ts,
                    values = mapOf(RecordMapper.V_PERCENT to v),
                )
            }
        }
        return TableSpec(type, sql, mapper)
    }
}
