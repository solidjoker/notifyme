# 构建指南（从源码构建 Android APK）

本文面向需要自行构建 notifyme Android 客户端的开发者。
普通用户请直接从 [Releases](https://github.com/solidjoker/notifyme/releases/latest) 下载 `notifyme-open.apk` 安装。

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

1. **通知使用权**（设置 → 通知访问权限）— 必需，用于捕获各 App（微信 / 飞书 / 钉钉…）通知
2. （可选）**无障碍服务** — 启用微信界面直读（在微信聊天界面翻页读取消息）
3. （可选）**日历权限** — 用于写入待办事件
4. （可选）**忽略电池优化** — 保持后台监听与同步任务可靠调度

---

## 发布版签名

`assembleOpenRelease` 默认产出 **未签名** APK（构建不会失败，只会打印一行警告）。
要产出可安装的签名包，需要提供一个密钥库；`app/build.gradle.kts` 的读取优先级为：

**环境变量 > 仓库根目录 `keystore.properties` > 都没有（降级为不签名）**

| 配置项 | `keystore.properties` 键 | 环境变量 |
|---|---|---|
| 密钥库文件 | `storeFile`（绝对路径，或相对仓库根的路径） | `NOTIFYME_KEYSTORE_FILE` |
| 密钥库口令 | `storePassword` | `NOTIFYME_KEYSTORE_PASSWORD` |
| 密钥别名 | `keyAlias` | `NOTIFYME_KEY_ALIAS` |
| 密钥口令 | `keyPassword` | `NOTIFYME_KEY_PASSWORD` |

### 本地签名

```powershell
# 1) 生成密钥库（放到仓库外，例如 ..\notifyme-secrets\）
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
  -keystore ..\notifyme-secrets\notifyme-release.jks `
  -keyalg RSA -keysize 2048 -validity 10000 -alias notifyme `
  -dname "CN=notifyme, O=notifyme, C=CN"

# 2) 填配置（keystore.properties 已被 .gitignore 忽略，绝不入库）
copy keystore.properties.example keystore.properties
# 编辑 keystore.properties，填 storeFile / storePassword / keyAlias / keyPassword
# 用记事本保存也没问题：构建脚本会忽略 UTF-8 BOM，路径分隔符 / 与 \ 都接受

# 3) 构建并校验
.\gradlew.bat assembleOpenRelease
& "$env:ANDROID_HOME\build-tools\34.0.0\apksigner.bat" verify --print-certs `
  app\build\outputs\apk\open\release\notifyme-open.apk
```

> **密钥库务必备份且不要更换**：Android 按签名识别应用，签名变了老用户无法原地升级，
> 只能卸载重装，而卸载会清空应用私有目录里的全部消息与分析数据（JSONL）。

### CI 自动签名与发布

推送 `v*` 标签会触发 [.github/workflows/release.yml](../.github/workflows/release.yml)：
校验标签与 `versionName` 一致 → 用 Secrets 里的密钥库签名 `assembleOpenRelease` →
`apksigner verify` → 生成 `.sha256` → `gh release create` 发布为 latest。

需要的 4 个仓库 Secrets（Settings → Secrets and variables → Actions）：
`NOTIFYME_KEYSTORE_BASE64`（`[Convert]::ToBase64String([IO.File]::ReadAllBytes("notifyme-release.jks"))`）、
`NOTIFYME_KEYSTORE_PASSWORD`、`NOTIFYME_KEY_ALIAS`、`NOTIFYME_KEY_PASSWORD`。

推送 `main` 或开 PR 会触发 [.github/workflows/android.yml](../.github/workflows/android.yml)：
单元测试 + `assembleOpenDebug` / `assembleBetaDebug` + server Python 语法检查 +
隐私守卫（拒绝密钥文件入库、拒绝私有命名与个人用户目录残留），并上传 debug APK 产物。
CI 不签名（debug 包用 Android 默认 debug 密钥）。

---

## 目录结构

```
.
├── .github/
│   ├── workflows/              # android.yml（CI）+ release.yml（打 tag 自动签名发布）
│   ├── ISSUE_TEMPLATE/         # bug / feature 表单 + 文档跳转
│   └── pull_request_template.md
├── app/                        # Android 应用模块
│   ├── build.gradle.kts        # flavor（open/beta）、密钥注入、发布签名、依赖
│   └── src/main/
│       ├── AndroidManifest.xml # 服务/权限/Activity 声明
│       ├── res/                # 布局、图标、主题、服务配置 XML
│       └── java/com/notifyme/android/   # Kotlin 源码（41 个文件）
├── server/                     # 可选配套 Flask 服务端（见 server/README.md）
├── docs/
│   ├── BUILD.md                # 本文件
│   ├── ROADMAP.md              # 里程碑 M0–M8、工作量、风险与决策门
│   └── architecture/           # 架构文档与 9 张图源
├── gradle/wrapper/             # Gradle Wrapper（8.9）
├── gradlew / gradlew.bat       # Wrapper 启动脚本
├── PRIVACY.md                  # 隐私说明
├── THIRD-PARTY.md              # 第三方组件与许可证清单
├── LICENSE                     # MIT
├── keystore.properties.example # 发布签名配置模板（真实文件 gitignore）
└── secrets.local.properties.example  # beta flavor 密钥模板（真实文件 gitignore）
```

**Kotlin 源码按职责分组：**

| 职责 | 文件 |
|---|---|
| 通知采集 | `NotifyMeListener`、`A11yExtractService`、`A11yExtractStore` |
| 应用源管理 | `AppSourceRegistry`、`AppSourceStore`、`SourcesActivity`、`ConvKey` |
| 本地存储 | `MessageStore`、`AnalysisStore`、`AdvisorStore`、`ReminderStore`、`PromptStore`、`WatchlistStore`、`PendingQueue` |
| 首页与会话 | `MainActivity`、`ConversationActivity`、`FullHeightRecyclerView` |
| AI 分析 | `AnalysisConfig`、`AnalysisWorker`、`AnalysisScheduler`、`AnalysisCase`、`AdvisorWorker`、`AdvisorScheduler`、`LocalLlmEngine`、`ForkPrefilter` |
| 本地模型 | `LocalModelStore`、`ModelDownloadWorker`、`ModelListActivity` |
| 结果与提醒 | `AnalysisListActivity`、`ReminderListActivity`、`ReminderReceiver`、`CalendarHelper`、`AlarmHelper` |
| 关注与提示词 | `WatchlistActivity`、`PromptEditActivity` |
| 悬浮通知（M9） | `OverlayService`、`OverlayManager`、`OverlayConfig`、`OverlayActionReceiver` |
| 采集诊断 / 快速回复 | `CaptureDiagnosticsActivity`、`CaptureStats`、`ReplyActionStore`、`MessageReplier` |
| 服务器同步 | `SyncConfig`、`SyncWorker`、`SyncScheduler`、`HistorySync` |
| 应用骨架 | `MainApplication`、`OnboardingActivity`、`ConsoleActivity`、`BootReceiver`、`KeepAliveService`、`MessageReplier` |
