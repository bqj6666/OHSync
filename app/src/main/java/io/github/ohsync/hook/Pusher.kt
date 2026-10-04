package io.github.ohsync.hook

import android.util.Log
import android.content.ContentValues
import io.github.ohsync.core.PayloadCodec
import io.github.ohsync.core.RecordType
import io.github.ohsync.core.SyncRecord

/**
 * 把 OPPO 的表读出来 -> 规范化成 SyncRecord -> 推给主进程。
 *
 * 翻译规则全部集中在 [QueryPlan]：一张表一套 SELECT 和字段映射，
 * 改映射只动这一个文件，不散落在各处。
 */
object Pusher {

    private const val TAG = "OHSyncPush"
    private const val PROVIDER_SUFFIX = ".sync"
    private const val OHSYNC_PACKAGE = "io.github.ohsync"

    fun push(reader: TableReader, mapping: EntityMapping, isBackfill: Boolean) {
        val plan = QueryPlan.forTables(reader, mapping)
        if (plan.isEmpty()) {
            Log.w(TAG, "没有可推送的表")
            return
        }
        var total = 0
        for ((table, spec) in plan) {
            val rows = reader.query(spec.sql)
            val records = rows.mapNotNull { spec.toRecord(table, it) }
            if (records.isNotEmpty()) {
                send(table, records, isBackfill)
                total += records.size
                Log.i(TAG, "$table -> ${records.size} 条")
            }
        }
        Log.i(TAG, "推送完成：共 $total 条")
    }

    private fun send(type: String, records: List<SyncRecord>, isBackfill: Boolean) {
        runCatching {
            val ctx = AppContextHolder.context ?: return
            val values = ContentValues().apply {
                put("token", TokenHolder.token)
                put("type", type)
                put("payload", PayloadCodec.encode(records))
            }
            ctx.contentResolver.insert(
                android.net.Uri.parse("content://$OHSYNC_PACKAGE$PROVIDER_SUFFIX/records"),
                values,
            )
        }.onFailure { Log.e(TAG, "推送 $type 失败", it) }
    }
}
