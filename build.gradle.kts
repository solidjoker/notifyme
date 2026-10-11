// 根级构建脚本：只声明插件版本，不在根模块应用（apply false）
// 兼容 Gradle 8.x + AGP 8.5.x + Kotlin 2.2.x
// Kotlin 必须 ≥2.1：litert-api 2.2.0 的 kotlin_module 元数据是 2.3.0，2.0.x 编译器直接报 incompatible version。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}
