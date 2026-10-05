package io.github.bqj6666.ohsync.core

/**
 * 同步类型枚举。Hook 侧（读）与主进程侧（写 HC）共用这一份定义，
 * 保证两端对同一个类型 ID 的理解永远一致。
 *
 * [hcRecord] 为 null 表示 Health Connect 无对应记录类型 —— 按规则废弃，并在 UI 上列出。
 *
 * hcRecord 的取值逐一对照 androidx.health.connect:connect-client:1.1.0 的
 * androidx.health.connect.client.records 包内实际存在的类，不凭记忆写。
 */
enum class RecordType(val id: String, val hcRecord: String?, val label: String) {
    STEPS("steps", "StepsRecord", "步数"),
    STEPS_CADENCE("steps_cadence", "StepsCadenceRecord", "步频"),
    DISTANCE("distance", "DistanceRecord", "距离"),
    ACTIVE_CALORIES("active_calories", "ActiveCaloriesBurnedRecord", "活动卡路里"),
    TOTAL_CALORIES("total_calories", "TotalCaloriesBurnedRecord", "总卡路里"),
    HEART_RATE("heart_rate", "HeartRateRecord", "心率"),
    RESTING_HEART_RATE("resting_heart_rate", "RestingHeartRateRecord", "静息心率"),
    HRV("hrv", "HeartRateVariabilityRmssdRecord", "心率变异性"),
    // 睡眠。HC 1.1.0 没有独立的 SleepStageRecord，但 SleepSessionRecord 自带
    // List<Stage>，分段直接写进同一条记录，所以不需要单独的阶段类型。
    SLEEP_SESSION("sleep_session", "SleepSessionRecord", "睡眠"),
    EXERCISE_SESSION("exercise_session", "ExerciseSessionRecord", "运动记录"),
    BLOOD_PRESSURE("blood_pressure", "BloodPressureRecord", "血压"),
    BLOOD_OXYGEN("blood_oxygen", "OxygenSaturationRecord", "血氧"),
    BLOOD_GLUCOSE("blood_glucose", "BloodGlucoseRecord", "血糖"),
    BODY_TEMPERATURE("body_temperature", "BodyTemperatureRecord", "体温"),
    SKIN_TEMPERATURE("skin_temperature", "SkinTemperatureRecord", "皮温"),
    BASAL_BODY_TEMPERATURE("basal_body_temperature", "BasalBodyTemperatureRecord", "基础体温"),
    WEIGHT("weight", "WeightRecord", "体重"),
    BODY_FAT("body_fat", "BodyFatRecord", "体脂"),
    LEAN_BODY_MASS("lean_body_mass", "LeanBodyMassRecord", "去脂体重"),
    BODY_WATER("body_water", "BodyWaterMassRecord", "身体水分"),
    BONE_MASS("bone_mass", "BoneMassRecord", "骨量"),
    HEIGHT("height", "HeightRecord", "身高"),
    RESPIRATORY_RATE("respiratory_rate", "RespiratoryRateRecord", "呼吸率"),
    MENTAL_HEALTH_SESSION("mental_health", "MindfulnessSessionRecord", "放松训练"),
    BASAL_METABOLIC_RATE("bmr", "BasalMetabolicRateRecord", "基础代谢"),
    VO2_MAX("vo2_max", "Vo2MaxRecord", "最大摄氧量"),
    ELEVATION_GAINED("elevation_gained", "ElevationGainedRecord", "累计爬升"),
    FLOORS_CLIMBED("floors_climbed", "FloorsClimbedRecord", "爬楼层数"),
    SPEED("speed", "SpeedRecord", "速度"),
    POWER("power", "PowerRecord", "功率"),
    PEDALING_CADENCE("pedaling_cadence", "CyclingPedalingCadenceRecord", "踏频"),
    HYDRATION("hydration", "HydrationRecord", "饮水量"),
    NUTRITION("nutrition", "NutritionRecord", "营养摄入"),
    MENSTRUAL_CYCLE("menstrual_cycle", "MenstruationPeriodRecord", "月经周期"),
    INTERMENSTRUAL_BLEEDING("intermenstrual_bleeding", "IntermenstrualBleedingRecord", "经间期出血"),
    CERVICAL_MUCUS("cervical_mucus", "CervicalMucusRecord", "宫颈黏液"),
    SEXUAL_ACTIVITY("sexual_activity", "SexualActivityRecord", "性活动"),

    // ---- 已判定废弃：Health Connect 1.1.0 无对应记录类型 ----
    STRESS("stress", null, "压力"),
    HEARING_HEALTH("hearing_health", null, "听力健康"),
    OSA_RESULT("osa_result", null, "OSA 检测"),
    SENSOR_OSA("sensor_osa", null, "传感器鼾声"),
    SNORE_FEATURE("snore_feature", null, "鼾声特征"),
    PHYSICAL_FITNESS("physical_fitness", null, "体测评分"),
    BLOOD_SUGAR_WARNING("blood_sugar_warning", null, "血糖预警"),
    HEART_RATE_WARNING("heart_rate_warning", null, "心率预警"),
    SPO2_WARNING("spo2_warning", null, "血氧预警"),
    ATRIAL_FIBRIL("atrial_fibril", null, "房颤检测"),
    BREATH_RATE("breath_rate", null, "呼吸频率"),
    EXERCISE_LOAD("exercise_load", null, "运动负荷"),
    EXERCISE_INTENSITY("exercise_intensity", null, "运动强度"),
    RECOVERY_HEART_RATE("recovery_heart_rate_raw", null, "恢复心率"),
    SEDENTARY("sedentary", null, "久坐提醒"),
    SLEEP_INDEX("sleep_index", null, "睡眠评分"),
    SLEEP_ADVICE("sleep_advice", null, "睡眠建议"),
    ECG("ecg", null, "心电"),
    UNKNOWN("unknown", null, "未识别");

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): RecordType = byId[id] ?: UNKNOWN

        /** 会写入 Health Connect 的类型。 */
        val syncable: List<RecordType> = entries.filter { it.hcRecord != null }

        /** 按规则废弃的类型（UNKNOWN 除外）。 */
        val discarded: List<RecordType> = entries.filter { it.hcRecord == null && it != UNKNOWN }
    }
}
