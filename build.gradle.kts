// OHSync 根构建脚本：仅声明插件版本（apply false），模块各自 apply。
//
// AGP 9.0 起内置 Kotlin 支持，不再需要 org.jetbrains.kotlin.android；
// 但 Compose 编译器插件仍必须单独声明（Kotlin 2.0 起的要求）。
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
