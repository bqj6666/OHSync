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
            if (!reader.hasTable(table)) { Log.w(TAG, "表 $table 不存在，跳过"); continue }
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

    /** DBSportDataStat / DBSportDataDetail 共用的日汇总形态：start/end + 一个数值列。 */
    private fun daily(table: String, type: RecordType, valueKey: String, column: String) = TableSpec(
        type,
        "SELECT _id, start_timestamp, end_timestamp, $column FROM $table " +
            "ORDER BY _id DESC LIMIT ${TableReader.MAX_ROWS}",
    ) { r ->
        val id = r.str("_id") ?: return@daily null
        val start = r.long("start_timestamp") ?: return@daily null
        val end = r.long("end_timestamp") ?: start
        val v = r.double(column) ?: return@daily null
        if (start <= 0 || v == 0.0) return@daily null
        SyncRecord(
            type = type.id, sourceKey = id, startTime = start, endTime = maxOf(end, start),
            values = mapOf(valueKey to v),
        )
    }

    /** DBHeartRate：data_created_timestamp / heart_rate_value / heart_rate_type。 */
    private fun heartRate(table: String, type: RecordType) = TableSpec(
        type,
        "SELECT _id, data_created_timestamp, heart_rate_value FROM $table " +
            "WHERE heart_rate_value > 0 ORDER BY data_created_timestamp DESC " +
            "LIMIT ${TableReader.MAX_ROWS}",
    ) { r ->
        val id = r.str("_id") ?: return@heartRate null
        val ts = r.long("data_created_timestamp") ?: return@heartRate null
        val bpm = r.double("heart_rate_value") ?: return@heartRate null
        if (ts <= 0 || bpm <= 0) return@heartRate null
        SyncRecord(
            type = type.id, sourceKey = id, startTime = ts, endTime = ts,
            values = mapOf(
                RecordMapper.V_BPM to bpm,
                "sample_0_bpm" to bpm, "sample_0_at" to ts.toDouble(),
            ),
        )
    }

    /** DBBloodPressure：measure_timestamp / systolic / diastolic。 */
    private fun bloodPressure(table: String, type: RecordType) = TableSpec(
        type,
        "SELECT measure_timestamp, systolic, diastolic FROM $table " +
            "WHERE systolic > 0 AND diastolic > 0 ORDER BY measure_timestamp DESC " +
            "LIMIT ${TableReader.MAX_ROWS}",
    ) { r ->
        val ts = r.long("measure_timestamp") ?: return@bloodPressure null
        val sys = r.double("systolic") ?: return@bloodPressure null
        val dia = r.double("diastolic") ?: return@bloodPressure null
        if (ts <= 0) return@bloodPressure null
        SyncRecord(
            type = type.id,
            sourceKey = "${ts}_${sys.toInt()}_${dia.toInt()}",
            startTime = ts, endTime = ts,
            values = mapOf(
                RecordMapper.V_PRESSURE_SYS to sys,
                RecordMapper.V_PRESSURE_DIA to dia,
            ),
        )
    }

    /** DBBloodOxygenSaturation：blood_oxygen_saturation_value，单位为百分比。 */
    private fun bloodOxygen(table: String, type: RecordType) = TableSpec(
        type,
        "SELECT _id, data_created_timestamp, blood_oxygen_saturation_value FROM $table " +
            "WHERE blood_oxygen_saturation_value > 0 ORDER BY data_created_timestamp DESC " +
            "LIMIT ${TableReader.MAX_ROWS}",
    ) { r ->
        val id = r.str("_id") ?: return@bloodOxygen null
        val ts = r.long("data_created_timestamp") ?: return@bloodOxygen null
        val v = r.double("blood_oxygen_saturation_value") ?: return@bloodOxygen null
        if (ts <= 0 || v <= 0 || v > 100) return@bloodOxygen null
        SyncRecord(
            type = type.id, sourceKey = id, startTime = ts, endTime = ts,
            values = mapOf(RecordMapper.V_PERCENT to v),
        )
    }

    private fun Map<String, String?>.str(c: String) = this[c]
    private fun Map<String, String?>.long(c: String) = this[c]?.toLongOrNull()
    private fun Map<String, String?>.double(c: String) = this[c]?.toDoubleOrNull()
}
