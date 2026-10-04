import java.io.File
import java.io.StringReader
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 读取 .properties，容忍 UTF-8 BOM。
 *
 * Windows 记事本与 PowerShell 5.1 的 Set-Content/Out-File 默认会写 BOM（EF BB BF），
 * 而 java.util.Properties 会把 BOM 当作第一个键名的一部分（键变成 "\uFEFFstoreFile"），
 * 结果是配置「看起来填对了却静默读不到值」——签名退回未签名、beta 密钥注入变空串。
 */
fun loadPropsIgnoringBom(file: File): Properties = Properties().apply {
    if (file.exists()) load(StringReader(file.readText().removePrefix("\uFEFF")))
}

// 测试版默认值来源：secrets.local.properties（本机密钥，已 gitignore，绝不入库）。
// open flavor 一律注入空串，保证发布包不含任何密钥。
val secretsFile = rootProject.file("secrets.local.properties")
val secrets = loadPropsIgnoringBom(secretsFile)

fun secret(key: String): String = secrets.getProperty(key, "").orEmpty()

/** buildConfigField 字符串字面量转义（防密钥里出现引号/反斜杠打坏生成代码） */
fun bcString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// ---------------------------------------------------------------------------
// 发布签名（release signing）
//
// 配置来源二选一，环境变量优先（CI 从 GitHub Secrets 注入）：
//   1. 仓库根目录 keystore.properties（本机，已 gitignore；模板见 keystore.properties.example）
//   2. 环境变量 NOTIFYME_KEYSTORE_FILE / _KEYSTORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD
//
// 两者都缺失或密钥库文件不存在时，release 构建退回「未签名」并只打警告、不失败——
// 保证任何人 clone 仓库后 assembleOpenRelease 都能跑通（拿到的 APK 需自行签名才能安装）。
// 详见 docs/BUILD.md「发布签名与 Release」。
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = loadPropsIgnoringBom(keystorePropsFile)

/** 签名配置读取：环境变量优先，其次 keystore.properties */
fun signProp(propKey: String, envKey: String): String =
    (System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }
        ?: "")

/** 密钥库路径解析：绝对路径直接用，相对路径按仓库根目录解析 */
fun resolveKeystoreFile(path: String): File? =
    if (path.isBlank()) null else File(path).let { if (it.isAbsolute) it else rootProject.file(path) }

val releaseStoreFile = resolveKeystoreFile(signProp("storeFile", "NOTIFYME_KEYSTORE_FILE"))
val releaseStorePassword = signProp("storePassword", "NOTIFYME_KEYSTORE_PASSWORD")
val releaseKeyAlias = signProp("keyAlias", "NOTIFYME_KEY_ALIAS")
val releaseKeyPassword = signProp("keyPassword", "NOTIFYME_KEY_PASSWORD")

val hasReleaseSigning = releaseStoreFile?.exists() == true &&
    releaseStorePassword.isNotBlank() && releaseKeyAlias.isNotBlank() && releaseKeyPassword.isNotBlank()

android {
    namespace = "com.notifyme.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.notifyme.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.2.0"
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

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // 说清「为什么没签上」，否则用户只看到未签名 APK 却不知配置错在哪
                val reason = when {
                    releaseStoreFile == null ->
                        if (keystorePropsFile.exists()) "keystore.properties 里没有有效的 storeFile"
                        else "未找到 keystore.properties，也未设置 NOTIFYME_KEYSTORE_FILE"
                    !releaseStoreFile.exists() -> "密钥库文件不存在：$releaseStoreFile"
                    releaseStorePassword.isBlank() -> "缺少 storePassword / NOTIFYME_KEYSTORE_PASSWORD"
                    releaseKeyAlias.isBlank() -> "缺少 keyAlias / NOTIFYME_KEY_ALIAS"
                    releaseKeyPassword.isBlank() -> "缺少 keyPassword / NOTIFYME_KEY_PASSWORD"
                    else -> "签名配置不完整"
                }
                logger.warn(
                    "[notifyme] 发布签名未生效（$reason），assembleOpenRelease 将产出未签名 APK，" +
                        "无法直接安装。配置方式见 docs/BUILD.md「发布版签名」。"
                )
            }
        }
    }

    // 输出 APK 改名：notifyme-open.apk / notifyme-test.apk
    applicationVariants.all {
        val channel = if (flavorName == "beta") "test" else flavorName
        outputs.all {
            val impl = this as? com.android.build.gradle.internal.api.ApkVariantOutputImpl
            if (impl != null) {
                impl.outputFileName = "notifyme-$channel.apk"
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

    testOptions {
        // 单测里碰到未 mock 的 Android API（如 Log.w）返回默认值而不是抛
        // "Method ... not mocked"，让纯逻辑测试不必为一次日志调用引入 Robolectric
        unitTests.isReturnDefaultValues = true
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

    // ---- 单元测试（M1 测试地基，只进 test classpath，不进 APK）----
    testImplementation("junit:junit:4.13.2")
    // JVM 单测里 android.jar 的 org.json 只是空壳（方法返回默认值 → JSONObject 恒为空），
    // 必须放一份真实现；AGP 把 mockable android.jar 排在 classpath 最后，所以这份生效。
    testImplementation("org.json:json:20250107")
}
