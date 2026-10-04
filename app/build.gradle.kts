import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 测试版默认值来源：secrets.local.properties（本机密钥，已 gitignore，绝不入库）。
// open flavor 一律注入空串，保证发布包不含任何密钥。
val secretsFile = rootProject.file("secrets.local.properties")
val secrets = Properties().apply {
    if (secretsFile.exists()) secretsFile.inputStream().use { load(it) }
}

fun secret(key: String): String = secrets.getProperty(key, "").orEmpty()

/** buildConfigField 字符串字面量转义（防密钥里出现引号/反斜杠打坏生成代码） */
fun bcString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    // 与母项目命名保持一致：qiyeweixin 的 Android 端等价物
    namespace = "com.qiyeweixin.weixin_android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qiyeweixin.weixin_android"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.1.3"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "channel"
    productFlavors {
        create("open") {
            // 发布版：全部默认值为空，APK 内不得出现任何密钥字符串
            buildConfigField("String", "DEFAULT_ANALYSIS_PROTOCOL", bcString(""))
            buildConfigField("String", "DEFAULT_ANALYSIS_URL", bcString(""))
            buildConfigField("String", "DEFAULT_ANALYSIS_KEY", bcString(""))
            buildConfigField("String", "DEFAULT_ANALYSIS_MODEL", bcString(""))
            buildConfigField("String", "DEFAULT_LAYA_URL", bcString(""))
            buildConfigField("String", "DEFAULT_LAYA_KEY", bcString(""))
            buildConfigField("String", "DEFAULT_SERVER_URL", bcString(""))
            buildConfigField("String", "DEFAULT_MONITOR_TOKEN", bcString(""))
        }
        create("beta") {
            // 测试版：与主包共存同机，默认值从 secrets.local.properties 注入
            // （AGP 禁止 flavor 名以 test 开头，故内部名用 beta，产物名仍叫 test）
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            // 与主包同名同图标时用户极易点错（已实际发生：装了 open 包却以为在用测试版，
            // 表现为「无预置模型、无历史回填」——open 包零预置是设计使然）。
            // 测试版桌面名加后缀，两个图标一眼可辨。
            resValue("string", "app_name", "微信分析助手·测试")
            buildConfigField("String", "DEFAULT_ANALYSIS_PROTOCOL", bcString("openai"))
            buildConfigField("String", "DEFAULT_ANALYSIS_URL", bcString(secret("GLM_BASE_URL")))
            buildConfigField("String", "DEFAULT_ANALYSIS_KEY", bcString(secret("GLM_API_KEY")))
            buildConfigField("String", "DEFAULT_ANALYSIS_MODEL", bcString(secret("GLM_MODEL")))
            buildConfigField("String", "DEFAULT_LAYA_URL", bcString(secret("JEV_URL")))
            buildConfigField("String", "DEFAULT_LAYA_KEY", bcString(secret("JEV_API_KEY")))
            buildConfigField("String", "DEFAULT_SERVER_URL", bcString(secret("MONITOR_URL")))
            buildConfigField("String", "DEFAULT_MONITOR_TOKEN", bcString(secret("MONITOR_TOKEN")))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 输出 APK 改名：weixin-monitor-open.apk / weixin-monitor-test.apk
    applicationVariants.all {
        val channel = if (flavorName == "beta") "test" else flavorName
        outputs.all {
            val impl = this as? com.android.build.gradle.internal.api.ApkVariantOutputImpl
            if (impl != null) {
                impl.outputFileName = "weixin-monitor-$channel.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 仅引入最基础的 AndroidX 依赖，UI 用传统 View + RecyclerView，避免 Compose 依赖链
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // org.json 为 Android 平台内置，MessageStore 的 JSON Lines 持久化无需额外依赖

    // 定时上报：WorkManager 是系统级兼容的周期任务方案（休眠/重启后仍可靠调度）
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // 上报用 OkHttp；JSON 仍用平台内置 org.json，不引 Gson/Moshi
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
