package io.github.bqj6666.ohsync.core

import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * 模块进程 -> 主进程的全部跨进程业务逻辑。
 *
 * 两条通道共用这一份实现：
 *   - ContentProvider（默认，简单）
 *   - BridgeService（显式组件绑定，用于 provider 被 HMA 之类藏掉时）
 *
 * 之前逻辑写在 Provider 里，现在抽出来，避免两条通道各写一遍走样。
 */
object BridgeHandler {

    private const val TAG = "OHSyncBridge"

    /** 收一条记录批次。返回 true 表示已被主进程接收。 */
    fun insert(ctx: Context, token: String?, type: String?, payload: String?): Boolean {
        val expected = TokenStore.getOrCreate(ctx)
        if (token == null || !TokenStore.constantTimeEquals(token, expected)) {
            Log.w(TAG, "rejected: bad token")
            return false
        }
        if (type == null || payload == null) return false
        return try {
            val records = PayloadCodec.decode(type, payload)
            val engine = SyncEngine.get(ctx)
            engine.noteActivity()
            engine.submit(SyncBatch(records, isBackfill = false))
            Log.i(TAG, "accepted ${records.size} records of $type")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed", t)
            false
        }
    }

    /** 配置 / 心跳 / 回报，语义与 Provider 的 call() 完全一致。 */
    fun call(ctx: Context, method: String, arg: String?, extras: Bundle?): Bundle {
        val expected = TokenStore.getOrCreate(ctx)
        val engine = SyncEngine.get(ctx)
        return Bundle().apply {
            when (method) {
                "ping" -> {
                    val ok = arg != null && TokenStore.constantTimeEquals(arg, expected)
                    putBoolean("ok", ok)
                    if (ok) engine.noteHookSeen()
                }
                "config" -> {
                    putString("token", expected)
                    putInt("intervalMinutes", Settings.intervalMinutes(ctx))
                    putInt("windowDays", Settings.windowDays(ctx))
                    putBoolean("backfillRequested", engine.pendingBackfill())
                    putLong("lastSyncAt", Settings.lastSyncAt(ctx))
                }
                "backfillDone" -> engine.clearBackfillRequest()
                "syncDone" -> {
                    extras?.getLong("at")?.let { Settings.setLastSyncAt(ctx, it) }
                    engine.clearBackfillRequest()
                }
                "requestBackfill" -> engine.requestBackfill()
                else -> Unit
            }
        }
    }
}
