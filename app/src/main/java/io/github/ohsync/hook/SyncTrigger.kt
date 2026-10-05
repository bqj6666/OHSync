package io.github.ohsync.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import io.github.ohsync.core.TokenStore

/**
 * 主进程 → Hook 的事件通道。
 *
 * 为什么需要它：原先 Hook 侧每 5 秒跨进程查一次配置，用来及时发现用户点的
 * 「立即同步」。结果是绝大多数询问都是「没有新请求」，却把 OHSync 主进程
 * 永久钉在内存里（实测常驻 PSS 约 55 MB）。
 *
 * 改成事件驱动后，Hook 侧平时只在本地 sleep，不做任何 IPC；只有用户操作时
 * 主进程才发一次广播。广播内带口令，第三方伪造的会被丢弃。
 */
object SyncTrigger {

    const val ACTION_SYNC_NOW = "io.github.ohsync.action.SYNC_NOW"
    const val ACTION_CONFIG_CHANGED = "io.github.ohsync.action.CONFIG_CHANGED"
    const val ACTION_PING = "io.github.ohsync.action.PING"
    const val EXTRA_TOKEN = "token"

    /**
     * @param onSyncNow  用户点了「立即同步」
     * @param onConfigChanged 用户改了设置
     * @param onPing     应用界面刚打开，想确认读取端是否在线
     */
    fun register(
        context: Context,
        onSyncNow: () -> Unit,
        onConfigChanged: () -> Unit,
        onPing: () -> Unit,
    ) {
        val filter = IntentFilter().apply {
            addAction(ACTION_SYNC_NOW)
            addAction(ACTION_CONFIG_CHANGED)
            addAction(ACTION_PING)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val expected = TokenHolder.token
                val got = intent.getStringExtra(EXTRA_TOKEN)
                if (expected.isEmpty() || got.isNullOrEmpty() ||
                    !TokenStore.constantTimeEquals(got, expected)
                ) {
                    Log.w(TAG, "忽略校验不通过的广播：$action")
                    return
                }
                Log.i(TAG, "收到事件：$action")
                when (action) {
                    ACTION_SYNC_NOW -> onSyncNow()
                    ACTION_CONFIG_CHANGED -> onConfigChanged()
                    ACTION_PING -> onPing()
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 发送方是另一个应用，必须导出；安全性由广播里的口令保证
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            Log.i(TAG, "事件通道已注册")
        } catch (t: Throwable) {
            Log.e(TAG, "事件通道注册失败", t)
        }
    }

    private const val TAG = "OHSyncTrigger"
}
