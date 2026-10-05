package io.github.bqj6666.ohsync.hook

import android.util.Log

/**
 * 跨进程配对口令。
 *
 * 主进程生成并显示在 UI 上，用户在 Hook 侧粘贴进来。只留在内存里：
 * 写进目标进程的 SharedPreferences 等于往 OPPO 的私有目录塞我们的东西，
 * 不合适；代价是进程重启后需要重新配对，这是有意的取舍。
 */
object TokenHolder {

    @Volatile
    var token: String = ""
        private set

    fun pair(newToken: String) {
        if (newToken.isBlank()) return
        token = newToken.trim()
        Log.i(TAG, "口令已配对，长度 ${token.length}")
    }

    fun isPaired(): Boolean = token.isNotBlank()

    private const val TAG = "OHSyncToken"
}
