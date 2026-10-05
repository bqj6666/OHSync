# OHSync

把 **OPPO 健康**（`com.heytap.health`，国行版）的数据同步进 Android 原生
**健康数据共享（Health Connect）**。

[English](README.en.md)


**下载**：[最新版本](https://github.com/bqj6666/OHSync/releases/latest) ·
[全部版本](https://github.com/bqj6666/OHSync/releases)

## 为什么需要它

OPPO 健康国行版**完全没有** Health Connect 集成。国际版 OHealth 支持，但国行不是把
开关藏起来，而是整块功能被裁掉（实测依据）：

- Manifest 里没有任何 `android.permission.health.*`
- 全部 dex 文件里搜不到 `androidx.health` / `healthconnect` 的任何引用
- 它自己另开了一套接口：`com.oplus.health.apiprovider`，`protectionLevel=signature`

所以 OHSync 直接读 OPPO 健康自己的数据库，再代你写入 Health Connect。

## 工作原理

一个 APK，两个角色。

```
com.heytap.health 进程                    io.github.ohsync 主进程
----------------------------------      ----------------------------------
OHSyncHook（Xposed 注入）
  + 截获已打开的数据库  --IPC-->  SyncReceiverProvider（token 校验）
  + 读取表（只 SELECT）              + 规范化数据
  + 按间隔推送                       + HealthConnectClient（持有 HC 权限）
```

两点说明：

**读取在目标进程内完成。** OHSync 不复制也不解密那个 138 MB 的数据库，而是截获
OPPO 健康自己打开的 `SupportSQLiteDatabase` 实例复用。这样不引入额外的 SQLCipher
依赖、不自己处理口令，也不会与它进程里已加载的 `libsqlcipher.so` 冲突。

**写入必须在 OHSync 自己的进程。** Health Connect 按调用方 UID 校验权限，注入到
`com.heytap.health` 的代码没有写权限。所以由注入进程读取，交给主进程写入。

## 安装

需要：

- Android 14（API 34）或更高
- 可用的 Health Connect（Android 14+ 自带，或安装 Google 的 Health Connect 应用）
- Root（KernelSU / Magisk）—— 需要读取其他应用的私有数据库
- LSPosed 或兼容的 Xposed 框架

步骤：

1. 安装 APK，**先打开一次 OHSync**（系统不允许启动从未打开过的应用的数据接口）
2. 在应用里点「去授权」，勾选要授予的健康数据类型
3. 在 LSPosed 里启用 OHSync，作用域只勾 **OPPO 健康**
4. 打开一次 OPPO 健康，让它把数据库准备好
5. 回到 OHSync，点「立即同步」

## 同步设置

| 设置 | 说明 |
|---|---|
| 自动同步间隔 | 仅手动 / 15 分钟 / 30 分钟 / 1 小时（默认）/ 3 / 6 / 12 小时 / 每天 |
| 回填时间范围 | 只读取这段时间内的数据，默认最近 3 个月 |

- **自动同步**走增量：只推上次同步之后的新数据，省电省资源
- **立即同步**走完整窗口：用于补历史或修数据
- 两者都靠 `clientRecordId` 幂等覆盖，重复推送不会产生重复记录

## 同步哪些数据

映射关系按**运行时真实表结构**（`sqlite_master` + `PRAGMA table_info`）推导，
不硬编码表名，OPPO 只改内部实现时仍能工作。

| OPPO 健康 | Health Connect |
|---|---|
| 步数、距离、活动卡路里 | `StepsRecord`、`DistanceRecord`、`ActiveCaloriesBurnedRecord` |
| 心率 | `HeartRateRecord` |
| 睡眠（含分段阶段） | 带 `stages` 的 `SleepSessionRecord` |
| 血压、血氧 | `BloodPressureRecord`、`OxygenSaturationRecord` |
| 体重 | `WeightRecord` |

Health Connect 没有对应记录类型的数据**按设计丢弃**（共 18 类）：压力、听力健康、
鼾声 / OSA、体测评分、各类预警、心电、久坐提醒等。应用里会完整列出，不会静默丢弃。

### 睡眠分段

OPPO 的 `DBSleepPiece.type` 是它的私有枚举，代码里查不到语义定义。
本项目的映射是**与 OPPO 健康界面逐项对账**得出的，不是猜的：

| type | 含义 | 对账依据（实测某晚） |
|---|---|---|
| 0 | 清醒 | 1 段 2 分钟 ＝ 界面「清醒 1 次 \| 2 分钟」 |
| 2 | 深睡 | 7 段 68 分钟 ＝ 界面「深睡 1 小时 8 分钟」 |
| 3 | 快速眼动 | 10 段 119 分钟，占比 24.9% ＝ 界面 25% |
| 4 | 浅睡 | 18 段 288 分钟，占比 60.4% ＝ 界面 59% |

## 关于后台运行

应用会常驻一个**前台服务**（一条静音通知）。这是本机实测后的结论，不是保险起见。

读取端跑在 OPPO 健康进程里，读完要通过本应用的数据接口把数据交过来。而**本机系统
不会因为「有别的应用来访问数据接口」就去启动一个没在跑的应用**（用另一个应用做过
对照，行为一致）。进程不在，写入就全部失败 —— 也就是「把卡片划掉之后不再同步」。

三条路都试过：

| 方案 | 实测结果 |
|---|---|
| 普通后台服务 + 读取端广播唤醒 | 广播被系统入队，但**不执行**，进程拉不起来 |
| 普通后台服务 + AlarmManager 兜底 | 闹钟到点后**没有反应**，进程数始终为 0 |
| **前台服务** | 进程稳定存活，划掉卡片也不受影响 ✅ |

广播与闹钟拉不起 cached 应用，是 Android 本身的限制，不是实现问题。所以前台服务是
唯一可靠的方式，代价就是那条通知。通知已降到最低重要级别：不响、不震、不显示角标。

顺带一提：同类项目看起来「没有通知也能常驻」，是因为它的功能跑在系统进程里
（Xposed 注入到 GMS / 设置），它自己的应用进程其实也经常不在。而 OHSync 的写入
必须由自己的进程完成（Health Connect 按调用方 UID 校验权限），所以不能照搬。

资源占用实测：

- CPU：空闲时约每小时 1.8 秒
- 内存：约 59 MB（Java 堆 12 MB / Native 堆 15 MB / 代码段 29 MB）

设置里可以关掉「保持后台运行」。关掉后没有常驻组件，自动同步会失效，
只能打开应用手动同步 —— 那时可以省下上面这份占用。

## 隐私

所有数据都留在设备上。OHSync 没有网络代码，数据只在同一台手机的两个进程之间流动。

模块以 `PROTECTIVE` 异常模式运行，每个 hook 内部都有 try/catch，OHSync 出问题不会
把 OPPO 健康带崩。所有数据库操作都是只读的 `SELECT`。

## 常见问题

**点了「去授权」没反应 / 应用在 Health Connect 里显示为「非活跃应用」**

Health Connect 的授权流程要求应用声明一个「用途说明」页面
（`VIEW_PERMISSION_USAGE` + `HEALTH_PERMISSIONS` 分类）。缺了它，HC 的授权页会直接
退出。本项目已声明，正常情况不会遇到。若你自行构建并改动过 manifest，请保留该声明。

另外，**每次安装更新，Health Connect 都会撤销该应用的全部健康权限**（平台行为）。
更新后重新点一次「去授权」即可。

**应用详情里的「权限 → 健康、健身和身心状态」这一行消失了**

ColorOS 只在「至少已授权一项健康权限」时才显示该行。若你把权限全部关掉，系统设置里
就没有再打开的入口了 —— 所以请用 OHSync 内的「去授权」按钮重新授权。

**同步一直没反应**

确认 LSPosed 里模块已启用、作用域勾选了「OPPO 健康」，并打开一次 OPPO 健康。
应用的「同步状态」里会显示读取端与写入权限是否就绪。

## 构建

```bash
./gradlew :app:assembleDebug
```

需要 JDK 21。在 ARM64 Linux 宿主上，若 AGP 自带的 `aapt2` 无法原生运行，把
`OHSYNC_AAPT2` 指向包装脚本：

```bash
OHSYNC_AAPT2=/path/to/wrapper/aapt2 ./scripts/build.sh :app:assembleDebug
```

## 许可

MIT。OPPO 健康是闭源软件，本项目仅在你的设备上读取你自己拥有的数据。
与 OPPO、Google 均无关联。
