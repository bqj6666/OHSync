# OHSync

把 **OPPO 健康**（国行版）的数据同步进 Android 原生 **健康数据共享（Health Connect）**。

> 作者：[bqj6666](https://github.com/bqj6666) ｜ 版本：**1.1.0**（versionCode 11000） ｜ 许可证：**MIT**（见 [LICENSE](LICENSE)）

> **本仓库是 LSPosed 模块索引 / 发布页，不含源码。** 完整源码、构建方式、Issue 请前往源码仓库：
>
> - 🔗 **源码仓库**：[bqj6666/OHSync](https://github.com/bqj6666/OHSync)
> - 📦 **Releases**：[https://github.com/bqj6666/OHSync/releases](https://github.com/bqj6666/OHSync/releases)

---

## 为什么需要它

OPPO 健康国行版**完全没有** Health Connect 集成。国际版 OHealth 支持，但国行不是把
开关藏起来，而是整块功能被裁掉（实测依据）：

- Manifest 里没有任何 `android.permission.health.*`
- 全部 dex 文件里搜不到 `androidx.health` / `healthconnect` 的任何引用
- 它自己另开了一套接口：`com.oplus.health.apiprovider`，`protectionLevel=signature`

所以本模块直接读 OPPO 健康自己的数据库，再代你写入 Health Connect。

## 同步内容

| OPPO 健康 | Health Connect |
|---|---|
| 步数、距离、活动卡路里 | `StepsRecord`、`DistanceRecord`、`ActiveCaloriesBurnedRecord` |
| 心率 | `HeartRateRecord` |
| 睡眠（含分段） | 带 `stages` 的 `SleepSessionRecord` |
| 血压、血氧 | `BloodPressureRecord`、`OxygenSaturationRecord` |
| 体重 | `WeightRecord` |

Health Connect 没有对应记录类型的 18 类数据（压力、听力健康、鼾声 / OSA、体测评分、
各类预警、心电等）**按设计丢弃**，并在应用内完整列出，不会静默吞掉。

### 睡眠分段

OPPO 的 `DBSleepPiece.type` 是它的私有枚举，代码里查不到语义定义。本模块的映射是
**与 OPPO 健康界面逐项对账**得出的，不是猜的：

| type | 含义 | 对账依据（实测某晚） |
|---|---|---|
| 0 | 清醒 | 1 段 2 分钟 ＝ 界面「清醒 1 次，2 分钟」 |
| 2 | 深睡 | 7 段 68 分钟 ＝ 界面「深睡 1 小时 8 分钟」 |
| 3 | 快速眼动 | 10 段 119 分钟（24.9%）＝ 界面 25% |
| 4 | 浅睡 | 18 段 288 分钟（60.4%）＝ 界面 59% |

## 功能

- **自动同步间隔可选**：仅手动 / 15 分钟 / 30 分钟 / 1 小时 / 3 / 6 / 12 小时 / 每天
- **回填时间范围可选**：默认最近 3 个月
- **自动同步走增量**，只推上次之后的新数据
- **重复推送幂等**：靠 `clientRecordId` 覆盖，不会产生重复记录

## 工作原理

一个 APK，两个角色。

```
com.heytap.health 进程                    io.github.bqj6666.ohsync 主进程
----------------------------------      ----------------------------------
OHSyncHook（Xposed 注入）
  + 截获已打开的数据库  --IPC-->  SyncReceiverProvider（token 校验）
  + 读取表（只 SELECT）              + 规范化数据
  + 按间隔推送                       + HealthConnectClient（持有 HC 权限）
```

- **读取在目标进程内完成**：复用它自己打开的 `SupportSQLiteDatabase` 实例，
  不复制也不解密那个 138 MB 主库，因此不引入额外 SQLCipher 依赖，也不会有符号冲突
- **写入必须在主进程**：Health Connect 按调用方 UID 校验权限，注入进程没有写权限

## 界面

![同步状态](screenshots/home.png)

![设置与数据范围](screenshots/settings.png)

## 安装

需要 Android 14+、Root、LSPosed，以及可用的 Health Connect。

1. 安装 APK，**先打开一次**（系统不允许启动从未打开过的应用的数据接口）
2. 在应用内点「去授权」，勾选要授予的健康数据类型
3. 在 LSPosed 里启用本模块，作用域只勾 **OPPO 健康**
4. 打开一次 OPPO 健康
5. 回到本应用点「立即同步」

## 常见问题

**「去授权」没反应 / 在 Health Connect 里显示为「非活跃应用」**

Health Connect 要求应用声明一个「用途说明」页面（`VIEW_PERMISSION_USAGE` +
`HEALTH_PERMISSIONS` 分类），缺了它授权页会直接退出。本模块已声明。

另外**每次安装更新，Health Connect 都会撤销该应用的全部健康权限**（平台行为），
更新后重新点一次「去授权」即可。

**应用详情里「权限 → 健康、健身和身心状态」这一行消失了**

ColorOS 只在「至少已授权一项健康权限」时才显示该行。全部关掉后系统设置里就没有
入口了，请用应用内的「去授权」按钮重新授权。

**同步一直没反应**

确认 LSPosed 里模块已启用、作用域勾选了「OPPO 健康」，并打开一次 OPPO 健康。

**关于后台运行**

本模块会常驻一个前台服务（一条静音通知）。实测过广播唤醒与 AlarmManager 两条路，
都**拉不起已经退出的进程**（Android 对 cached 应用的限制），前台服务是唯一可靠方式。
通知为最低重要级别：不响、不震、不显示角标。可在设置里关闭，代价是自动同步失效。

## 许可

MIT。OPPO 健康是闭源软件，本模块仅在你的设备上读取你自己拥有的数据。
与 OPPO、Google 均无关联。
