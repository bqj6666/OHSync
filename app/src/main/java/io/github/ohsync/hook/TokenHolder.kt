package io.github.ohsync.hook

import android.content.Context
import io.github.ohsync.core.TokenStore

/**
 * 跨进程口令。
 *
 * 主进程生成并显示在 UI 上，用户粘贴到这里（Hook 侧设置页）。
 * 存目标进程的 SharedPreferences 不合适（那是 OPPO 的目录），
 * 所以只留在内存，进程重启后需重新配对 —— 这是有意的安全取舍。
 */
object TokenHolder {

    @Volatile var token: String = ""
        private set

    fun pair(newToken: String) {
        if (newToken.isBlank()) return
        token = newToken.trim()
        Log.i(TAG, "口令已配对，长度 ${token.length}")
    }

    fun isPaired() = token.isNotBlank()

    private const val TAG = "OHSyncToken"
}
