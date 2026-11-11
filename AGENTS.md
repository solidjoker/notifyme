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
| `server/` | 可选自托管 Flask 服务端（跨设备查看 / 历史回填 / PWA 控制台）；已拆成 4 个模块：`app.py`（Flask 壳 + 鉴权 + 控制台/落地页）、`util.py`（纯函数工具层）、`storage.py`（SQLite / JSONL / 顾问存档）、`routes.py`（数据面路由） |
| `docs/` | `BUILD.md`（构建）、`ROADMAP.md`（里程碑）、`architecture/`（9 张架构图） |
| `third_party/llama.cpp` | M4 端侧推理依赖，CI 里 clone v0.5.0，**不入库** |

技术底座：compileSdk / targetSdk **34**，minSdk **26**，JDK **17**，AGP **8.5.2**，Kotlin **2.2.21**（必须 ≥2.1：litert-api 2.2.0 的元数据是 2.3.0，2.0.x 编译器直接报 incompatible version），Gradle **8.9**（wrapper 自带，无需手装）。

---

## 1. 常用命令

### 本地（Windows PowerShell）

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

.\gradlew.bat testOpenDebugUnitTest                        # 单元测试（app/src/test，纯 JVM，无 Robolectric）
.\gradlew.bat assembleOpenDebug assembleBetaDebug          # 双 flavor 构建
.\gradlew.bat lintOpenDebug                                # Android Lint + ktlint
.\gradlew.bat assembleOpenRelease                           # 发布包（R8 混淆 + 资源压缩）
python -m pytest server/ -v --tb=short                     # 服务端全部用例
python -m compileall -q server                              # 服务端语法检查（目录级，与 CI 一致）
python docs/scripts/privacy_guard.py                        # 隐私守卫自查
python docs/scripts/version_check.py                       # 版本号自查（--expect-tag v0.3.19）
```

### 一条命令自检（开 PR 前跑这个）

```powershell
powershell -ExecutionPolicy Bypass -File docs/scripts/local_check.ps1                 # 全量：服务端用例 + 语法 + 守卫 + 版本 + Android 单测/双 flavor/Lint
powershell -ExecutionPolicy Bypass -File docs/scripts/local_check.ps1 -SkipAndroid    # 只跑服务端侧（改 Python 时用，秒级）
```

Linux / macOS：`bash docs/scripts/local_check.sh`（同样支持 `--skip-android` / `--skip-server`）。
脚本逐项打印退出码与日志目录，任一步失败整体退出码为 1，**别在它说“全部通过”之前开 PR**。
（`local_check.ps1` 以 UTF-8 + BOM 保存，否则 Windows PowerShell 5.1 解析中文会报语法错。）

产物：

- `app/build/outputs/apk/open/debug/notifyme-open.apk`（发布版，零密钥）
- `app/build/outputs/apk/beta/debug/notifyme-test.apk`（内部测试版，读本机 `secrets.local.properties`）
- `app/build/outputs/apk/open/release/notifyme-open.apk`（发布包：arm64-v8a + R8 混淆 + 资源压缩）

ABI 约定：debug/beta 打 `arm64-v8a` + `x86_64`（真机 + 模拟器），**release 只打 `arm64-v8a`**（少带一套 x86_64 原生库，约省 20MB）。
要在 x86_64 模拟器上装发布包：`.\gradlew.bat assembleOpenRelease -PreleaseAbis=arm64-v8a,x86_64`。

### CI（`.github/workflows/android.yml`，ubuntu-24.04 + temurin 17）

```bash
./gradlew --stacktrace testOpenDebugUnitTest assembleOpenDebug assembleBetaDebug
```

CI 额外跑：Android Lint（`lintOpenDebug`，**退出码直接参与判定**，不再被管道吞掉）、Python 语法检查（`compileall server`）、pytest（`server/` 目录级）、**隐私守卫**（`docs/scripts/privacy_guard.py --forbid-local-secrets`，见 §4）、版本号校验（`docs/scripts/version_check.py`）。
CI 还开了：Gradle / pip 依赖缓存、job 级 `timeout-minutes`（构建 60 / 发布 30）、`concurrency`（PR 上重复推送自动取消上一次）。CI 不签名。

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
2. 不得出现私有母仓库旧命名（企微系的 4 个旧标识，`.github/` 自身除外）。**规则本体已收敛到 `docs/scripts/privacy_guard.py`（CI 与本地同源）**，自查命令：
   ```bash
   python docs/scripts/privacy_guard.py                                # 默认：扫已跟踪 + 未跟踪未忽略文件
   python docs/scripts/privacy_guard.py --forbid-local-secrets        # CI 同款；本机有 secrets.local.* 时报错属预期
   ```
   只想快速 grep 时（方括号写法是为了让本文件本身不被守卫命中）：
   ```bash
   git grep -InE '[q]iyeweixin|[w]eixin_android|[W]eixinAndroid|[w]eixin-monitor' -- . ':(exclude).github'
   ```
3. 不得出现真实个人用户目录 `C:\Users\<真实名>`，一律写占位符 `C:\Users\<你>`。
4. 不得提交 APK、密钥库、`files/*.jsonl` 里的真实用户数据、构建产物。
5. **open flavor 的 BuildConfig 默认值必须保持空串**（发布包零密钥，APK 内不含任何服务器地址与密钥）。

---

## 5. 验收清单（开 PR 前逐条过）

- [ ] 复现步骤已写进 PR（若不可复现，写明原因与替代验证方式）
- [ ] `.\gradlew.bat testOpenDebugUnitTest` 绿
- [ ] `.\gradlew.bat assembleOpenDebug assembleBetaDebug` 绿
- [ ] `.\gradlew.bat lintOpenDebug` 无新增 error
- [ ] `python -m pytest server/ -v --tb=short` 绿
- [ ] `python docs/scripts/privacy_guard.py` 绿（本机有 `secrets.local.*` 时用默认模式，别加 `--forbid-local-secrets`）
- [ ] `powershell -ExecutionPolicy Bypass -File docs/scripts/local_check.ps1` 打印「全部通过，可以开 PR」
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
- `lintOpenDebug` 输出里的 `e: ... Module was compiled with an incompatible version of Kotlin` 是 **AGP 8.5.2 自带 lint 内嵌的 Kotlin 编译器（2.0.x）**在解析 2.2.21 stdlib / litert-api 元数据时的噪音，**不影响编译**（单独重跑 `compileOpenDebugKotlin` 是干净的）；看到它别去动 Kotlin 版本。
- **同步水位口径＝「已结算前缀」**：水位只推进到「本轮被接受 ∪ 本轮新被隔离」的连续前缀；
  中间存在「被拒未隔离 / 未脱敏 / 本轮结论不明（207 清单读不出来）」的洞时，
  水位**停在洞前，绝不越过** —— 越过的记录 `analyzedAt <= 水位`，扫描会当已同步跳过，
  **永远补报不了**。改 `SyncWorker` / `AnalysisStore` 的水位逻辑时，
  必须同步看 `SyncWatermarkCore` 的单测。
- **413 单独处置**：服务端单条请求体上限 8 MiB，超限的那一条是「毒丸」，重试无用。
  端上先把批次二分重试，拆到单条仍 413 才单独隔离该条并继续下一批，
  **不触发整轮 retry**（否则一条毒丸会永久卡住它后面的整条队列）。
- **`gh pr merge` / `PUT /pulls/{n}/merge` 会 403 `Resource not accessible by personal access token`**，但 `gh pr create`、写 label、写 issue comment 都通 —— 因为 fine-grained PAT 只有 Issues / Pull requests 写权限，**缺 `Contents: write`**（合并要往 base 分支写合并提交）。修法二选一：给该 PAT 加 `Contents: Read and write`（会一并覆盖 token 选中的其它仓库，范围由维护者定），或走已登录浏览器点合并。
- **浏览器点合并的正确姿势**：合并框本体（`.mergePartialContainer__VUMolp`）里只有提交信息框 + `[data-dd-action-name="pull_requests.merge.confirm_merge"]`；**合并方式选择器不在这个容器里**，在主按钮旁的图标按钮 `[data-dd-action-name="pull_requests.merge.open_method"]` 里，弹层选项文案是 `Create a merge commit` / `Squash and merge` / `Rebase and merge`，选完再点主按钮 `[data-dd-action-name="pull_requests.merge.open"]`。
- **Android 单测里起不了 JDK 自带的 HTTP 服务**：单测编译 classpath = `android.jar` + 显式依赖，Kotlin 走 `-no-jdk`，`com.sun.net.httpserver` 编译期直接报 `Unresolved reference 'sun'`。想在纯 JVM 单测里起本地服务，只能用 `java.net.ServerSocket` 自己写 HTTP/1.1（见 `app/src/test/java/com/notifyme/android/HttpEndToEndFixture.kt`），或引入 okhttp mockwebserver（会多一条外部依赖解析，CI 上要权衡）。
- **`lintOpenDebug` 现在只剩 3 条 warning，都是刻意保留的，别去“修”**：① `ObsoleteLintCustomCheck` —— 来自依赖库 `lifecycle-livedata-core-2.10.0` 自带的 lint.jar，仓库代码改不了；② `OldTargetApi` —— `targetSdk 34` 是产品决定（Play 上架要求 ≥35 时再统一升，别单文件升）；③ `ChromeOsAbiSupport` —— release 只打 `arm64-v8a`（见 §1 ABI 约定），是体积取舍。基线口径：改动前 97 条 issue / 0 error → 现在 **3 条 / 0 error**。
- **改 `docs/architecture/diagrams/*.mmd` 必须重渲并回写尺寸表**：mermaid-cli 11.17.0 的命令是 `mmdc -i X.mmd -o X.svg`，PNG 要给足页面宽度才能拿到原始像素 —— `-w <SVG viewBox 宽 + 20> -H <SVG viewBox 高 + 60>`；**脑图 06 的 viewBox 起点不是 `0 0`**，得单独用 `-w 3200 -H 1600`，否则会渲染出 14×7 的废图。**注意 mmdc 的 `-h` 是 help 不是高度**，高度用 `-H`。渲完用 `[System.Drawing.Image]::FromFile` 量实际像素，回写 `docs/architecture/README.md` 的索引表。
- **本机起浏览器调试实例的两个坑**：Windows WinINET 代理指向 `127.0.0.1:18888`（该端口 CONNECT 隧道已挂，Edge 报 `ERR_TUNNEL_CONNECTION_FAILED`），必须显式带 `--proxy-server=http://127.0.0.1:7890`；默认 user-data-dir 不允许 `--remote-debugging-port`（Chrome/Edge 136+ 策略），要把 User Data 拷到独立目录再带 `--remote-debugging-port=9223` 启动。访问仓库设置 / token 页还会撞 **sudo mode**，要过一次 2FA。
