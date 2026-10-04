package io.github.ohsync.core

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record

/**
 * Health Connect 客户端封装：可用性、权限、写入。
 *
 * 只存在于主进程。Hook 进程永远不碰这里 —— HC 按调用方 UID 判权限，
 * 注入到 com.heytap.health 的代码没有 WRITE_HEALTH_DATA，写不进去。
 */
class HcClient(private val context: Context) {

    private val sdkStatus = HealthConnectClient.getSdkStatus(context, PROVIDER)

    val client: HealthConnectClient? =
        if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }

    /** 需要用户授予的权限：每种记录类型的读、写权限。 */
    val requiredPermissions: Set<String> = buildSet {
        for (type in RecordType.syncable) {
            val kclass = hcRecordKClass(type) ?: continue
            runCatching { HealthPermission.getReadPermission(kclass) }
                .getOrNull()?.let { add(it) }
            runCatching { HealthPermission.getWritePermission(kclass) }
                .getOrNull()?.let { add(it) }
        }
    }

    suspend fun grantedPermissions(): Set<String> =
        client?.permissionController?.getGrantedPermissions() ?: emptySet()

    suspend fun isWriteGranted(): Boolean {
        val granted = grantedPermissions()
        return requiredPermissions.isNotEmpty() && requiredPermissions.all { it in granted }
    }

    /**
     * 写入一条记录。
     *
     * 幂等性依赖 Health Connect 对 clientRecordId 的 upsert 语义：同一应用、
     * 同一记录类型、同一 clientRecordId 再次写入会覆盖旧记录，前提是
     * clientRecordVersion 不小于已存版本 —— 所以这里用当前毫秒当版本号，
     * 保证每次写入都是"更新"而不是被丢弃。
     *
     * 这条语义需要装到设备上实测确认，代码先按此实现。
     */
    suspend fun write(record: Record) = writeAll(listOf(record))

    /**
     * 批量写入。Health Connect 单次上限 1000 条，这里按 400 分批：
     * 一次一条会让几千条数据要跑几千次 IPC，慢到不可用。
     */
    suspend fun writeAll(records: List<Record>) {
        val c = client ?: throw IllegalStateException("Health Connect unavailable")
        records.chunked(BATCH).forEach { chunk -> c.insertRecords(chunk) }
    }

    fun availabilityText(): String =
        if (client == null) "Health Connect 不可用（SDK 状态=$sdkStatus）" else "Health Connect 可用"

    companion object {
        private const val TAG = "OHSyncHc"
        private const val BATCH = 400
        const val PROVIDER = "com.google.android.apps.healthdata"

        fun clientRecordId(type: RecordType, sourceKey: String) = "ohsync:${type.id}:$sourceKey"

        /** hcRecord 存的是类名，反射拿 KClass；拿不到就跳过该类型。 */
        @Suppress("UNCHECKED_CAST")
        fun hcRecordKClass(type: RecordType): kotlin.reflect.KClass<out Record>? = runCatching {
            Class.forName("androidx.health.connect.client.records.${type.hcRecord}")
                .kotlin as kotlin.reflect.KClass<out Record>
        }.getOrNull()

        fun log(msg: String) = Log.i(TAG, msg)
    }
}
