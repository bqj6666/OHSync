package io.github.ohsync.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.time.TimeRangeFilter

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

    /**
     * 需要授予的权限：每种记录类型的**写**权限。
     *
     * 只请求写：OHSync 只往 Health Connect 写，不读别的应用写的健康数据。
     * 少请求一半权限，授权界面也短一半。
     */
    val requiredPermissions: Set<String> = buildSet {
        for (type in RecordType.syncable) {
            val kclass = hcRecordKClass(type) ?: continue
            runCatching { HealthPermission.getWritePermission(kclass) }
                .getOrNull()?.let { add(it) }
        }
    }

    suspend fun grantedPermissions(): Set<String> =
        client?.permissionController?.getGrantedPermissions() ?: emptySet()

    /**
     * 能否写入。
     *
     * 判据是**平台层权限**，不是 Health Connect 内部的授权记录：
     * 两者会不一致（比如应用更新时 HC 会撤销内部记录，但平台权限仍在），
     * 而真正决定写入成败的是平台权限。用 HC 的内部记录会让界面显示「未授权」
     * 却实际写入成功，误导用户。
     */
    fun isWriteGranted(): Boolean {
        val ctx = context
        return requiredPermissions.isNotEmpty() && requiredPermissions.all {
            ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
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

    /**
     * 删除本应用写入的全部记录。
     *
     * 用途：早期版本没带 clientRecordId，同一份数据被反复插入产生了大量重复，
     * 需要一次清干净再重同步。只删自己的（dataOriginFilter 限定本包名），
     * 不碰其他应用写的数据。
     */
    suspend fun deleteAllMine(): Int {
        val c = client ?: return 0
        var deleted = 0
        for (type in RecordType.syncable) {
            val kclass = hcRecordKClass(type) ?: continue
            runCatching {
                // 这个重载只删「调用方应用自己写入的」指定类型记录，不会碰到别的应用。
                // TimeRangeFilter 没有 all()，用一个覆盖全部时间的大区间代替。
                c.deleteRecords(kclass, TimeRangeFilter.between(EPOCH, FAR_FUTURE))
                deleted++
            }.onFailure { Log.w(TAG, "清除 ${type.label} 失败", it) }
        }
        Log.i(TAG, "已清除 $deleted 类记录")
        return deleted
    }

    fun availabilityText(): String =
        if (client == null) "Health Connect 不可用（SDK 状态=$sdkStatus）" else "Health Connect 可用"

    companion object {
        private const val TAG = "OHSyncHc"
        private const val BATCH = 400

        /** 用于「清空全部」的时间区间端点。 */
        private val EPOCH: java.time.Instant = java.time.Instant.EPOCH
        private val FAR_FUTURE: java.time.Instant =
            java.time.LocalDate.of(2100, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
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
