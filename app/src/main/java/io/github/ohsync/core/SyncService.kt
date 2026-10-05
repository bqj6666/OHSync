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
 * 常驻前台服务。
 *
 * 为什么必须要有它（实测得出，不是保险起见）：
 * 读取端在 OPPO 健康进程里，读完要通过本应用的 ContentProvider 交过来。
 * 而本机（ColorOS，Android 16）**不会因为 provider 访问就去拉起一个已经死掉的
 * 应用进程** —— 用另一个应用做过对照，结果一样。于是进程一旦被回收，写入就
 * 全部失败（报 Unknown URL content://…），用户看到的就是「划掉之后不同步了」。
 *
 * 前台服务能让这个进程在用户划掉最近任务后依然存活，provider 也就一直可用。
 *
 * 资源占用上做了取舍：
 *  - 服务本身不加载界面相关代码，只在需要写入时初始化 Health Connect 客户端
 *  - 通知使用最低重要级别、不响不震不显示角标
 *  - 用户可在应用里关掉它（代价是后台同步失效，界面会说明）
 */
class SyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        return START_STICKY
    }

    private fun startAsForeground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                nm.getNotificationChannel(CHANNEL_ID) == null
            ) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "后台同步", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "保持同步所需的最低限度常驻"
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
                )
            }
            val notification: Notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("OHSync")
                .setContentText("正在按设定间隔同步 OPPO 健康数据")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.e(TAG, "启动前台服务失败", t)
        }
    }

    companion object {
        private const val TAG = "OHSyncService"
        private const val CHANNEL_ID = "ohsync_background"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, SyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "无法启动常驻服务", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SyncService::class.java)) }
        }
    }
}
