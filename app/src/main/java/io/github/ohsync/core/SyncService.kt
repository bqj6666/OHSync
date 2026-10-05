package io.github.ohsync.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 数据接收端的主进程侧。
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
 * 它是**普通后台服务**，不调用 startForeground()，因此没有常驻通知。
 * 同类项目（FxxkMoondrop）用「普通服务 + AlarmManager」的组合稳定存活，
 * 这里沿用同一思路，但把闹钟间隔拉长、把实时性交给事件，整体开销更低。
 */
class SyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    @Volatile private var watching = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "服务启动：${intent?.action ?: "(no action)"}")
        WakeReceiver.scheduleAlarm(this)
        startIdleWatch()
        return START_STICKY
    }

    /**
     * 空闲一段时间后主动退出。
     *
     * 数据接口要一直可用，所以「有数据要写」时进程得活着；但没人来推数据时没必要
     * 占着内存。空闲即退出，进程随后被系统回收（占用归零），下次有数据要推时
     * 读取端会发广播把它唤醒。
     *
     * 用 stopSelf()：这样不会被 START_STICKY 拉回来（那只在系统杀进程时生效）。
     */
    private fun startIdleWatch() {
        if (watching) return
        watching = true
        Thread {
            while (watching) {
                try {
                    Thread.sleep(IDLE_CHECK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val idleFor = System.currentTimeMillis() - SyncEngine.lastActivityAt()
                if (idleFor >= IDLE_MS) {
                    Log.i(TAG, "空闲 ${idleFor / 1000} 秒，主动退出以释放内存")
                    watching = false
                    stopSelf()
                    return@Thread
                }
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onDestroy() {
        watching = false
        Log.i(TAG, "服务销毁")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OHSyncService"

        /** 空闲多久退出。留足余量，避免写入还没落盘就退出。 */
        private const val IDLE_MS = 5 * 60_000L
        private const val IDLE_CHECK_MS = 30_000L

        fun start(context: Context) {
            runCatching { context.startService(Intent(context, SyncService::class.java)) }
                .onFailure { Log.w(TAG, "启动服务失败", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SyncService::class.java)) }
        }
    }
}
