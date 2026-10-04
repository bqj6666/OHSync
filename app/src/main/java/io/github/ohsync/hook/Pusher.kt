package io.github.ohsync.hook

import android.content.ContentValues
import android.net.Uri
import android.util.Log
import io.github.ohsync.core.PayloadCodec
import io.github.ohsync.core.SyncRecord

/**
 * 把 OPPO 的表读出来 -> 规范化成 SyncRecord -> 推给主进程。
 *
 * 「读什么」全部交给 [QueryPlan] 决定，这里只负责取数、编码、递送。
 */
object Pusher {

    private const val TAG = "OHSyncPush"
    private const val OHSYNC_PACKAGE = "io.github.ohsync"
    private const val PROVIDER_SUFFIX = ".sync"

    fun push(reader: TableReader) {
        val specs = QueryPlan.forTables(reader)
        if (specs.isEmpty()) {
            Log.w(TAG, "没有找到可同步的表（结构可能已变）")
            return
        }
        var total = 0
        for (spec in specs) {
            val rows = reader.query(spec.sql)
            val records = rows.mapNotNull(spec.toRecord)
            if (records.isEmpty()) continue
            send(spec.type.id, records)
            total += records.size
            Log.i(TAG, "${spec.type.label}：${records.size} 条")
        }
        Log.i(TAG, "推送完成，共 $total 条")
    }

    private fun send(type: String, records: List<SyncRecord>) {
        val ctx = AppContextHolder.context
        if (ctx == null) {
            Log.e(TAG, "没有 Context，无法推送 $type")
            return
        }
        if (!TokenHolder.isPaired()) {
            Log.e(TAG, "尚未配对，无法推送 $type（请在 OHSync 里复制口令并粘贴回来）")
            return
        }
        try {
            val values = ContentValues().apply {
                put("token", TokenHolder.token)
                put("type", type)
                put("payload", PayloadCodec.encode(records))
            }
            ctx.contentResolver.insert(
                Uri.parse("content://$OHSYNC_PACKAGE$PROVIDER_SUFFIX/records"),
                values,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "推送 $type 失败", t)
        }
    }
}
