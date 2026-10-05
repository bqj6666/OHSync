# DexKit 依赖反射读取目标应用结构，需保留其内部类名
-keep class org.luckypray.dexkit.** { *; }
-keep class io.github.bqj6666.ohsync.hook.** { *; }

# Hook 入口由 META-INF/xposed/java_init.list 声明，按名字加载，禁止混淆
-keep class io.github.bqj6666.ohsync.hook.OHSyncEntry { *; }
