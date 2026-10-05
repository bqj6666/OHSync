package io.github.ohsync.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * 唤醒本应用的入口。
 *
 * 两个来源：
 *
 *  1. **读取端要推数据** —— 它发显式广播 [WAKE_ACTION]（带口令）。系统会因此把
 *     本应用拉起来，服务随之启动，数据接口变得可用。实时性由此而来，
 *     不需要靠高频闹钟轮询。
 *
 *  2. **定时兜底** —— [ALARM_ACTION] 由 AlarmManager 周期触发，避免长期没有数据
 *     可推时进程一直不在，导致用户点「立即同步」无人响应。
 *
 * 这个模式沿用同类项目验证过的做法（普通后台服务 + AlarmManager 自我保护），
 * 但闹钟间隔更长、实时性交给事件，整体更省电。
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

            ALARM_ACTION -> {
                if (!Settings.keepAlive(context)) {
                    Log.i(TAG, "用户已关闭保活，不再排闹钟")
                    return
                }
                Log.i(TAG, "定时兜底唤醒")
                SyncService.start(context)
                scheduleAlarm(context)
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                if (!Settings.keepAlive(context)) return
                Log.i(TAG, "开机后启动")
                SyncService.start(context)
                scheduleAlarm(context)
            }
        }
    }

    companion object {
        private const val TAG = "OHSyncWake"

        /** 读取端要推数据前发的显式广播。 */
        const val WAKE_ACTION = "io.github.ohsync.action.WAKE"

        /** 保活闹钟。 */
        const val ALARM_ACTION = "io.github.ohsync.action.ALARM"

        private const val EXTRA_TOKEN = "token"
        private const val REQUEST_CODE = 2001

        /**
         * 兜底闹钟间隔。
         *
         * 不设更短的原因：实时性由读取端的事件唤醒负责，这里只防「长期没人来推」。
         * 15 分钟一次的闹钟系统开销可忽略。
         */
        private const val ALARM_INTERVAL_MS = 15 * 60_000L

        fun scheduleAlarm(context: Context) {
            if (!Settings.keepAlive(context)) return
            runCatching {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                // 用不精确的 set()：省电，这点误差对兜底没有影响。
                // 需要精确触发的场合由事件通道负责。
                am.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                    pendingIntent(context),
                )
            }.onFailure { Log.w(TAG, "排闹钟失败", it) }
        }

        fun cancelAlarm(context: Context) {
            runCatching {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                am.cancel(pendingIntent(context))
            }
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, WakeReceiver::class.java).setAction(ALARM_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
