package io.github.ohsync.core

import java.time.Instant

/**
 * Hook 进程推给主进程的一条记录。字段刻意保持"扁平 + 语义中立"，
 * 让它能直接跨进程传输（实现见 SyncReceiverProvider）。
 *
 * [sourceKey] 是 OPPO 侧的主键，用于生成 clientRecordId 实现幂等。
 */
data class SyncRecord(
    val type: String,
    val sourceKey: String,
    val startTime: Long,
    val endTime: Long,
    val values: Map<String, Double> = emptyMap(),
    val zoneOffsetSeconds: Int? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    val recordType: RecordType get() = RecordType.fromId(type)
}

/** 一次性推送请求。 */
data class SyncBatch(
    val records: List<SyncRecord>,
    /** true 表示这是历史全量回填，false 表示实时增量。 */
    val isBackfill: Boolean,
)

/** 同步状态，供 UI 展示。 */
data class SyncStatus(
    val hookConnected: Boolean = false,
    val hcPermissionGranted: Boolean = false,
    val lastSyncAtMillis: Long? = null,
    val syncedCount: Long = 0,
    val skippedCount: Long = 0,
    val failedCount: Long = 0,
    val lastError: String? = null,
    val pendingBackfill: Boolean = false,
) {
    val healthy: Boolean get() = hookConnected && hcPermissionGranted && lastError == null
}

/** 解析时间戳：OPPO 库统一为毫秒。 */
fun Instant.toEpochMillis(): Long = toEpochMilli()
