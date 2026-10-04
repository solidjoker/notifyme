# 构建指南（从源码构建 Android APK）

本文面向需要自行构建 notifyme Android 客户端的开发者。
普通用户请直接从 [Releases](https://github.com/solidjoker/notifyme/releases) 下载 `notifyme-open.apk` 安装。

---

## 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 17（`sourceCompatibility` / `jvmTarget` 均为 17） |
| Android SDK | compileSdk 34，minSdk 26（Android 8.0+），targetSdk 34 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 2.0.20 |
| Gradle | 8.9（由仓库内置 Wrapper 自动下载，无需手动安装） |

仓库已内置 `gradlew` / `gradlew.bat` 与 `gradle/wrapper/`，首次构建会自动拉取 Gradle 8.9。

依赖（见 `app/build.gradle.kts`）：`androidx.core:core-ktx:1.13.1`、
`androidx.recyclerview:recyclerview:1.3.2`、`androidx.work:work-runtime-ktx:2.9.1`、
`com.squareup.okhttp3:okhttp:4.12.0`。UI 用传统 View + RecyclerView，未引入 Compose；
JSON 用 Android 平台内置 `org.json`，未引入 Gson/Moshi。

---

## 设置环境变量

Windows PowerShell：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # 指向你的 JDK 17
$env:ANDROID_HOME = "C:\Users\<你>\AppData\Local\Android\Sdk"     # 指向你的 Android SDK
```

macOS / Linux：

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
```

---

## 两个 flavor

| flavor | applicationId | 用途 | 默认值来源 | 产物 APK |
|---|---|---|---|---|
| **open** | `com.notifyme.android` | 发布版 | 全部为空（APK 内零密钥） | `notifyme-open.apk` |
| **beta** | `com.notifyme.android.test` | 内部测试版，与主包共存同机 | 本机 `secrets.local.properties` 注入 | `notifyme-test.apk` |

- **open（发布版）**：所有 `DEFAULT_*` 字段注入空串，保证发布包不含任何服务器地址与密钥。未配置即完全离线。
- **beta（测试版）**：`applicationIdSuffix = ".test"`、`versionNameSuffix = "-test"`，桌面名为「微信分析助手·测试」，两个图标一眼可辨。默认值从本机 `secrets.local.properties` 读取。

> 命名说明：AGP 禁止 flavor 名以 `test` 开头，故内部 flavor 名为 `beta`，但产物 APK 与对外口径仍叫 `test`。

---

## 构建

```powershell
# 发布版（无需任何密钥）
.\gradlew.bat assembleOpenDebug

# 测试版（如需预置默认值，先配置 secrets.local.properties，见下节）
.\gradlew.bat assembleBetaDebug

# 两个一起构建
.\gradlew.bat assembleOpenDebug assembleBetaDebug
```

产物路径：

```
app/build/outputs/apk/open/debug/notifyme-open.apk
app/build/outputs/apk/beta/debug/notifyme-test.apk
```

---

## 测试版密钥注入（仅 beta flavor）

beta flavor 的默认值来自仓库根目录的 `secrets.local.properties`（已被 `.gitignore` 忽略，**绝不入库**）。

```powershell
copy secrets.local.properties.example secrets.local.properties
# 编辑 secrets.local.properties，填入你自己的地址与密钥
```

可注入的键（见 `secrets.local.properties.example`）：

| 键 | 注入到的 BuildConfig 字段 | 说明 |
|---|---|---|
| `GLM_BASE_URL` | `DEFAULT_ANALYSIS_URL` | OpenAI 兼容分析接口地址 |
| `GLM_API_KEY` | `DEFAULT_ANALYSIS_KEY` | 分析接口密钥 |
| `GLM_MODEL` | `DEFAULT_ANALYSIS_MODEL` | 模型名 |
| `JEV_URL` | `DEFAULT_LAYA_URL` | System One / JEV 评分接口（可选） |
| `JEV_API_KEY` | `DEFAULT_LAYA_KEY` | JEV 密钥（可选） |
| `MONITOR_URL` | `DEFAULT_SERVER_URL` | 配套 Flask 服务端地址 |
| `MONITOR_TOKEN` | `DEFAULT_MONITOR_TOKEN` | 服务端鉴权令牌 |

open flavor 一律注入空串，不读取此文件。

---

## 安装到设备

```powershell
adb install -r app\build\outputs\apk\open\debug\notifyme-open.apk
```

首次进入按引导授予以下权限：

1. **通知使用权**（设置 → 通知访问权限）— 必需，用于捕获微信通知
2. （可选）**无障碍服务** — 启用微信界面直读（在微信聊天界面翻页读取消息）
3. （可选）**日历权限** — 用于写入待办事件
4. （可选）**忽略电池优化** — 保持后台监听与同步任务可靠调度

---

## 发布版签名（可选）

仓库默认构建 debug 包。如需发布签名版，在 `app/build.gradle.kts` 的 `buildTypes.release`
中配置 `signingConfig`，并将密钥库信息放入本机 `secrets.local.properties`（切勿入库），
然后执行 `.\gradlew.bat assembleOpenRelease`。

---

## 目录结构

```
.
├── app/                        # Android 应用模块
│   ├── build.gradle.kts        # flavor 配置（open/beta）、密钥注入、依赖
│   └── src/main/
│       ├── AndroidManifest.xml # 服务/权限/Activity 声明
│       ├── res/                # 布局、图标、主题、服务配置 XML
│       └── java/com/notifyme/android/   # Kotlin 源码（41 个文件）
├── server/                     # 可选配套 Flask 服务端（见 server/README.md）
├── docs/
│   ├── BUILD.md                # 本文件
│   └── architecture/           # 架构文档与 9 张图源
├── gradle/wrapper/             # Gradle Wrapper（8.9）
├── gradlew / gradlew.bat       # Wrapper 启动脚本
├── PRIVACY.md                  # 隐私说明
├── THIRD-PARTY.md              # 第三方组件与许可证清单
├── LICENSE                     # MIT
└── secrets.local.properties.example
```

**Kotlin 源码按职责分组：**

| 职责 | 文件 |
|---|---|
| 通知采集 | `WeChatNotificationListener`、`WeChatA11yExtractService`、`A11yExtractStore` |
| 本地存储 | `MessageStore`、`AnalysisStore`、`AdvisorStore`、`ReminderStore`、`PromptStore`、`WatchlistStore`、`PendingQueue` |
| 首页与会话 | `MainActivity`、`ConversationActivity`、`FullHeightRecyclerView` |
| AI 分析 | `AnalysisConfig`、`AnalysisWorker`、`AnalysisScheduler`、`AnalysisCase`、`AdvisorWorker`、`AdvisorScheduler`、`LocalLlmEngine`、`ForkPrefilter` |
| 本地模型 | `LocalModelStore`、`ModelDownloadWorker`、`ModelListActivity` |
| 结果与提醒 | `AnalysisListActivity`、`ReminderListActivity`、`ReminderReceiver`、`CalendarHelper`、`AlarmHelper` |
| 关注与提示词 | `WatchlistActivity`、`PromptEditActivity` |
| 服务器同步 | `SyncConfig`、`SyncWorker`、`SyncScheduler`、`HistorySync` |
| 应用骨架 | `MainApplication`、`OnboardingActivity`、`ConsoleActivity`、`BootReceiver`、`KeepAliveService`、`MessageReplier` |
