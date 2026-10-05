package io.github.bqj6666.ohsync.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 把常驻服务拉回来的入口。
 *
 * 说明：本机实测过「广播唤醒」与「AlarmManager 兜底」两条路，都**拉不起已经退出
 * 的进程** —— 广播会被系统入队但不执行，闹钟到点也没有反应（系统对 cached 应用的
 * 限制）。常驻因此只能靠前台服务，见 SyncService。
 *
 * 这里保留两个入口，用于进程已存在时恢复服务或刷新状态：
 *   - 读取端要推数据时的唤醒广播（带口令校验）
 *   - 开机自启
 */
class WakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            WAKE_ACTION -> {
                // 读取端来推数据：校验口令，防第三方伪造唤醒
                val expected = TokenStore.getOrCreate(context)
                val got = intent.getStringExtra(EXTRA_TOKEN)
                if (got.isNullOrEmpty() || !TokenStore.constantTimeEquals(got, expected)) {
                    Log.w(TAG, "唤醒广播口令不匹配，忽略")
                    return
                }
                Log.i(TAG, "读取端请求唤醒")
                SyncService.start(context)
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                if (!Settings.keepAlive(context)) {
                    Log.i(TAG, "用户已关闭常驻，跳过开机自启")
                    return
                }
                Log.i(TAG, "开机后启动常驻服务")
                SyncService.start(context)
            }
        }
    }

    companion object {
        private const val TAG = "OHSyncWake"

        /** 读取端要推数据前发的显式广播。 */
        const val WAKE_ACTION = Ids.ACTION_WAKE

        private const val EXTRA_TOKEN = Ids.EXTRA_TOKEN
    }
}
