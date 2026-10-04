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

    /**
     * 是否已请求手动同步、但还没看到推送结果。
     *
     * 初始 false：UI 一进来不该显示「正在同步」。首次拿库时的历史回填由 Hook 侧
     * 独立判断（它不读这个标志），所以这里不需要为它置位。
     */
    private val _backfillRequested = MutableStateFlow(false)
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
        val mapped = ArrayList<androidx.health.connect.client.records.Record>(batch.records.size)
        for (r in batch.records) {
            try {
                if (r.recordType.hcRecord == null) { skipped++; continue }
                mapped += RecordMapper.map(r)
            } catch (t: Throwable) {
                failed++
                Log.e(TAG, "map failed for ${r.recordType.id}/${r.sourceKey}", t)
                log("翻译失败 ${r.recordType.id}: ${t.message}")
            }
        }
        try {
            client.writeAll(mapped)
            ok = mapped.size.toLong()
        } catch (t: Throwable) {
            failed += mapped.size
            Log.e(TAG, "批量写入失败（${mapped.size} 条）", t)
            log("批量写入失败：${t.message}")
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

    /** 清除本应用写入 Health Connect 的全部记录（早期版本有过重复插入）。 */
    fun clearAll() {
        scope.launch {
            val c = hc ?: return@launch
            val n = runCatching { c.deleteAllMine() }
                .onFailure { Log.e(TAG, "清除失败", it) }
                .getOrDefault(0)
            _status.value = _status.value.copy(syncedCount = 0)
            log("已清除 $n 类记录，下次推送会重新写入")
        }
    }

    fun requestBackfill() {
        _backfillRequested.value = true
        _status.value = _status.value.copy(pendingBackfill = true)
        log("已请求历史回填，等待 Hook 进程响应")
    }

    fun pendingBackfill(): Boolean = _backfillRequested.value

    /** Hook 侧完成手动同步后调用。 */
    fun clearBackfillRequest() {
        _backfillRequested.value = false
        _status.value = _status.value.copy(pendingBackfill = false)
    }

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
