package io.github.ohsync.core

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

/**
 * Health Connect 客户端封装：可用性、权限、写入、幂等删除。
 *
 * 只做主进程这一侧。Hook 进程永远不碰这里 —— HC 按 UID 判权限，注入进程写不进去。
 */
class HcClient(private val context: Context) {

    val client: HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(context, PROVIDER) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else null

    fun permissions(): Set<String> = if (client == null) emptySet() else REQUIRED_PERMISSIONS

    suspend fun granted(): Set<String> {
        val c = client ?: return emptySet()
        return c.permissionController.getGrantedPermissions()
    }

    suspend fun isWriteGranted(): Boolean =
        HealthPermission.getWritePermission(HealthConnectClient::class.java).all { it in granted() }

    /**
     * 幂等写入：clientRecordId 已存在则先删后插。
     * 不去重的话重复回填会在 HC 里堆出重复记录。
     */
    suspend fun upsert(record: Record, clientRecordId: String) {
        val c = client ?: throw IllegalStateException("Health Connect unavailable")
        val clazz = record::class.java
        val existing = c.readRecords(
            androidx.health.connect.client.request.ReadRecordsRequest(
                recordType = clazz,
                clientRecordIds = setOf(clientRecordId),
                timeRangeFilter = TimeRangeFilter.all(),
            )
        ).records
        if (existing.isNotEmpty()) {
            c.deleteRecords(clazz, setOf(clientRecordId), TimeRangeFilter.all())
        }
        c.insertRecords(listOf(record))
    }

    /** 供 UI 显示的可用性文字。 */
    fun availabilityText(): String = when (client) {
        null -> "Health Connect 不可用（SDK 状态=${HealthConnectClient.getSdkStatus(context, PROVIDER)}）"
        else -> "Health Connect 可用"
    }

    companion object {
        private const val TAG = "OHSyncHc"
        const val PROVIDER = "com.google.android.apps.healthdata"

        private val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(HealthConnectClient::class.java),
        )

        fun clientRecordId(type: RecordType, sourceKey: String) = "ohsync:${type.id}:$sourceKey"

        fun log(msg: String) = Log.i(TAG, msg)

        fun now() = Instant.now()
    }
}
