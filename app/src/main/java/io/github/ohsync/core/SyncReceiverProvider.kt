package io.github.ohsync.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * Hook 进程 -> 主进程的数据入口。
 *
 * export + token 校验，不用自定义权限（见 spec 4.2）。
 * 写入路径：call("ping") 做连通性与 token 自检；insert() 收记录。
 */
class SyncReceiverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun ctx(): Context = requireNotNull(context)

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val token = values?.getAsString(COL_TOKEN)
        val expected = TokenStore.getOrCreate(ctx())
        if (token == null || !TokenStore.constantTimeEquals(token, expected)) {
            Log.w(TAG, "rejected: bad token")
            return null
        }
        val type = values?.getAsString(COL_TYPE) ?: return null
        val payload = values?.getAsString(COL_PAYLOAD) ?: return null
        return try {
            val records = PayloadCodec.decode(type, payload)
            SyncEngine.get(ctx()).submit(SyncBatch(records, isBackfill = false))
            Log.i(TAG, "accepted ${records.size} records of $type")
            Uri.withAppendedPath(uri, "ok")
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed", t)
            null
        }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? {
        val token = selectionArgs?.firstOrNull()
        val expected = TokenStore.getOrCreate(ctx())
        val ok = token != null && TokenStore.constantTimeEquals(token, expected)
        val engine = SyncEngine.get(ctx())
        val matrix = android.database.MatrixCursor(
            arrayOf(COL_HOOK_CONNECTED, COL_PENDING_BACKFILL, COL_SYNCED_COUNT)
        )
        matrix.addRow(arrayOf(if (ok) 1 else 0, if (engine.pendingBackfill()) 1 else 0, engine.syncedCount()))
        return matrix
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/vnd.io.github.ohsync"

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0

    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
        val expected = TokenStore.getOrCreate(ctx())
        val engine = SyncEngine.get(ctx())
        return android.os.Bundle().apply {
            when (method) {
                // Hook 进程启动时自检：口令对不对、有没有待回填
                "ping" -> putBoolean("ok", arg != null && TokenStore.constantTimeEquals(arg, expected))
                "requestBackfill" -> engine.requestBackfill()
                else -> Unit
            }
        }
    }

    companion object {
        private const val TAG = "OHSyncProvider"
        const val COL_TOKEN = "token"
        const val COL_TYPE = "type"
        const val COL_PAYLOAD = "payload"
        const val COL_HOOK_CONNECTED = "hook_connected"
        const val COL_PENDING_BACKFILL = "pending_backfill"
        const val COL_SYNCED_COUNT = "synced_count"

        fun authority(context: Context): String = "${context.packageName}.sync"
        fun uri(context: Context): Uri = Uri.parse("content://${authority(context)}/records")
    }
}
