package io.github.ohsync.core;

import androidx.health.connect.client.records.metadata.DataOrigin;
import androidx.health.connect.client.records.metadata.Device;
import androidx.health.connect.client.records.metadata.Metadata;

import java.time.Instant;

/**
 * 构造带 clientRecordId 的 Metadata。
 *
 * 为什么用 Java 写：Kotlin 侧 Metadata 的构造器是 internal，Kotlin 代码调不到；
 * 而它的 Companion 工厂方法（autoRecordedWithId 等）只设置 id 字段，
 * **不设置 clientRecordId** —— 实测写进 Health Connect 后 client_record_id 全是 NULL，
 * 于是同一份数据每次推送都会被当成新记录插入（步数因此累积到 2 万多条）。
 *
 * internal 在 JVM 字节码里仍是 public，所以从 Java 可以直接调这个构造器。
 */
public final class MetadataFactory {

    private MetadataFactory() {
    }

    /** 关联到某个 OPPO 侧主键的记录：重复写入会按 clientRecordId 覆盖而不是新增。 */
    public static Metadata withClientId(String clientRecordId, Device device) {
        return new Metadata(
                Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED,
                Metadata.EMPTY_ID,
                new DataOrigin("io.github.ohsync"),
                Instant.now(),
                clientRecordId,
                System.currentTimeMillis(),
                device
        );
    }
}
