package io.github.ohsync.core

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Power
import java.time.Instant
import java.time.ZoneOffset

/**
 * 原始行 -> Health Connect Record 的唯一翻译点。
 *
 * 单位约定（由 Hook 侧保证读出来就是这套单位，主进程不再猜）：
 *   count 无量纲 / distance_m 米 / calories 千卡 / mass_kg 千克
 *   percent 0..100 / pressure mmHg / bmr 千卡每天
 *
 * 不认识的类型、缺关键字段 -> 抛异常，由 SyncEngine 计入失败并跳过，
 * 绝不静默把错数据写进 HC。
 */
@OptIn(ExperimentalMindfulnessSessionApi::class)
object RecordMapper {
    const val V_COUNT = "count"
    const val V_DISTANCE_M = "distance_m"
    const val V_CALORIES = "calories"
    const val V_MASS_KG = "mass_kg"
    const val V_PERCENT = "percent"
    const val V_PRESSURE_SYS = "systolic"
    const val V_PRESSURE_DIA = "diastolic"
    const val V_BPM = "bpm"
    const val V_RMSSD = "rmssd_ms"
    const val V_BREATHS_PER_MIN = "breaths_per_min"
    const val V_FLOORS = "floors"
    const val V_BMR = "bmr"

    private val DEVICE = Device(Device.TYPE_PHONE, null, null)

    /**
     * Metadata 的主构造器在 HC 1.1.0 里是 internal，外部只能用 Companion 的工厂方法。
     * dataOrigin 本来就由 Health Connect 按调用方包名自动填，不需要（也改不了）。
     */
    fun metadata(clientRecordId: String): Metadata =
        Metadata.autoRecordedWithId(clientRecordId, DEVICE)

    fun map(r: SyncRecord): Record {
        val start = Instant.ofEpochMilli(r.startTime)
        val end = Instant.ofEpochMilli(r.endTime)
        val zo = ZoneOffset.ofTotalSeconds(r.zoneOffsetSeconds ?: 0)
        val cid = HcClient.clientRecordId(r.recordType, r.sourceKey)

        return when (r.recordType) {
            RecordType.STEPS -> StepsRecord(
                start, zo, end, zo, r.long(V_COUNT), metadata(cid))

            RecordType.DISTANCE -> DistanceRecord(
                start, zo, end, zo, Length.meters(r.double(V_DISTANCE_M)), metadata(cid))

            RecordType.ACTIVE_CALORIES -> ActiveCaloriesBurnedRecord(
                start, zo, end, zo, Energy.kilocalories(r.double(V_CALORIES)), metadata(cid))

            RecordType.TOTAL_CALORIES -> TotalCaloriesBurnedRecord(
                start, zo, end, zo, Energy.kilocalories(r.double(V_CALORIES)), metadata(cid))

            RecordType.HEART_RATE -> HeartRateRecord(
                start, zo, end, zo, r.samples().map { HeartRateRecord.Sample(it.second, it.first.toLong()) },
                metadata(cid))

            RecordType.RESTING_HEART_RATE -> RestingHeartRateRecord(
                start, zo, r.long(V_BPM), metadata(cid))

            RecordType.HRV -> HeartRateVariabilityRmssdRecord(
                start, zo, r.double(V_RMSSD), metadata(cid))

            // 睡眠：分段写进同一条记录，HC 原生就能画出阶段图
            RecordType.SLEEP_SESSION -> SleepSessionRecord(
                start, zo, end, zo, metadata(cid),
                null, null,
                r.stages.map {
                    SleepSessionRecord.Stage(
                        startTime = Instant.ofEpochMilli(it.start),
                        endTime = Instant.ofEpochMilli(it.end),
                        stage = it.stage,
                    )
                },
            )

            RecordType.BLOOD_PRESSURE -> BloodPressureRecord(
                start, zo, metadata(cid),
                Pressure.millimetersOfMercury(r.double(V_PRESSURE_SYS)),
                Pressure.millimetersOfMercury(r.double(V_PRESSURE_DIA)),
                r.metaInt("location", 0),
            )

            RecordType.BLOOD_OXYGEN -> OxygenSaturationRecord(
                start, zo, Percentage(r.double(V_PERCENT)), metadata(cid))

            RecordType.WEIGHT -> WeightRecord(
                start, zo, Mass.kilograms(r.double(V_MASS_KG)), metadata(cid))

            RecordType.BODY_FAT -> BodyFatRecord(
                start, zo, Percentage(r.double(V_PERCENT)), metadata(cid))

            RecordType.HEIGHT -> HeightRecord(
                start, zo, Length.meters(r.double("height_m")), metadata(cid))

            RecordType.RESPIRATORY_RATE -> RespiratoryRateRecord(
                start, zo, r.double(V_BREATHS_PER_MIN), metadata(cid))

            RecordType.ELEVATION_GAINED -> ElevationGainedRecord(
                start, zo, end, zo, Length.meters(r.double(V_DISTANCE_M)), metadata(cid))

            RecordType.FLOORS_CLIMBED -> FloorsClimbedRecord(
                start, zo, end, zo, r.double(V_FLOORS), metadata(cid))

            RecordType.BASAL_METABOLIC_RATE -> BasalMetabolicRateRecord(
                start, zo, Power.kilocaloriesPerDay(r.double(V_BMR)), metadata(cid))

            RecordType.MENTAL_HEALTH_SESSION -> MindfulnessSessionRecord(
                start, zo, end, zo, metadata(cid),
                r.metaInt("type", MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING),
                r.metadata["title"], r.metadata["notes"],
            )

            else -> throw IllegalArgumentException("no HC mapping for ${r.recordType.id}")
        }
    }

    private fun SyncRecord.double(key: String): Double =
        values[key] ?: throw IllegalArgumentException("missing '$key' for ${recordType.id}")

    private fun SyncRecord.long(key: String): Long =
        values[key]?.toLong() ?: throw IllegalArgumentException("missing '$key' for ${recordType.id}")

    private fun SyncRecord.metaInt(key: String, default: Int): Int =
        metadata[key]?.toIntOrNull() ?: default

    /** 心率样本：还原 values 里的 sample_<i>_bpm / sample_<i>_at 键。 */
    private fun SyncRecord.samples(): List<Pair<Double, Instant>> {
        val out = ArrayList<Pair<Double, Instant>>()
        val indices = values.keys.mapNotNull { k ->
            SAMPLE_RE.matchEntire(k)?.groupValues?.get(1)?.toIntOrNull()
        }.distinct().sorted()
        for (i in indices) {
            val bpm = values["sample_${i}_bpm"] ?: continue
            val at = values["sample_${i}_at"] ?: continue
            out.add(bpm to Instant.ofEpochMilli(at.toLong()))
        }
        if (out.isEmpty()) {
            // 兜底：很多统计表只有整段平均值
            out.add(double(V_BPM) to Instant.ofEpochMilli(startTime))
        }
        return out
    }

    private val SAMPLE_RE = Regex("^sample_(\\d+)_bpm$")
}
