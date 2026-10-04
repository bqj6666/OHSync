package io.github.ohsync.hook

import android.content.Context

/**
 * 拿到 OPPO 健康的 Context。
 *
 * Hook 侧没有自己的 Context（模块代码跑在目标进程里），但要发 ContentProvider 调用
 * 就必须有 Context。用 ActivityThread 的当前 ActivityThread 拿 base context，
 * 只读取归属信息，不改任何设置。
 */
object AppContextHolder {

    @Volatile var context: Context? = null
        private set

    fun init() {
        if (context != null) return
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val system = at.getDeclaredMethod("currentActivityThread").invoke(null)
            val app = at.getDeclaredMethod("getSystemContext").invoke(system) as? Context
            context = app
            Log.i(TAG, "已获取目标进程 Context：${app?.packageName}")
        }.onFailure { Log.e(TAG, "获取 Context 失败", it) }
    }

    private const val TAG = "OHSyncCtx"
}
