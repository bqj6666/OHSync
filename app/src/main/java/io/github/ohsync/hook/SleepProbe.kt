package io.github.ohsync.hook

import android.util.Log

/**
 * 睡眠分段的自检探针。
 *
 * 背景：OPPO 的 DBSleepPiece.type 是它自己的私有枚举，jadx 全量反编译后追完
 * 所有引用链（SleepPieceDao / SleepPieceStore / 同步层）都找不到任何常量定义或
 * 分支语义，只当作不透明 int 搬运。猜错会让 Health Connect 里显示错误的睡眠阶段，
 * 比不显示更难排查，所以这里只观测真实取值分布。
 *
 * 拿到真实分布后再一次性写映射，不要凭猜测填。
 */
object SleepProbe {

    private const val TAG = "OHSyncSleep"
    private const val TABLE = "DBSleepPiece"

    fun run(reader: TableReader) {
        if (!reader.hasTable(TABLE)) { Log.w(TAG, "$TABLE 不存在"); return }

        val rows = reader.query(
            "SELECT type, COUNT(*) AS cnt, MIN(start_timestamp) AS first_ts, " +
                "MAX(end_timestamp) AS last_ts FROM $TABLE GROUP BY type ORDER BY type"
        )
        if (rows.isEmpty()) { Log.w(TAG, "$TABLE 里没有数据"); return }

        Log.i(TAG, "$TABLE 的 type 取值分布（共 ${rows.size} 种）：")
        for (r in rows) {
            val type = r["type"]
            val cnt = r["cnt"]
            val first = r["first_ts"]?.toLongOrNull() ?: 0
            val last = r["last_ts"]?.toLongOrNull() ?: 0
            Log.i(
                TAG,
                "  type=$type  条数=$cnt  最早=${fmt(first)}  最晚=${fmt(last)}" +
                    "  平均段长=${avgSegmentMs(reader, type)}"
            )
        }
        Log.i(TAG, "映射表尚未确认，请依据以上分布补 TableRules.sleepStage()")
    }

    /** 某个 type 的平均段长（毫秒）—— 辅助判断哪些值是真实睡眠阶段。 */
    private fun avgSegmentMs(reader: TableReader, type: String?): String {
        val r = reader.query(
            "SELECT AVG(end_timestamp - start_timestamp) AS d FROM $TABLE WHERE type='${type.orEmpty()}'"
        ).firstOrNull()
        val d = r?.get("d")?.toDoubleOrNull() ?: return "-"
        return "${(d / 60000.0).toInt()} 分钟"
    }

    private fun fmt(millis: Long): String =
        if (millis <= 0) "-" else java.time.Instant.ofEpochMilli(millis).toString()
}
