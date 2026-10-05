package io.github.bqj6666.ohsync.hook

import android.net.Uri
import android.util.Log
import io.github.bqj6666.ohsync.core.Ids

/** 主进程下发的配置：口令、同步间隔、时间窗口。 */
data class HookConfig(
    val token: String,
    val intervalMinutes: Int,
    val windowDays: Int,
    val backfillRequested: Boolean,
    val lastSyncAt: Long,
)

/**
 * 从主进程取配置。
 *
 * Hook 侧跑在 OPPO 健康进程里，没有自己的界面，用户的设置只能这样取。
 * 取不到（主进程没开、provider 被拦）时返回 null，调用方按保守默认值走。
 */
object RemoteConfig {

    private const val TAG = "OHSyncCfg"
    // 包名与动作名集中定义在 core/Ids.kt，避免改包名时漏改
    private const val OHSYNC_PACKAGE = Ids.APP_ID
    private const val PROVIDER_SUFFIX = Ids.PROVIDER_SUFFIX

    @Volatile private var cached: HookConfig? = null
    @Volatile private var lastFetchAt = 0L

    /** 缓存 5 分钟，避免每轮都跨进程取。 */
    fun fetch(force: Boolean = false): HookConfig? {
        val now = System.currentTimeMillis()
        if (!force) {
            cached?.takeIf { now - lastFetchAt < 300_000L }?.let { return it }
        }
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context ?: return if (force) null else cached
        val got = try {
            val b = ctx.contentResolver.call(
                Uri.parse("content://$OHSYNC_PACKAGE$PROVIDER_SUFFIX"),
                "config", null, null,
            )
            val token = b?.getString("token")
            if (token.isNullOrEmpty()) {
                null
            } else {
                HookConfig(
                    token = token,
                    intervalMinutes = b.getInt("intervalMinutes", 60),
                    windowDays = b.getInt("windowDays", 90),
                    backfillRequested = b.getBoolean("backfillRequested", false),
                    lastSyncAt = b.getLong("lastSyncAt", 0L),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "取配置失败（主进程可能未启动）", t)
            null
        }
        if (got != null) {
            cached = got
            lastFetchAt = now
            TokenHolder.pair(got.token)
        }
        // force=true 表示调用方要的是「此刻主进程在不在」这个事实，
        // 此时**不能**回退到旧缓存 —— 否则进程已经死了也会被判为就绪，
        // 结果就是推送全部失败（踩过）。
        return if (force) got else got ?: cached
    }

    /** 告诉主进程本次推送完成的时间点，下次增量只取这之后的数据。 */
    fun reportSyncDone(at: Long) {
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context ?: return
        runCatching {
            ctx.contentResolver.call(
                Uri.parse("content://$OHSYNC_PACKAGE$PROVIDER_SUFFIX"),
                "syncDone", null,
                android.os.Bundle().apply { putLong("at", at) },
            )
        }
    }

    /**
     * 回应应用界面的在线确认。
     *
     * 应用打开时发一次广播问「读取端在吗」，这里回一个 provider 调用。
     * 事件驱动，只在用户看界面时发生一次，不是轮询。
     */
    fun ping() {
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context ?: return
        runCatching {
            ctx.contentResolver.call(
                Uri.parse("content://$OHSYNC_PACKAGE$PROVIDER_SUFFIX"),
                "ping", TokenHolder.token, null,
            )
        }
    }
}
