package io.github.ohsync.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 数据接收端的主进程侧（前台服务）。
 *
 * 为什么需要它：读取端跑在 OPPO 健康进程里，读完要通过本应用的数据接口把数据
 * 交过来。而本机（ColorOS，Android 16）不会因为「有别的应用访问数据接口」就
 * 去启动一个已经没在跑的应用 —— 用另一个应用做过对照，行为一致。进程不在，
 * 写入就全部失败（报 Unknown URL content://…）。
 *
 * 所以这个服务负责「让进程按需存在」，由两件事唤起：
 *   1. 读取端要推数据前发的唤醒广播（见 WakeReceiver）—— 事件驱动，实时
 *   2. 定时闹钟兜底 —— 防止长期没有数据可推时没人响应「立即同步」
 *
 * 为什么必须是**前台服务**（本机实测，不是保险起见）：
 * 试过普通后台服务 + AlarmManager + 广播唤醒，三条路在本机（ColorOS，Android 16）
 * 都拉不起已经退出的进程 —— 广播被入队但不执行，闹钟到点也没反应（进程数始终为 0）。
 * 这是系统对 cached 应用的限制，不是实现问题。
 * 前台服务是唯一能让进程不被回收的方式，代价是需要一条（静音）通知。
 *
 * 通知已降到最低重要级别：不响、不震、不显示角标；用户可在设置里关掉常驻
 * （代价是自动同步失效，只能打开应用手动同步）。
 */
class SyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "服务启动：${intent?.action ?: "(no action)"}")
        startAsForeground()
        return START_STICKY
    }

    private fun startAsForeground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "后台同步", NotificationManager.IMPORTANCE_MIN
                    ).apply {
                        description = "保持同步所需的最低限度常驻"
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
                )
            }
            startForeground(
                NOTIFICATION_ID,
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("OHSync")
                    .setContentText("正在同步 OPPO 健康数据")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .build()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "启动前台服务失败", t)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "服务销毁")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OHSyncService"
        private const val CHANNEL_ID = "ohsync_background"
        private const val NOTIFICATION_ID = 1001

        /**
         * 启动常驻服务。
         *
         * 必须用 startForegroundService：它是前台服务，用 startService 起不来
         * （服务记录会存在，但 onStartCommand 不会被调用 —— 踩过）。
         */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, SyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "启动服务失败", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SyncService::class.java)) }
        }
    }
}
