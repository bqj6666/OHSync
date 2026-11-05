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
 * 为什么要多这一条：HMA 这类模块会把被隐藏的包从宿主应用的 PackageManager
 * 视图里抹掉，于是 ContentResolver 解析 provider 直接失败（Unknown authority），
 * 推送全废。而 bindService 用的是显式 ComponentName，AMS 直接按组件名查服务表，
 * 不经过「这个包对调用方可见吗」这一层，所以隐藏状态下仍然能连上。
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
