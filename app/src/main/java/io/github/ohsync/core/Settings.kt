package io.github.ohsync.core

import android.content.Context

/**
 * 用户可调设置。
 *
 * 存 SharedPreferences：只有两个值，（interval, windowDays），
 * 用 DataStore 要多引一个依赖和一层协程，不划算。
 */
object Settings {

    private const val PREFS = "ohsync_settings"
    private const val KEY_INTERVAL = "sync_interval_minutes"
    private const val KEY_WINDOW = "window_days"
    private const val KEY_LAST_SYNC = "last_incremental_sync_at"

    /** 同步间隔选项。0 表示只在手动点「立即同步」时才推。 */
    val INTERVAL_OPTIONS: List<Pair<Int, String>> = listOf(
        0 to "仅手动",
        15 to "15 分钟",
        30 to "30 分钟",
        60 to "1 小时",
        180 to "3 小时",
        360 to "6 小时",
        720 to "12 小时",
        1440 to "每天",
    )

    /** 时间窗口选项：决定一次回填多久的历史。 */
    val WINDOW_OPTIONS: List<Pair<Int, String>> = listOf(
        7 to "最近 7 天",
        30 to "最近 30 天",
        90 to "最近 3 个月",
        180 to "最近 6 个月",
        365 to "最近 1 年",
        3650 to "全部",
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun intervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL, DEFAULT_INTERVAL)

    fun setIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL, minutes).apply()
        // 立刻告知读取端，否则要等兜底刷新（最长 1 小时）才生效
        TriggerSender.configChanged(context)
    }

    fun windowDays(context: Context): Int =
        prefs(context).getInt(KEY_WINDOW, DEFAULT_WINDOW)

    fun setWindowDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_WINDOW, days).apply()
        TriggerSender.configChanged(context)
    }

    fun intervalLabel(context: Context): String =
        INTERVAL_OPTIONS.firstOrNull { it.first == intervalMinutes(context) }?.second
            ?: "${intervalMinutes(context)} 分钟"

    fun windowLabel(context: Context): String =
        WINDOW_OPTIONS.firstOrNull { it.first == windowDays(context) }?.second
            ?: "最近 ${windowDays(context)} 天"

    /**
     * 上次增量同步的时间点。
     *
     * 周期推送只取这个时间之后的数据 —— 否则每小时都要把整个窗口
     * （1.5 万条量级）重读重写一遍，纯属浪费。
     */
    fun lastSyncAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SYNC, 0L)

    fun setLastSyncAt(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_LAST_SYNC, at).apply()
    }

    /** 默认 1 小时：够及时，也不至于反复扫库。 */
    const val DEFAULT_INTERVAL = 60

    /** 默认回填 3 个月。 */
    const val DEFAULT_WINDOW = 90
}
