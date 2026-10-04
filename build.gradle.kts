// OHSync 根构建脚本：仅声明插件版本（apply false），模块各自 apply。
//
// AGP 9.0 起内置 Kotlin 支持，不再需要 org.jetbrains.kotlin.android 插件；
// Compose 编译器插件同样由 AGP 内置的 Kotlin 提供，这里也不单独声明。
plugins {
    id("com.android.application") version "9.4.1" apply false
}
