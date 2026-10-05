package io.github.bqj6666.ohsync.core

/**
 * 跨进程通信用到的常量，集中在这里，避免散落各处后改包名时漏改。
 *
 * [APP_ID] 必须是本应用真实的包名：Hook 侧跑在 OPPO 健康进程里，那边的
 * `context.packageName` 是宿主包名，拿不到自己的，只能靠这个常量。
 */
object Ids {

    /** 本应用包名。改包名时只改这一处（gradle 与 manifest 另算）。 */
    const val APP_ID = "io.github.bqj6666.ohsync"

    /** 读取端发来数据用的数据接口。 */
    const val PROVIDER_SUFFIX = ".sync"

    /** 读取端把主进程唤醒用的广播。 */
    const val ACTION_WAKE = "$APP_ID.action.WAKE"

    /** 主进程通知读取端立即同步。 */
    const val ACTION_SYNC_NOW = "$APP_ID.action.SYNC_NOW"

    /** 主进程通知读取端设置变了。 */
    const val ACTION_CONFIG_CHANGED = "$APP_ID.action.CONFIG_CHANGED"

    /** 主进程问读取端是否在线。 */
    const val ACTION_PING = "$APP_ID.action.PING"

    /** 广播里带的口令字段名。 */
    const val EXTRA_TOKEN = "token"

    /** 被读取的目标应用（OPPO 健康）。 */
    const val TARGET_PACKAGE = "com.heytap.health"
}
