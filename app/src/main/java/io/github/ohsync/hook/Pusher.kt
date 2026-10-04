package io.github.ohsync.hook

import android.content.ContentValues
import android.net.Uri
import android.util.Log
import io.github.ohsync.core.PayloadCodec
import io.github.ohsync.core.RecordType
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

        // 睡眠是多行拼一条记录，走独立路径
        val sleep = SleepBuilder.build(reader)
        if (sleep.isNotEmpty()) {
            send(RecordType.SLEEP_SESSION.id, sleep)
            total += sleep.size
        }

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
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context
        if (ctx == null) {
            Log.e(TAG, "没有 Context，无法推送 $type")
            return
        }
        if (TokenHolder.token.isEmpty()) {
            // 主动向主进程取口令：Hook 侧没有 UI，用户无法手抄，只能这样拿
            val fetched = try {
                ctx.contentResolver.call(
                    Uri.parse("content://$OHSYNC_PACKAGE$PROVIDER_SUFFIX"),
                    "token", null, null,
                )?.getString("token")
            } catch (t: Throwable) {
                Log.e(TAG, "取口令失败", t)
                null
            }
            if (fetched.isNullOrEmpty()) {
                Log.e(TAG, "主进程还没准备好，暂不推送 $type")
                return
            }
            TokenHolder.pair(fetched)
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
