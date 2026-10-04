package io.github.ohsync.hook

import android.content.Context
import android.util.Log

/**
 * 拿到目标进程的 Context。
 *
 * Hook 侧没有自己的 Context（模块代码跑在 OPPO 健康进程里），但要发
 * ContentResolver 调用就必须有 Context。用 ActivityThread 的系统 Context，
 * 只用来广播/插入数据，不改任何配置。
 */
object AppContextHolder {

    @Volatile
    var context: Context? = null
        private set

    fun init() {
        if (context != null) return
        try {
            val activityThread: Class<*> = Class.forName("android.app.ActivityThread")
            val thread: Any? = activityThread
                .getDeclaredMethod("currentActivityThread")
                .invoke(null)
            val app: Context? = activityThread
                .getDeclaredMethod("getSystemContext")
                .invoke(thread) as? Context
            context = app
            Log.i(TAG, "已获取目标进程 Context：${app?.packageName}")
        } catch (t: Throwable) {
            Log.e(TAG, "获取 Context 失败", t)
        }
    }

    private const val TAG = "OHSyncCtx"
}
