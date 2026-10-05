package io.github.bqj6666.ohsync.hook

import android.util.Log

/**
 * 证据探针：把还没把握的字段的**真实取值**打到日志里。
 *
 * 为什么需要它：单位与枚举含义猜错会让 Health Connect 里出现脏数据，比缺数据更难排查。
 * 与其猜，不如把原始样本打出来，看过之后再决定映射。
 *
 * 只读，不改任何东西。
 */
object Probe {

    private const val TAG = "OHSyncProbe"

    fun run(reader: TableReader) {
        sleepPieceTypes(reader)
        sleepNightTotals(reader)
        sleepSessions(reader)
        sportSamples(reader)
        bodySamples(reader)
    }

    /**
     * 睡眠分段：DBSleepPiece.type 是 OPPO 的私有枚举，全量反编译后追完所有引用链
     * （SleepPieceDao / SleepPieceStore / 同步层）都找不到常量定义或分支语义，
     * 只当作不透明 int 搬运。所以先看真实分布。
     */
    private fun sleepPieceTypes(reader: TableReader) {
        val table = "DBSleepPiece"
        if (reader.tableNames().none { it == table }) return
        val rows = reader.query(
            "SELECT type, COUNT(*) AS cnt, MIN(start_timestamp) AS first_ts, " +
                "MAX(end_timestamp) AS last_ts, " +
                "AVG(end_timestamp - start_timestamp) AS avg_len " +
                "FROM $table GROUP BY type ORDER BY type"
        )
        if (rows.isEmpty()) {
            Log.w(TAG, "$table 无数据")
            return
        }
        Log.i(TAG, "$table.type 取值分布（${rows.size} 种）：")
        for (r in rows) {
            Log.i(
                TAG,
                "  type=${r["type"]} 条数=${r["cnt"]} 平均段长=" +
                    "${(r["avg_len"]?.toDoubleOrNull()?.div(60000.0))?.toInt()}分钟 " +
                    "范围=${fmt(r["first_ts"])}~${fmt(r["last_ts"])}",
            )
        }
    }

    /** 步数/距离/卡路里的原始值：用于确认距离与卡路里的单位，确认前不写入 HC。 */
    private fun sportSamples(reader: TableReader) {
        for (table in reader.tableNames()) {
            val cols = reader.columns(table)
            if ("total_steps" !in cols && "steps" !in cols) continue
            val picked = listOf(
                "_id", "start_time", "start_timestamp", "end_time", "end_timestamp",
                "total_steps", "steps", "total_distance", "distance",
                "total_calories", "calories", "timezone",
            ).filter { it in cols }
            if (picked.isEmpty()) continue
            val rows = reader.query(
                "SELECT ${picked.joinToString()} FROM $table " +
                    "ORDER BY 1 DESC LIMIT 3"
            )
            Log.i(TAG, "$table 样本（列：${picked.joinToString()}）：")
            rows.forEachIndexed { i, r ->
                Log.i(TAG, "  [$i] " + picked.joinToString { "$it=${r[it]}" })
            }
        }
    }

    /** 体重/体脂表的样本，确认 measurement_timestamp 单位与数值量级。 */
    private fun bodySamples(reader: TableReader) {
        for (table in reader.tableNames()) {
            val cols = reader.columns(table)
            if ("weight" !in cols || "measurement_timestamp" !in cols) continue
            val picked = listOf(
                "weight_id", "measurement_timestamp", "weight", "body_fat_rate", "height",
            ).filter { it in cols }
            val rows = reader.query(
                "SELECT ${picked.joinToString()} FROM $table ORDER BY measurement_timestamp DESC LIMIT 3"
            )
            Log.i(TAG, "$table 样本：")
            rows.forEachIndexed { i, r ->
                Log.i(TAG, "  [$i] " + picked.joinToString { "$it=${r[it]}" })
            }
        }
    }

    private fun fmt(millis: String?): String {
        val v = millis?.toLongOrNull() ?: return "-"
        return if (v <= 0) "-" else java.time.Instant.ofEpochMilli(v).toString()
    }

    /**
     * 按「日期 + type」汇总分段时长。
     *
     * 用途：拿它和 OPPO 健康界面上显示的睡眠阶段对账，确定 type 的语义。
     * 例：界面显示某晚 深睡68分/浅睡262分/快速眼动106分/清醒2分，
     * 那么当晚四种 type 的分钟数就能一一对应上，映射不再是猜。
     */
    private fun sleepNightTotals(reader: TableReader) {
        if (reader.tableNames().none { it == "DBSleepPiece" }) return
        val rows = reader.query(
            "SELECT date(start_timestamp/1000, 'unixepoch', '+8 hours') AS d, type, " +
                "COUNT(*) AS cnt, SUM(end_timestamp - start_timestamp)/60000 AS mins " +
                "FROM DBSleepPiece GROUP BY d, type ORDER BY d DESC, type LIMIT 24"
        )
        Log.i(TAG, "DBSleepPiece 按夜汇总（日期 / type / 段数 / 分钟）：")
        for (r in rows) {
            Log.i(TAG, "  ${r["d"]}  type=${r["type"]}  段数=${r["cnt"]}  共 ${r["mins"]} 分钟")
        }
    }

    /** DBSleepTable 的睡眠段（判断夜间睡眠与小睡如何区分）。 */
    private fun sleepSessions(reader: TableReader) {
        if (reader.tableNames().none { it == "DBSleepTable" }) return
        val cols = reader.columns("DBSleepTable")
        val picked = listOf(
            "start_time", "end_time", "sleep_type", "sleep_state", "device_type",
        ).filter { it in cols }
        if (picked.isEmpty()) return
        val rows = reader.query(
            "SELECT ${picked.joinToString()} FROM DBSleepTable ORDER BY start_time DESC LIMIT 6"
        )
        Log.i(TAG, "DBSleepTable 最近 6 段（列：${picked.joinToString()}）：")
        for (r in rows) {
            Log.i(TAG, "  " + picked.joinToString { "$it=${r[it]}" })
        }
    }
}
