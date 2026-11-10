# AGENTS.md —— notifyme 的 AI 协作者入口

> 这份文件是 AI agent（含 dsh superagent 唤醒后的执行会话）在本仓库动手前的唯一入口。
> 人类开发者读 [README.md](README.md) 与 [docs/BUILD.md](docs/BUILD.md)。
> 总原则：**先复现，再改码；改完必须跑通双 flavor 构建 + 单测；对外回复一律中文。**

---

## 0. 仓库是什么

notifyme = 微信通知本地智能分析助手（本地优先，通知原文留在自己设备上）。

| 目录 | 内容 |
|---|---|
| `app/` | Android 客户端（Kotlin；传统 View + RecyclerView，**无 Compose**；JSON 用 `org.json`，**无 Gson/Moshi**） |
| `server/` | 可选自托管 Flask 服务端（跨设备查看 / 历史回填 / PWA 控制台） |
| `docs/` | `BUILD.md`（构建）、`ROADMAP.md`（里程碑）、`architecture/`（9 张架构图） |
| `third_party/llama.cpp` | M4 端侧推理依赖，CI 里 clone v0.5.0，**不入库** |

技术底座：compileSdk / targetSdk **34**，minSdk **26**，JDK **17**，AGP **8.5.2**，Kotlin **2.0.20**，Gradle **8.9**（wrapper 自带，无需手装）。

---

## 1. 常用命令

### 本地（Windows PowerShell）

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

.\gradlew.bat testOpenDebugUnitTest                        # 单元测试（app/src/test，纯 JVM，无 Robolectric）
.\gradlew.bat assembleOpenDebug assembleBetaDebug          # 双 flavor 构建
.\gradlew.bat lintOpenDebug                                # Android Lint + ktlint
python -m pytest server/test_weixin.py -v --tb=short        # 服务端测试
python -m py_compile server/app.py server/android_db_extract.py server/pc_db_extract.py server/gen_icons.py
```

产物：

- `app/build/outputs/apk/open/debug/notifyme-open.apk`（发布版，零密钥）
- `app/build/outputs/apk/beta/debug/notifyme-test.apk`（内部测试版，读本机 `secrets.local.properties`）

### CI（`.github/workflows/android.yml`，ubuntu-24.04 + temurin 17）

```bash
./gradlew --stacktrace testOpenDebugUnitTest assembleOpenDebug assembleBetaDebug
```

CI 额外跑：Python 语法检查、pytest、**隐私守卫**（见 §4）。CI 不签名。

### 发布

推送 `v<version>` 标签触发 `.github/workflows/release.yml`：校验 tag 与 `versionName` 一致 → 签名 `assembleOpenRelease` → `apksigner verify` → 生成 `.sha256` → `gh release create`。
需要的 4 个仓库 Secrets：`NOTIFYME_KEYSTORE_BASE64` / `NOTIFYME_KEYSTORE_PASSWORD` / `NOTIFYME_KEY_ALIAS` / `NOTIFYME_KEY_PASSWORD`。

---

## 2. 分诊规则（收到 issue / PR 时按顺序判）

| 情况 | 动作 |
|---|---|
| **能本地复现的 bug** | 改码 + 加单测 + 双 flavor 构建通过 → 开 PR。PR 必写：复现步骤、根因、改动范围、验证命令与实际结果 |
| **需求 / 增强** | 只回评讨论设计要点，**不直接写码**，等维护者确认范围与里程碑归属（对照 `docs/ROADMAP.md`） |
| **文档问题** | 直接改文档开 PR；改完仍要跑 §1 的 lint + 单测，确认没把文档里的命令写错 |
| **依赖真机行为的报告** | 先补齐信息：机型、Android 版本、通知来源 App、是否已授予通知使用权、是否开启了无障碍服务。**无法在模拟器复现就先要信息，别猜** |
| **与本仓库无关 / spam** | 不处理，回评说明后关闭 |
| **涉及密钥 / 原始消息数据 / 用户隐私** | 立刻回评「请勿在 issue 里贴密钥或真实通知内容」，并在后续回复中**不复述敏感内容** |

默认口径：**一条 issue 只回评一次**，先问清关键信息再动手；同一 issue 后续追问合并到同一轮回复。

---

## 3. 提交与版本

- 分支：功能分支 `feat/xxx` / `fix/xxx` / `docs/xxx`；**不要直接推 `main`**。
- 提交信息：`fix(scope): 一句话` / `feat(scope): 一句话` / `docs: 一句话`，scope 用模块名（如 `listener`、`reminder`、`analysis`、`server`）。
- 改 `versionName` 只在维护者明确要求时做；打 tag 前确认 tag 与 `versionName` 一致（release.yml 会校验，不一致直接失败）。
- 一次 PR 只做一件事；大改动先开 issue 对齐。

---

## 4. 硬红线（CI 会拦，PR 里同样适用）

1. 仓库内不得出现 `secrets.local.properties` / `keystore.properties` / `secrets.local.bat`。
2. 不得出现私有母仓库旧命名（企微系的 4 个旧标识，规则本体见 `.github/workflows/android.yml` 的 Guard 步骤，`.github/` 自身除外）。自查命令（方括号写法是为了让本文件本身不被守卫命中）：
   ```bash
   git grep -InE '[q]iyeweixin|[w]eixin_android|[W]eixinAndroid|[w]eixin-monitor' -- . ':(exclude).github'
   ```
3. 不得出现真实个人用户目录 `C:\Users\<真实名>`，一律写占位符 `C:\Users\<你>`。
4. 不得提交 APK、密钥库、`files/*.jsonl` 里的真实用户数据、构建产物。
5. **open flavor 的 BuildConfig 默认值必须保持空串**（发布包零密钥，APK 内不含任何服务器地址与密钥）。

---

## 5. 验收清单（开 PR 前逐条过）

- [ ] 复现步骤已写进 PR（若不可复现，写明原因与替代验证方式）
- [ ] `\.\gradlew.bat testOpenDebugUnitTest` 绿
- [ ] `\.\gradlew.bat assembleOpenDebug assembleBetaDebug` 绿
- [ ] `\.\gradlew.bat lintOpenDebug` 无新增 error
- [ ] 新行为有对应单测（放 `app/src/test/`）
- [ ] 文档同步更新（README / docs/ / PRIVACY.md 按影响面判断）
- [ ] `git status` 里没有密钥、APK、用户数据
- [ ] PR 描述含：复现、根因、改动、验证命令与结果

---

## 6. 已知的坑（别重复踩）

- `run-as com.notifyme.android` 会吞掉错误码：输出里没有明确失败信息时，用 `wc -l` / `cat` 的实际输出判断，**不要把空输出当成功**。
- 模拟器系统时钟可能是 **UTC**：所有 `event_time` 换算前先确认设备时区，再下结论。
- `cmd notification post` 发出的通知 `pkg` 是 `com.android.shell`，会被来源白名单过滤掉，抓不到消息 —— **别误判成通知监听器坏了**。
- 单测是纯 JVM（`unitTests.isReturnDefaultValues = true`），只有不依赖 Android 框架的逻辑才放得进 `app/src/test/`。
- 通知监听 + 无障碍双通道会拿到同一批消息，去重靠 `dedup_key` / 消息指纹，改动去重逻辑时务必补回归用例。
- 提醒链是三级降级：系统日历事件 → 日历插入 Intent → 本地闹钟；改链路时三种终态都要验。
