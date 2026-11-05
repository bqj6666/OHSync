package io.github.bqj6666.ohsync.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import io.github.bqj6666.ohsync.core.Ids
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 显式组件绑定版通道（provider 被隐藏时的备胎）。
 *
 * 与 RemoteConfig / Pusher 里走 provider 的路径语义一致，只是换了传输：
 * bindService 用 ComponentName，不依赖「OHSync 这个包对 OPPO 健康可见」。
 */
object Bridge {

    private const val TAG = "OHSyncBridge"
    private const val SERVICE_CLASS = "io.github.bqj6666.ohsync.core.BridgeService"
    private const val WHAT_CALL = 1
    private const val WHAT_INSERT = 2
    private const val BIND_TIMEOUT_MS = 3000L
    private const val CALL_TIMEOUT_MS = 8000L

    @Volatile private var remote: Messenger? = null
    @Volatile private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = binder?.let { Messenger(it) }
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            bound = false
        }
    }

    /** 已连上就直接用，否则绑一次。主进程可能没起，bind 会自动把它拉起来。 */
    private fun ensure(ctx: Context): Messenger? {
        remote?.let { return it }
        synchronized(this) {
            remote?.let { return it }
            if (!bound) {
                val intent = Intent().setClassName(Ids.APP_ID, SERVICE_CLASS)
                val ok = try {
                    ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                } catch (t: Throwable) {
                    Log.w(TAG, "bind 抛异常", t)
                    false
                }
                if (!ok) return null
            }
            // onServiceConnected 是异步的，这里短暂等它
            val deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                remote?.let { return it }
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    return null
                }
            }
            return remote
        }
    }

    private fun request(ctx: Context, what: Int, data: Bundle): Bundle? {
        val m = ensure(ctx) ?: return null
        val queue = LinkedBlockingQueue<Bundle>(1)
        val reply = Messenger(Handler(Looper.getMainLooper()) { msg ->
            queue.offer(msg.data ?: Bundle())
            true
        })
        val msg = Message.obtain(null, what).apply {
            this.data = data
            this.replyTo = reply
        }
        return try {
            m.send(msg)
            queue.poll(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "发送失败，重置连接", t)
            remote = null
            bound = false
            null
        }
    }

    fun call(ctx: Context, method: String, arg: String?, extras: Bundle? = null): Bundle? {
        val data = Bundle().apply {
            putString("method", method)
            putString("arg", arg)
            extras?.let { putAll(it) }
        }
        return request(ctx, WHAT_CALL, data)
    }

    fun insert(ctx: Context, token: String, type: String, payload: String): Boolean {
        val data = Bundle().apply {
            putString("token", token)
            putString("type", type)
            putString("payload", payload)
        }
        val r = request(ctx, WHAT_INSERT, data) ?: return false
        return r.getBoolean("ok", false)
    }
}
