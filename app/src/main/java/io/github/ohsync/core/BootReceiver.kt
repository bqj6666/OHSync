package io.github.ohsync.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机后把常驻服务拉起来。
 *
 * 没有它的话，重启之后本应用不会自己运行，读取端就永远找不到收数据的地方
 * （本机不会因为 provider 访问去启动一个没在跑的应用），必须等用户手动打开一次。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.keepAlive(context)) {
            Log.i(TAG, "用户已关闭常驻，跳过")
            return
        }
        Log.i(TAG, "开机后启动常驻服务")
        SyncService.start(context)
    }

    private companion object {
        const val TAG = "OHSyncBoot"
    }
}
