package io.github.ohsync.hook

import io.github.ohsync.core.RecordType

/**
 * 表名 -> RecordType 的语义规则。返回 null 表示认不出来，交给上层记入未识别清单。
 *
 * 关键词按长度倒序匹配，保证 "heartratedatastat" 命中 HEART_RATE 而不是被 "heartrate" 抢走。
 */
internal object TableRules {

    fun resolve(key: String): RecordType? {
        EXACT[key]?.let { return it }
        for (e in ORDERED) if (key.contains(e.first)) return e.second
        return null
    }

    private val EXACT: Map<String, RecordType> = mapOf(
        "dbheartrate" to RecordType.HEART_RATE,
        "dbheartratedatastattable" to RecordType.HEART_RATE,
        "dbheartratewarning" to RecordType.HEART_RATE_WARNING,
        "dbhrvtable" to RecordType.HRV,
        "dbsleeptable" to RecordType.SLEEP_SESSION,
        "dbsleepdatastattable" to RecordType.SLEEP_SESSION,
        "dbrrinterval" to RecordType.HRV,
        "dbonetimesport" to RecordType.EXERCISE_SESSION,
        "dbonetimesportstat" to RecordType.EXERCISE_SESSION,
        "dbbloodpressure" to RecordType.BLOOD_PRESSURE,
        "dbbloodoxygensaturation" to RecordType.BLOOD_OXYGEN,
        "dbbloodsugar" to RecordType.BLOOD_GLUCOSE,
        "dbrelax" to RecordType.MENTAL_HEALTH_SESSION,
        "dbrelaxstat" to RecordType.MENTAL_HEALTH_SESSION,
        "dbbreathrate" to RecordType.RESPIRATORY_RATE,
        "dbbreathratestat" to RecordType.RESPIRATORY_RATE,
        "dbphysiquemeasureall" to RecordType.WEIGHT,
        "dbphysiquemeasuredetail" to RecordType.BODY_FAT,
        "dbhearinghealthtable" to RecordType.HEARING_HEALTH,
        "dbosaresult" to RecordType.OSA_RESULT,
        "dbsensorosa" to RecordType.SENSOR_OSA,
        "dbsleepadvice" to RecordType.SLEEP_ADVICE,
        "dbsleepindex" to RecordType.SLEEP_INDEX,
        "dbsedentary" to RecordType.SEDENTARY,
        "dbspo2warning" to RecordType.SPO2_WARNING,
        "dbecgregord" to RecordType.ECG,
        "dbphysicalfitness" to RecordType.PHYSICAL_FITNESS,
        "dbrecoveryheartrate" to RecordType.RECOVERY_HEART_RATE,
        "dbexerciseintensity" to RecordType.EXERCISE_INTENSITY,
        "dbexerciseload" to RecordType.EXERCISE_LOAD,
        "dbsnorefeature" to RecordType.SNORE_FEATURE,
        // 步数日汇总表与明细表（列名实测：total_steps / start_time）
        "dbsportdatastat" to RecordType.STEPS,
        "dbsportdatadetail" to RecordType.STEPS,
        // 体重体脂（实测列：weight / body_fat_rate / height / measurement_timestamp）
        "dbweightbodyfattable" to RecordType.WEIGHT,
    )

    private val ORDERED: List<Pair<String, RecordType>> = listOf(
        "bloodoxygensaturationdatastat" to RecordType.BLOOD_OXYGEN,
        "sleepheartratestat" to RecordType.HEART_RATE,
        "sugarwarning" to RecordType.BLOOD_SUGAR_WARNING,
        "spo2warning" to RecordType.SPO2_WARNING,
        "heartratewarning" to RecordType.HEART_RATE_WARNING,
        "hearinghealthstat" to RecordType.HEARING_HEALTH,
        "physicalmentalstat" to RecordType.PHYSICAL_FITNESS,
        "physiquemeasuredetail" to RecordType.BODY_FAT,
        "physiquemeasureall" to RecordType.WEIGHT,
        "onetimesportstat" to RecordType.EXERCISE_SESSION,
        "exerciseintensity" to RecordType.EXERCISE_INTENSITY,
        "exerciseload" to RecordType.EXERCISE_LOAD,
        "recoveryheartrate" to RecordType.RECOVERY_HEART_RATE,
        "heartratedatastat" to RecordType.HEART_RATE,
        "bloodoxygensaturation" to RecordType.BLOOD_OXYGEN,
        "bloodpressurestat" to RecordType.BLOOD_PRESSURE,
        "sleepdatastat" to RecordType.SLEEP_SESSION,
        "snorefeature" to RecordType.SNORE_FEATURE,
        "sleeprrinterval" to RecordType.HRV,
        "relaxstat" to RecordType.MENTAL_HEALTH_SESSION,
        "breathratestat" to RecordType.RESPIRATORY_RATE,
        "physicalfitness" to RecordType.PHYSICAL_FITNESS,
        "sugarstat" to RecordType.BLOOD_GLUCOSE,
        "sleepadvice" to RecordType.SLEEP_ADVICE,
        "sleepindex" to RecordType.SLEEP_INDEX,
        "osaresult" to RecordType.OSA_RESULT,
        "sensorosa" to RecordType.SENSOR_OSA,
        "sleeptable" to RecordType.SLEEP_SESSION,
        "sleepmainstat" to RecordType.SLEEP_SESSION,
        "heartrate" to RecordType.HEART_RATE,
        "bloodpressure" to RecordType.BLOOD_PRESSURE,
        "bloodoxygen" to RecordType.BLOOD_OXYGEN,
        "bloodsugar" to RecordType.BLOOD_GLUCOSE,
        "onetimesport" to RecordType.EXERCISE_SESSION,
        "breathrate" to RecordType.RESPIRATORY_RATE,
        "hrvtable" to RecordType.HRV,
        "ecgregord" to RecordType.ECG,
        "relax" to RecordType.MENTAL_HEALTH_SESSION,
        "sedentary" to RecordType.SEDENTARY,
        "stress" to RecordType.STRESS,
        "hearing" to RecordType.HEARING_HEALTH,
        "physique" to RecordType.WEIGHT,
    ).sortedByDescending { it.first.length }
}
