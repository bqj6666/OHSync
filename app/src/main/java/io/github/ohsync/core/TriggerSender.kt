package io.github.ohsync.core

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 主进程 → 读取端的事件通知。
 *
 * 读取端被注入在 OPPO 健康进程里，没有自己的界面，用户的操作只能这样告知它。
 *
 * 用广播而不是轮询：早期版本每 5 秒跨进程查一次配置，虽然单次成本很低，
 * 却把本应用永久钉在内存里（实测常驻 PSS 约 55 MB）。事件驱动后，本应用
 * 在没有用户操作时可以被系统正常回收，需要写入时再由系统按需拉起数据接口。
 *
 * 广播内带口令，读取端校验后才处理，第三方伪造无效。
 */
object TriggerSender {

    const val ACTION_SYNC_NOW = "io.github.ohsync.action.SYNC_NOW"
    const val ACTION_CONFIG_CHANGED = "io.github.ohsync.action.CONFIG_CHANGED"
    const val ACTION_PING = "io.github.ohsync.action.PING"

    private const val TAG = "OHSyncTrigger"
    private const val EXTRA_TOKEN = "token"

    /** 读取端注册广播时用的是宿主进程的 Context，所以目标包是它。 */
    private const val TARGET_PACKAGE = "com.heytap.health"

    fun syncNow(context: Context) = send(context, ACTION_SYNC_NOW)

    fun configChanged(context: Context) = send(context, ACTION_CONFIG_CHANGED)

    fun ping(context: Context) = send(context, ACTION_PING)

    private fun send(context: Context, action: String) {
        runCatching {
            context.sendBroadcast(
                Intent(action)
                    .setPackage(TARGET_PACKAGE)
                    .putExtra(EXTRA_TOKEN, TokenStore.getOrCreate(context))
            )
        }.onFailure { Log.w(TAG, "发送 $action 失败", it) }
    }
}
