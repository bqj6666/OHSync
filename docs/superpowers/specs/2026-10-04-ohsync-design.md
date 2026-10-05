# OHSync 设计规格

日期：2026-10-04
状态：待用户复核
包名：`io.github.bqj6666.ohsync`

## 1. 目标

把 OPPO 健康（`com.heytap.health`，国行版 6.9.40）的数据同步进 Android 原生 Health Connect，
包含历史数据一次性回填与持续实时同步。HC 中没有对应记录类型的数据类型直接废弃，但在 UI 上
可见，不静默吞掉。

## 2. 非目标

- 不做云同步、不做账号体系、不做数据上传。数据只在本机两个进程之间流动。
- 不修改 OPPO 健康的任何文件、数据库、设置。所有读取走只读路径。
- 不逆向 `libencrypt.so`、不硬编码 SQLCipher 口令。
- 不与国际版 OHealth 共存，不负责包名兼容（本机型只有 `com.heytap.health`）。

## 3. 独立性与项目归属

OHSync 是独立项目、独立仓库、独立包名。除通用第三方依赖（AGP、Kotlin、libxposed、
DexKit、Compose）之外不依赖任何其他项目，不共用源码、签名或发布流程。

UI 组件库选择 Material 3，界面保持最简，不引入第三方 UI 套件。

## 4. 架构

单个 APK，两个角色。

```
com.heytap.health 进程                      io.github.bqj6666.ohsync 主进程
------------------------------          -------------------------------
OHSyncHook (Xposed 注入)                 SyncReceiverProvider (exported)
  DexKitLocator  定位实体/表   token      token 校验
  KeyCapture    抓 SQLCipher  -------->  落盘 -> 规范化
  TableReader   只读查询       IPC       HealthConnectWriter
  PushPump      写库即推送               clientRecordId 幂等 / 写 HC 持证
                                          MainActivity (Material 3)
```

### 4.1 为什么必须跨进程

Health Connect 按调用方 UID 校验权限。注入到 `com.heytap.health` 进程里的代码没有
`android.permission.health.WRITE_HEALTH_DATA`，直接写 HC 会被拒。读与写必须分离：
注入进程读，推给主进程写。

单APK 的收益：映射规则只有一份实现，Hook 侧只抽取原始行，主进程只翻译成 HC Record，
两端共用 RecordMapper 的类型枚举与字段定义，避免双份映射漂移。

### 4.2 跨进程传输

主进程导出 SyncReceiverProvider（exported=true，无权限保护），每次安装生成 32 字节
随机 token 存 DataStore。Hook 侧通过配对码获取 token（用户在 Hook 侧设置页粘贴主进程
生成的配对码）。不使用自定义权限：`com.heytap.health` 是第三方应用，签名级自定义权限
无法授予它。

## 5. 数据抽取

### 5.1 SQLCipher 口令

主库 /data/user/0/com.heytap.health/databases/database.db（约 138 MB）是 SQLCipher 加密库，
由 libsqlcipher.so 提供，原生符号含 sqlite3_key / sqlite3_rekey。原生侧只有
_calculateKey / key_generator，无明文口令，故不逆原生库。

改为运行时捕获：OHSyncHook hook net.zetetic.database.sqlcipher.SupportOpenHelper 的
数据库打开路径以及 System.loadLibrary("sqlcipher") 的调用点，取出传入的 key 字节数组，
打印到 LSPosed 日志。用户在 Hook 侧设置页粘贴一次口令后复用该口令读库。
进程内直接用 SQLCipher API 读，不复制 138 MB 库。

### 5.2 实体与表名发现

用 DexKit 扫描目标进程 ClassLoader：

- 从 @Entity(tableName=...) 注解取实体类与真实表名
- 从 DAO 的 @Query 字符串取实际 SQL 与访问的列
- 从实体字段的 getter 名与类型推断语义

产出映射报告（实体 -> 表名 -> 字段 -> 候选 HC Record 类型），作为 P0 交付物，
在铺开全类型前交用户过目。

### 5.3 字段映射规则

有明确对应即同步：

| OPPO 数据 | Health Connect Record |
|---|---|
| 步数 | StepsRecord |
| 距离 | DistanceRecord |
| 活动卡路里 | ActiveCaloriesBurnedRecord |
| 心率 | HeartRateRecord |
| 睡眠 | SleepSessionRecord + SleepStageRecord |
| 运动记录 | ExerciseSessionRecord |
| 体重 / 体脂 | WeightRecord / BodyFatPercentageRecord |
| 血氧 | OxygenSaturationRecord |
| 血压 | BloodPressureRecord |
| 放松训练 | MentalHealthSessionRecord（breathing） |

已判定为废弃（HC 无对应记录类型）：压力 Stress、听力健康 HearingHealth、
OSA/鼾声事件、体测评分 PhysicalFitness。废弃项在 UI 的已废弃类型清单中列出。

若 DexKit 扫出的实体超出上表，按同样规则处理：有 HC 类型则同步，无则废弃并记入清单。

## 6. 同步语义

- **历史回填**：Hook 进程启动后一次性全量推送；UI 提供重新回填按钮。
- **实时同步**：hook com.heytap.databaseengineservice.SportHealthDataService 的写库路径
  （dumpsys 实测该服务常驻且有活跃连接），写入即推送，不做轮询。
- **幂等**：clientRecordId = "ohsync:<类型>:<OPPO 主键>"，重复推送覆盖而非叠加。
- **自检**：DexKit 定位失败或表结构变化时，在 UI 上显红报错，绝不静默同步错数据。

## 7. 技术栈

- 包名 io.github.bqj6666.ohsync，独立仓库，准备公开发布到 GitHub
- AGP 9.4.1 / Kotlin 2.4.20 / Gradle wrapper
- compileSdk 37 / minSdk 34 / targetSdk 36（HC 要求 34+）
- io.github.libxposed:api:102.0.0（compileOnly）
- org.luckypray:dexkit:2.3.0
- androidx.health.connect:connect-client:1.1.0-alpha11
- Compose + Material 3，界面最简：状态卡、同步/回填按钮、废弃类型清单、日志页
- Xposed 作用域：com.heytap.health（仅此一个包名）
- 仓库内不提交密钥文件，发布签名流程另行确认

## 8. 环境注意事项

- 设备侧访问 app 私有数据必须 nsenter -t 1 -m，因为 shell 里的 /data/data、
  /data/user/0 被 tmpfs 遮蔽，只剩 Eta 自身与 GMS 两个目录。
- Linux 环境 SDK 缺 android-36/37 平台，compileSdk 37 前需补装。
  /tmp/sdk37_bak/android-37 有历史备份可参考。
- Health Connect client 未在本地 Gradle 缓存中，Google Maven 可达，需首次拉取。

## 9. 分期

| 阶段 | 内容 | 估时 |
|---|---|---|
| P0 | DexKit 出映射报告 + 抓到 SQLCipher 口令 | ~1 h |
| P1 | 步数端到端进 HC | ~2 h |
| P2 | 全类型铺开（表中全部 + DexKit 扫出的其余实体） | ~4-6 h |
| P3 | Material 3 UI、回填按钮、日志、废弃清单 | ~2 h |

每阶段结束用户可独立验证。

## 10. 验证方式

- P0：映射报告文件 + LSPosed 日志中的口令行
- P1：Health Connect App 中可见 OHSync 来源的步数记录
- P2：逐类型在 HC 中确认记录存在且数值合理
- P3：UI 交互与日志完整走一遍

## 11. 待确认项

- 发布签名与 release 打包流程（GitHub Actions 或本地）
- README 语言（仅英文 / 中英双语）
