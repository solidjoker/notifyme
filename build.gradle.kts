// 根级构建脚本：只声明插件版本，不在根模块应用（apply false）
// 兼容 Gradle 8.x + AGP 8.5.x + Kotlin 2.0.x
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
