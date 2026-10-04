package io.github.ohsync.hook

import android.util.Log
import io.github.ohsync.core.RecordType
import io.github.ohsync.core.SleepStage
import io.github.ohsync.core.SyncRecord
import androidx.health.connect.client.records.SleepSessionRecord

/**
 * 把 OPPO 的睡眠分段拼成 Health Connect 的 SleepSessionRecord（含 stages）。
 *
 * DBSleepPiece 是一段一行（start_timestamp / end_timestamp / type），
 * 但没有「这一晚」的会话表，所以按时间间隔切会话：间隔超过 [GAP_MS] 视为另一段睡眠。
 * 实测夜间睡眠与小睡相隔数小时，这样切能自然分开。
 *
 * type -> HC 阶段 的映射是**对账出来的**，不是猜的（2026-10-04 实测）：
 *   type=0  1 段 2 分钟    <- 界面「清醒 1 次 | 2 分钟」
 *   type=2  7 段 68 分钟   <- 界面「深睡 1 小时 8 分钟」
 *   type=3  10 段 119 分钟 <- 界面「快速眼动 25%」（实测占比 24.9%）
 *   type=4  18 段 288 分钟 <- 界面「浅睡 59%」（实测占比 60.4%）
 */
object SleepBuilder {

    private const val TAG = "OHSyncSleep"
    private const val TABLE = "DBSleepPiece"

    /** 超过这个间隔就算另一段睡眠。 */
    private const val GAP_MS = 2 * 3600_000L

    /** 太短的片段不当作一次睡眠。 */
    private const val MIN_SESSION_MS = 10 * 60_000L

    private const val WINDOW_DAYS = 90L

    private val STAGE_OF_TYPE: Map<Int, Int> = mapOf(
        0 to SleepSessionRecord.STAGE_TYPE_AWAKE,
        2 to SleepSessionRecord.STAGE_TYPE_DEEP,
        3 to SleepSessionRecord.STAGE_TYPE_REM,
        4 to SleepSessionRecord.STAGE_TYPE_LIGHT,
    )

    fun build(reader: TableReader): List<SyncRecord> {
        if (reader.tableNames().none { it == TABLE }) return emptyList()
        val since = System.currentTimeMillis() - WINDOW_DAYS * 86_400_000L
        val rows = reader.query(
            "SELECT start_timestamp, end_timestamp, type FROM $TABLE " +
                "WHERE end_timestamp > start_timestamp AND start_timestamp >= $since " +
                "ORDER BY start_timestamp"
        )
        if (rows.isEmpty()) {
            Log.w(TAG, "$TABLE 无数据")
            return emptyList()
        }

        val pieces = rows.mapNotNull { r ->
            val s = r["start_timestamp"]?.toLongOrNull() ?: return@mapNotNull null
            val e = r["end_timestamp"]?.toLongOrNull() ?: return@mapNotNull null
            val t = r["type"]?.toIntOrNull() ?: return@mapNotNull null
            val stage = STAGE_OF_TYPE[t] ?: return@mapNotNull null
            Triple(s, e, stage)
        }.sortedBy { it.first }

        val out = ArrayList<SyncRecord>()
        var i = 0
        while (i < pieces.size) {
            // 先划出这一段睡眠包含哪些 piece，再定它的边界
            var j = i
            var sessionEnd = pieces[i].second
            while (j + 1 < pieces.size && pieces[j + 1].first - sessionEnd <= GAP_MS) {
                j++
                sessionEnd = maxOf(sessionEnd, pieces[j].second)
            }
            val sessionStart = pieces[i].first
            val group = pieces.subList(i, j + 1)
            val stages = buildStages(group, sessionStart, sessionEnd)

            if (sessionEnd - sessionStart >= MIN_SESSION_MS && stages.isNotEmpty()) {
                out += SyncRecord(
                    type = RecordType.SLEEP_SESSION.id,
                    sourceKey = "sleep:$sessionStart",
                    startTime = sessionStart,
                    endTime = sessionEnd,
                    stages = stages,
                )
            }
            i = j + 1
        }
        Log.i(TAG, "拼出 ${out.size} 段睡眠，共 ${pieces.size} 个分段")
        out.take(3).forEach { r ->
            Log.i(TAG, "  会话 ${r.startTime}~${r.endTime}，${r.stages.size} 个阶段")
        }
        return out
    }

    /**
     * 把一组 piece 变成 HC 可接受的 stages。
     *
     * Health Connect 对 stages 有硬校验：必须按时间有序、互不重叠、且完全落在
     * 会话区间内，否则构造 SleepSessionRecord 时直接抛 IllegalArgumentException。
     * 所以这里逐个裁剪到 [sessionStart, sessionEnd]，并串行推进保证不重叠。
     */
    private fun buildStages(
        group: List<Triple<Long, Long, Int>>,
        sessionStart: Long,
        sessionEnd: Long,
    ): List<SleepStage> {
        val out = ArrayList<SleepStage>(group.size)
        var cursor = sessionStart
        for ((s, e, stage) in group) {
            val start = maxOf(s, cursor, sessionStart)
            val end = minOf(e, sessionEnd)
            if (end - start >= 60_000L) {
                out += SleepStage(start, end, stage)
                cursor = end
            }
        }
        return out
    }
}
