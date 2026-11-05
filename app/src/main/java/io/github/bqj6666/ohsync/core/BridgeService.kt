package io.github.bqj6666.ohsync.core

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log

/**
 * 与 [SyncReceiverProvider] 等价的第二条通道，走「显式组件绑定」。
 *
 * 为什么要多这一条：provider 被挡时（Unknown authority）还有一条路可试。
 *
 * 但实测（2026-11-05）在 HMA 彻底隐藏 OHSync 的场景下，**这条通道也连不上**：
 * 隐藏类模块把包从 PackageManager 层整个抹掉，显式组件名一样解析失败。所以它只是
 * 兜底，不是「隐藏可用」的保证 —— 真要隐藏状态下工作，必须让 OHSync 对
 * com.heytap.health 可见（README 已写明）。
 *
 * 协议：what=1 走 call()，what=2 走 insert()，回复放 msg.replyTo。
 */
class BridgeService : Service() {

    private val messenger: Messenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                WHAT_CALL -> {
                    val data = msg.data ?: Bundle()
                    val method = data.getString("method") ?: ""
                    val arg = data.getString("arg")
                    val extras = Bundle(data).apply {
                        remove("method")
                        remove("arg")
                    }
                    reply(msg, BridgeHandler.call(this, method, arg, extras))
                }
                WHAT_INSERT -> {
                    val data = msg.data
                    val ok = BridgeHandler.insert(
                        this,
                        data?.getString("token"),
                        data?.getString("type"),
                        data?.getString("payload"),
                    )
                    reply(msg, Bundle().apply { putBoolean("ok", ok) })
                }
                else -> Unit
            }
            true
        })
    }

    private fun reply(msg: Message, body: Bundle) {
        try {
            msg.replyTo?.send(Message.obtain(null, 0).apply { data = body })
        } catch (t: Throwable) {
            Log.w(TAG, "回包失败", t)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        val uid = Binder.getCallingUid()
        if (uid != 0 && uid != Process.myUid()) {
            val pkgs = packageManager.getPackagesForUid(uid)
            if (pkgs == null || TARGET_PACKAGE !in pkgs) {
                Log.w(TAG, "拒绝绑定：uid=$uid pkg=${pkgs?.joinToString()}")
                return null
            }
        }
        return messenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    companion object {
        private const val TAG = "OHSyncBridge"
        private const val WHAT_CALL = 1
        private const val WHAT_INSERT = 2

        /** 唯一被允许绑定的外部应用。 */
        const val TARGET_PACKAGE = "com.heytap.health"
    }
}
