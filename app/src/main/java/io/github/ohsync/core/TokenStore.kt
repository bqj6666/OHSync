package io.github.ohsync.core

import android.content.Context
import java.security.SecureRandom
import java.util.Base64

/**
 * 跨进程 token。
 *
 * 主进程首次运行生成并保存；Hook 进程通过用户在设置页粘贴的配对码获得。
 * 不用自定义权限：com.heytap.health 是第三方应用，签名级权限无法授予它。
 */
object TokenStore {
    private const val PREFS = "ohsync_prefs"
    private const val KEY_TOKEN = "sync_token"
    private const val KEY_PAIRED = "paired"

    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    fun savePairedToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).putBoolean(KEY_PAIRED, true).apply()
    }

    fun isPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAIRED, false)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
