package io.github.ohsync.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 主进程的同步引擎：串行消费 Hook 推来的批次，写 Health Connect。
 *
 * 用单消费者 + Mutex 而不是并行写：HC 的写入有配额，并行只会更快触发限流，
 * 而且失败时难以判断是哪一批出错。
 */
object SyncEngine {

    private const val TAG = "OHSyncEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _backfillRequested = MutableStateFlow(true)
    private var hc: HcClient? = null

    fun get(context: Context): SyncEngine {
        synchronized(this) {
            if (hc == null) hc = HcClient(context.applicationContext)
            return this
        }
    }

    fun submit(batch: SyncBatch) {
        scope.launch { consume(batch) }
    }

    private suspend fun consume(batch: SyncBatch) = mutex.withLock {
        val client = hc ?: run { log("Health Connect 不可用，丢弃 ${batch.records.size} 条"); return@withLock }
        var ok = 0L; var skipped = 0L; var failed = 0L
        for (r in batch.records) {
            try {
                if (r.recordType.hcRecord == null) { skipped++; continue }
                client.upsert(RecordMapper.map(r), HcClient.clientRecordId(r.recordType, r.sourceKey))
                ok++
            } catch (t: Throwable) {
                failed++
                Log.e(TAG, "write failed for ${r.recordType.id}/${r.sourceKey}", t)
                log("写入失败 ${r.recordType.id}: ${t.message}")
            }
        }
        _status.value = _status.value.copy(
            hookConnected = true,
            lastSyncAtMillis = System.currentTimeMillis(),
            syncedCount = _status.value.syncedCount + ok,
            skippedCount = _status.value.skippedCount + skipped,
            failedCount = _status.value.failedCount + failed,
            pendingBackfill = false,
        )
        log("批次完成：写入 $ok / 跳过 $skipped / 失败 $failed" +
            if (batch.isBackfill) "（历史回填）" else "（实时）")
    }

    fun requestBackfill() {
        _backfillRequested.value = true
        _status.value = _status.value.copy(pendingBackfill = true)
        log("已请求历史回填，等待 Hook 进程响应")
    }

    fun pendingBackfill(): Boolean = _backfillRequested.value

    fun syncedCount(): Long = _status.value.syncedCount

    fun noteHookSeen() {
        _status.value = _status.value.copy(hookConnected = true)
    }

    fun notePermission(granted: Boolean) {
        _status.value = _status.value.copy(hcPermissionGranted = granted)
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        _logs.value = (_logs.value + "• $line").takeLast(200)
    }
}
