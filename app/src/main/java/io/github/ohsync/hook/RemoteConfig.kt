package io.github.ohsync.hook

import android.net.Uri
import android.util.Log

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
    private const val OHSYNC_PACKAGE = "io.github.ohsync"
    private const val PROVIDER_SUFFIX = ".sync"

    @Volatile private var cached: HookConfig? = null
    @Volatile private var lastFetchAt = 0L

    /** 缓存 5 分钟，避免每轮都跨进程取。 */
    fun fetch(force: Boolean = false): HookConfig? {
        val now = System.currentTimeMillis()
        if (!force) {
            cached?.takeIf { now - lastFetchAt < 300_000L }?.let { return it }
        }
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context ?: return cached
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
        return got ?: cached
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

    /** 告诉主进程「这次手动同步做完了」，避免下一轮又触发。 */
    fun reportBackfillDone() {
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context ?: return
        runCatching {
            ctx.contentResolver.call(
                Uri.parse("content://$OHSYNC_PACKAGE$PROVIDER_SUFFIX"),
                "backfillDone", null, null,
            )
        }
    }
}
