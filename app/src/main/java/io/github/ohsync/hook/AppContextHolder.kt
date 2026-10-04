package io.github.ohsync.hook

import android.content.Context
import android.util.Log

/**
 * 拿到目标进程的 Context。
 *
 * Hook 侧没有自己的 Context（模块代码跑在 OPPO 健康进程里），但要发
 * ContentResolver 调用就必须有 Context。取宿主自己的 Application，
 * 只用来读写本机 provider，不改任何配置。
 */
object AppContextHolder {

    @Volatile
    var context: Context? = null
        private set

    fun init() {
        if (context != null) return
        // 允许多次调用：onPackageReady 时 Application 可能还没挂上，下个时机再试
        try {
            val activityThread: Class<*> = Class.forName("android.app.ActivityThread")
            // 必须用宿主自己的 Application，不能用 getSystemContext()：
            // 后者的 packageName 是 "android"，拿它去 call 别的应用 provider 时
            // 会被 AMS 判定 callingPackage 与调用方 uid 不符而抛 SecurityException。
            val app = activityThread
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Context
            if (app != null) {
                context = app
                Log.i(TAG, "已获取宿主 Application，包名=${app.packageName}")
            } else {
                Log.w(TAG, "currentApplication 仍为 null，稍后再取")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "获取 Context 失败", t)
        }
    }

    private const val TAG = "OHSyncCtx"
}
