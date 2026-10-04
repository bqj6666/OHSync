# OHSync

把 **OPPO 健康**（`com.heytap.health`，国行版）的数据同步进 Android 原生
**Health Connect**。

[English](README.md)

## 为什么需要它

OPPO 健康国行版**完全没有** Health Connect 集成。国际版 OHealth 支持，但国行不是
把开关藏起来了，而是整块功能被裁掉：

- Manifest 里没有任何 `android.permission.health.*`
- 全部 dex 文件里搜不到 `androidx.health` / `healthconnect` 的任何引用
- 它自己另开了一套接口：`com.oplus.health.apiprovider`，`protectionLevel=signature`

所以 OHSync 自己去读 OPPO 健康的数据库，再代你写入 Health Connect。

## 工作原理

一个 APK，两个角色。

```
com.heytap.health 进程                    io.github.ohsync 主进程
----------------------------------      ----------------------------------
OHSyncHook（Xposed 注入）
  + 截获已打开的数据库  --IPC-->  SyncReceiverProvider（校验 token）
  + DexKit：发现 Room 表              + 规范化数据
  + 只读查询                           + HealthConnectClient（持有 HC 权限）
  + 写入即推送

有两点值得单独说明：

**读取发生在目标进程内部。** OHSync 不会复制并解密那个 138 MB 的数据库，而是 hook
OPPO 健康自己打开 `SupportSQLiteDatabase` 的那一刻，直接复用那个实例。这样不用引入
额外的 SQLCipher 依赖、不用自己处理口令，也不会和该进程里已加载的 `libsqlcipher.so`
发生符号冲突。

**写入发生在 OHSync 自己的进程。** Health Connect 按调用方 UID 校验权限，注入到
`com.heytap.health` 里的代码没有 `WRITE_HEALTH_DATA`，无法直接写入。所以由注入进程
读取，交给持有权限的主进程写入。

## 安装

需要：

- Android 14（API 34）或更高
- Health Connect 可用（Android 14+ 自带，或安装 Google Health Connect 应用）
- Root（KernelSU / Magisk）—— 需要读取其他应用的私有数据库
- LSPosed 或兼容的 Xposed 框架

步骤：

1. 安装 APK 并打开一次 OHSync
2. 按提示授予 Health Connect 写入权限
3. 复制应用里显示的配对口令
4. 在 LSPosed 中启用 OHSync，作用域设为 **OPPO 健康**
5. 打开一次 OPPO 健康，让它建立数据库
6. 用 `adb logcat -s OHSyncHook` 观察，它会打印找到的表
7. 回到 OHSync 点「同步历史数据」

## 同步哪些数据

映射关系在运行时从 OPPO 健康的 Room 实体里发现，不是硬编码的，所以 OPPO 只改内部
实现时仍能工作。

| OPPO 健康 | Health Connect |
|---|---|
| 步数、距离、活动卡路里 | `StepsRecord`、`DistanceRecord`、`ActiveCaloriesBurnedRecord` |
| 心率 | `HeartRateRecord`、`RestingHeartRateRecord` |
| 睡眠（含分段阶段） | 带 `stages` 的 `SleepSessionRecord` |
| 运动记录 | `ExerciseSessionRecord` |
| 血压、血氧、血糖 | 对应记录类型 |
| 体重、体脂 | `WeightRecord`、`BodyFatRecord` |
| 放松 / 呼吸训练 | `MindfulnessSessionRecord` |

Health Connect 没有对应记录类型的数据**按设计丢弃** —— 压力、听力健康、鼾声 / OSA、
体测评分以及各类预警表。应用里会列出完整的废弃清单，不会静默吞掉数据。

## 隐私

所有数据都留在设备上。OHSync 没有网络代码，数据只在同一台手机上的两个进程之间流动，
由安装时生成的随机 token 校验。

模块以 `PROTECTIVE` 异常模式运行，每个 hook 内部都有 try/catch，OHSync 出问题也不会
把 OPPO 健康带崩。所有数据库操作都是只读的 `SELECT`。

## 构建

```bash
./gradlew :app:assembleDebug
```

需要 JDK 21。在 ARM64 Linux 宿主上，如果 AGP 自带的 `aapt2` 无法原生运行，把
`OHSYNC_AAPT2` 指向包装脚本：

```bash
OHSYNC_AAPT2=/path/to/wrapper/aapt2 ./scripts/build.sh :app:assembleDebug
```

## 免责声明

本项目仅供个人使用，读取的是你自己设备上、你自己拥有的数据库。OPPO 健康是闭源软件，
一旦它变动本项目可能失效。与 OPPO、Google 均无关联。
