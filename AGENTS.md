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
| `server/` | 可选自托管 Flask 服务端（跨设备查看 / 历史回填 / PWA 控制台 / 排队任务看板）；已拆成 7 个模块：`app.py`（Flask 壳 + 鉴权 + 控制台/落地页）、`util.py`（纯函数工具层）、`storage.py`（SQLite / JSONL / 顾问存档 / 排队任务表）、`routes.py`（数据面路由，含 `/queue`）、`stats_api.py`（`/stats` 全库聚合）、`suggests.py`（`/suggests/next` 下一步建议）、`console_ui.py`（PWA 前端常量，5 个 Tab；视觉走 aura 设计语言：暖米白底 + 发丝描边 + 胶囊控件，颜色只落在两个变量块）+ `console_classify.js`（归类规则单一事实源，UMD 可 node 单测） |
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

S1 判定质量评测（云端 vs 端侧，见 `docs/评测-云端vs端侧S1.md`）：

```powershell
# 配置写 app/build/eval/eval.properties（app/build 被 .gitignore 忽略，端点与 key 不入库）
Remove-Item -Recurse -Force app\build\test-results\testOpenDebugUnitTest -ErrorAction SilentlyContinue
.\gradlew.bat --no-build-cache testOpenDebugUnitTest --tests 'com.notifyme.android.S1EvalHttpTest' --console=plain
```

**必须先删 test-results 再加 `--no-build-cache`**：否则 Gradle 判 UP-TO-DATE，评测根本不执行（改 properties 不算 task input，cleanTest 是 no-op）。
**评测必须留着时间预算**（`S1EvalBudgetCore`）：properties 里 `CALL_TIMEOUT_MS`（默认 90000）与 `TOTAL_BUDGET_MS`（默认 900000 = 15min）到期即停止发起新调用，剩余样本写进报告「预算耗尽未测试」而不是让构建无限等。2026-11-12 就因端点「TCP 通、chat 不回」+ 旧实现的 readTimeout=180s × 40 次调用，把整轮 `testOpenDebugUnitTest` 挂死 40 分钟，只能 jstack 定位。**跑评测前先确认端点真能出 chat 响应**（本机 176B 模型冷启动单条可达 85s）。

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
- **`gh pr merge` / `PUT /pulls/{n}/merge` 会 403 `Resource not accessible by personal access token`**，但 `gh pr create`、写 label、写 issue comment 都通 —— 因为 fine-grained PAT 只有 Issues / Pull requests 写权限，**缺 `Contents: write`**（合并要往 base 分支写合并提交）。修法三选一（按推荐顺序）：
  - **① 用全权限 OAuth token 临时覆盖 `GH_TOKEN`（推荐，零改配置）**：本机 Windows 凭据管理器里通常还存着一个 `git:https://github.com` 条目的 OAuth token（scope = `gist, repo, workflow`），它对合并端点有写合并提交的权限；`gh` 优先读 `GH_TOKEN`。
  - **② 给该 fine-grained PAT 加 `Contents: Read and write`**（会一并覆盖 token 选中的其它仓库，范围由维护者定）。
  - **③ 走已登录浏览器点合并**（姿势见下一条，最麻烦，能不用就不用）。
  - 取 token 并覆盖（**别把 token 打印进日志、提交或 CI**）：
    ```powershell
    $cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
    $env:GH_TOKEN = (($cred | Select-String '^password=' | Select-Object -First 1).ToString() -replace '^password=','')
    gh pr merge 8 --squash --delete-branch
    Remove-Item Env:\GH_TOKEN   # 用完立刻清掉，别留在会话里
    ```
  - 验证 scope：`gh api -i user | Select-String x-oauth-scopes`（fine-grained PAT 这行是空的，别拿它判断细粒度权限）。
- **浏览器点合并的正确姿势**：合并框本体（`.mergePartialContainer__VUMolp`）里只有提交信息框 + `[data-dd-action-name="pull_requests.merge.confirm_merge"]`；**合并方式选择器不在这个容器里**，在主按钮旁的图标按钮 `[data-dd-action-name="pull_requests.merge.open_method"]` 里，弹层选项文案是 `Create a merge commit` / `Squash and merge` / `Rebase and merge`，选完再点主按钮 `[data-dd-action-name="pull_requests.merge.open"]`。
- **Android 单测里起不了 JDK 自带的 HTTP 服务**：单测编译 classpath = `android.jar` + 显式依赖，Kotlin 走 `-no-jdk`，`com.sun.net.httpserver` 编译期直接报 `Unresolved reference 'sun'`。想在纯 JVM 单测里起本地服务，只能用 `java.net.ServerSocket` 自己写 HTTP/1.1（见 `app/src/test/java/com/notifyme/android/HttpEndToEndFixture.kt`），或引入 okhttp mockwebserver（会多一条外部依赖解析，CI 上要权衡）。
- **服务端要写「真实 HTTP 端到端」用例**：用 `werkzeug.serving.make_server("127.0.0.1", 0, app, threaded=True)` 起真实 HTTP/1.1 服务器，客户端用 `urllib.request`（真 TCP）；数据隔离靠改模块属性 `app.DATA_DIR / DB_PATH / JSONL_PATH / ADVISOR_PATH` 指到临时目录（`server/storage.py` 每次请求都动态读这几个变量，改属性就够，不要 import 时拷贝）。两个坑：① 测 413 要把 `app.config["MAX_CONTENT_LENGTH"]` 调小（如 1024）再发几 KB —— 真按 8 MiB 阈值发，服务端会在读完 body 前就回 413 并断连接，客户端在 `sendall` 阶段撞连接重置，拿不到状态码；② 404 / 413 是 JSON，但 **405 目前仍是 Flask 默认 HTML**（没注册 errorhandler），断言别按 JSON 写。参考实现见 `server/test_http_e2e.py`（34 例）。
- **「HTTP 触发 + 后台线程执行」型接口的端到端写法**：`/admin/extract` 是 POST 触发、后台线程干活、状态页看结果的组合（`server/routes.py:844` 触发，`server/storage.py:414` 跑任务）。写端到端用例要三件事：① monkeypatch `app._import_extract_module` 返回带 `run_extract(full=...)` 的假模块，别真去读微信数据库；② **每条用例前后都复位 `app._extract_status["running"]=False` / `["last"]=None`** —— 上一条用例的后台线程没跑完会残留 running=True，让下一条 POST 直接 409 误报；③ 路由在起线程**之前**就置 running=True，所以「第二次 POST 必然 409」不用 sleep；状态页在锁内读 running/last，轮询到 `running==False` 时 `last` 已是最终结果，也不用额外等待。monkeypatch `_probe_main_db_writable` 时签名要带默认值（`lambda timeout=0.5: (...)`），路由一旦显式传 timeout 就不会 TypeError。
- **提醒三级降级链的决策在 `ReminderChainCore`，不在 `CalendarHelper`**：`CalendarHelper.runReminderChain` 只做「采集环境事实（权限 / 可写日历 / 事件是否已存在 / 是否前台 / 能否唤起日历）→ 执行该级别的副作用 → 把 `ReminderChainCore.Outcome` 翻回 `ChainResult`」；该走哪一级由 `ReminderChainCore.calendarAction` / `shouldTryIntent` / `decide` 三个纯函数决定。改降级顺序、加第四级、改终态文案或 `status` 时**只改 Core 并补 `ReminderChainCoreTest.kt`**，别把 if 嵌套写回 CalendarHelper —— 写回去这条链就又进不了纯 JVM 单测。五条终态 note 文案是老记录级别推断（`ReminderRecord.fromJson` 按 note 前缀认 calendar/intent/alarm/failed）的契约，改文案会让历史提醒的徽标全变。
- **通知形状 / 解析口径改动会先被 `NotificationShapeEvidenceTest` 拦下**：该文件（`app/src/test/java/com/notifyme/android/NotificationShapeEvidenceTest.kt`，**17 条**）固化的是「某家 App 的通知长什么样 → 入库字段（sender / text / conversation / isGroup / pkg）怎么落」的映射，与 `NotificationParserTest`（26 条，解析器边界）分工不同。改 `PrefixImParser` / `GenericNotificationParser` / `NotifyMeListener.toRawNotification` 任一处都要同步看它。
- **三个解析器的抽取口径统一在 `NotificationTextCore`，别在 parser 里各写一套**：真机（2026-11-12）取证证实微信通知**不带 MessagingStyle**、正文是 `[11条]发言人: 正文`（半角冒号、冒号后无空格），旧代码把它全判成私聊、sender 写成会话名，而且**未读数一变（去重键含正文原文）就多存一条重复**（真机 1798 行里量化出 78 组重复 / 多出 271 行）。现在 `NotificationTextCore.splitSender(body, conversation)` 负责：剥未读前缀 → 依次试 `：` / `: ` / `:` 切发言人 → 驳回可疑前缀（**任何分隔符下前缀含空白直接否**；半角再加长度 ≥2、非纯数字、正文不以 `//` 开头）→ 前缀等于或包含于会话名就判私聊、否则群聊。改这段只改 Core + `NotificationTextCoreTest`（19 条），四个边界别回退：① 剥完为空要**保留原文**（返回空串等于丢消息）；② 「明天 10:30 开会」不能被切成 sender=明天；③ 会话名形如 `张三_某机构_某班` 时前缀是子串要判私聊；④ `GenericNotificationParser` 只剥前缀、**不猜发言人**（未收录 App 无从校验）。取证结论与待补清单见 `docs/通知形状取证记录.md`。
- **`lintOpenDebug` 现在只剩 3 条 warning，都是刻意保留的，别去“修”**：① `ObsoleteLintCustomCheck` —— 来自依赖库 `lifecycle-livedata-core-2.10.0` 自带的 lint.jar，仓库代码改不了；② `OldTargetApi` —— `targetSdk 34` 是产品决定（Play 上架要求 ≥35 时再统一升，别单文件升）；③ `ChromeOsAbiSupport` —— release 只打 `arm64-v8a`（见 §1 ABI 约定），是体积取舍。基线口径：改动前 97 条 issue / 0 error → 现在 **3 条 / 0 error**。
- **改 `docs/architecture/diagrams/*.mmd` 必须重渲并回写尺寸表**：mermaid-cli 11.17.0 的命令是 `mmdc -i X.mmd -o X.svg`，PNG 要给足页面宽度才能拿到原始像素 —— `-w <SVG viewBox 宽 + 20> -H <SVG viewBox 高 + 60>`；**脑图 06 的 viewBox 起点不是 `0 0`**，得单独用 `-w 3200 -H 1600`，否则会渲染出 14×7 的废图。**注意 mmdc 的 `-h` 是 help 不是高度**，高度用 `-H`。渲完用 `[System.Drawing.Image]::FromFile` 量实际像素，回写 `docs/architecture/README.md` 的索引表。
- **控制台前端要改归类逻辑，先看 `server/console_classify.js`**：控制台的所有归类规则（来源 App / 会话 / 日期 / 发送者 / 重要度 / 建议类型）必须写在这个独立 JS 文件里，不要就地写进 `server/console_ui.py` 的 HTML 字符串。它是 UMD 模块（浏览器挂 `window.ConsoleClassify`，node 走 `module.exports`），无 DOM 依赖、不引第三方库；`server/app.py` 在 import `console_ui.py` 后把它注入 HTML 里的 `/*__CONSOLE_CLASSIFY_JS__*/` 占位符，再交给 `console_page()` 返回。改规则 → 跑 node 单测 `server/test_console_classify.py`（subprocess 调 node 执行真逻辑，不要在 Python 里重写一份）；CI 里 `REQUIRE_NODE=1` 时 node 缺失必须 **fail 而不是 skip**。前端取不到该脚本时必须优雅降级（`var CC = window.ConsoleClassify || null`），别抛异常把整页打挂。
- **本机起浏览器调试实例的两个坑**：Windows WinINET 代理指向 `127.0.0.1:18888`（该端口 CONNECT 隧道已挂，Edge 报 `ERR_TUNNEL_CONNECTION_FAILED`），必须显式带 `--proxy-server=http://127.0.0.1:7890`；默认 user-data-dir 不允许 `--remote-debugging-port`（Chrome/Edge 136+ 策略），要把 User Data 拷到独立目录再带 `--remote-debugging-port=9223` 启动。访问仓库设置 / token 页还会撞 **sudo mode**，要过一次 2FA。
- **排队通道 `/queue` 是 best-effort**：端上 `SyncWorker.pushQueue` 只认 2xx，失败既不重试也不阻塞消息同步（`doWork` 的 msgResult 不变）；服务端 `queue_tasks` 表按 `(device_id, task_id)` 主键整份覆盖，保留 7 天，`stale` = `updated_at` 超 30 分钟未更新。端侧 `task_id` 含原始会话名（与「分析通道原样上报 conversation」口径一致，服务端不哈希存储）——**别在控制台或文档里当隐私漏洞写，但确实存在**。
- **控制台有 5 个 Tab（会话 / 待办 / 排队 / 统计 / 顾问）**：改 `server/console_ui.py` 的 HTML/CSS/JS 后必须自检三件事：① 内联脚本用 `node --check` 过一遍（脚本 `check_console_js.py`）；② `python -m pytest server/test_console_queue.py server/test_console_ui.py -q`；③ 浏览器实跑看 `errs: []`。**局部重绘 `patchSections` 只追加 `.pane-sec`，不清理临时节点** —— 骨架屏残留就是这么来的，改它时要顺手清掉没有 `data-sec` 的子节点。桌面布局里 `.tabbar` 必须是 `position:sticky`（媒体查询里若写成 `order:-1 + position:static` 会把标签栏甩到页面最顶）。
- **端侧排队上报的 device_id 不能碰硬件标识**：`QueueStateCollector` 用 SharedPreferences("queue_state", "device_seed") 里的随机种子做 FNV-1a 派生（`android-` + 8 位 hex），**不要改用 ANDROID_ID / IMEI / 序列号**。
- **控制台统计口径＝服务端全库真值，不是前端已加载页**：控制台概览条与统计 Tab 的数据来自 `GET /stats`（server/stats_api.py 聚合全库），
前端只有在接口不可用或用户切了来源过滤时才退回本地估算，并在指标下方标「估算 · 仅已加载 N 条」（server/console_ui.py 的 `useSrv` 分支）。
两个坑：① `/stats` 的 `by_pkg` / `by_source` / `by_kind` 是**对象数组** `[{pkg|source|kind, count}]`，而前端本地算法生成的是 `{键: 次数}` —— 必须走 `pairList()` 归一化再渲染，
直接 `Object.keys()` 会拿到下标 0/1 打印成 `[object Object]`；② 分析侧的 `redacted` / `redaction_rules` / `redaction_hits` 字段服务端一直返回，
但前端要显式渲染（`redactBadge` / `redactDetail`）并纳入 `analysisSearchText` 搜索，否则用户看不到「哪些内容被替换成了占位符」。
- **周期分析任务按 tag 反查不到就不上报**：`AnalysisScheduler` 的周期任务（unique 名 `wechat_analysis` / `conv_analysis_<id>`）不带 tag，`QueueStateCollector.collect()` 只按 tag `analysis_on_demand` / `analysis_conv:<id>` 采集，所以控制台「排队」Tab 里看不到周期任务——这是已知取舍，不是 bug。

- **会话卡内的「日期 / 发送者」两级分组**：规则在 `server/console_classify.js` 的 `groupMessages(msgs, dim, getTs, getSender)` —— **它按 `getTs` 返回的精确值分桶，不自己按天切**，所以调用方必须传「当天零点对齐」的时间戳（`server/console_ui.py` 的 `dayStartMs()`），直接传原始毫秒会一条消息一个组。维度切换状态存 `localStorage` 键 `wx_group_v1`（`date` / `sender`），前端 `setConvGroupDim` / `convDimBar` / `groupMsgsByDim` / `convGroupHtml` 四处要一起改；折叠仍复用 `#data` 上的 `data-action="toggle"` 事件委托（一级 key = `baseKey|g.key`，子组 key = `gkey|c.key`）。
- **排队 Tab 的「设备」维度有两套口径**：设备清单来自服务端 `GET /queue` 的 `devices` 聚合（`server/storage.py` 的 `queue_devices()`，`summary.devices` 是台数），控制台的设备 chip 用这一份；前端 `server/console_classify.js` 的 `groupQueueTasksByDevice` 是纯函数版本（对内存里的 task 列表分组），只在离线/单测场景用，两处别混算。
- **端云排队字段有契约守卫**：`server/test_queue_contract.py` 直接读 `app/src/main/java/com/notifyme/android/QueueStateCore.kt` 的 `buildPayload` 与 `server/storage.py` 的 `_normalize_queue_task` 比对字段名 / 顶层键 / `state`+`kind` 枚举 / 长度上限 —— 端侧改字段忘改服务端，CI 直接红。写这个守卫时两个坑：Kotlin 源是 CRLF、Python 源是 LF（读文件先统一换行）；Kotlin 里的键名是转义引号，正则切出来的名字尾部会带一个反斜杠，记得 `rstrip`。
- **版本信息只有一份口径：`VersionInfoCore`**（`internal object`，纯函数 + 14 条单测）。控制台「版本信息」卡与主界面 ☰ 菜单「版本信息」弹窗都调它，别各写一套。两条契约写进类注释：① `versionLine` 必须带 `versionCode`（debug 包原地覆盖安装时 `versionName` 可能不变，只有 code 能证明装上新包）；② beta 的 `versionName` 自带 `-test` 后缀（`app/build.gradle.kts` 的 `versionNameSuffix`），所以 `title()` 不再拼渠道后缀。改文案前先看 `app/src/test/java/com/notifyme/android/VersionInfoCoreTest.kt`。

- **历史重复消息只能靠 `MessageDedupeCore` 归并，别在别处另写一套删行逻辑**：2026-11-12 真机量化出 `messages.jsonl` 里 78 组 / 271 行是「同一条通知被多次捕获」（微信正文形如 `[11条]发言人: 正文`，未读计数一变正文就变，而入库去重键含正文原文）。归并口径是保守的：同 App + 同会话 + 同发送者 + 剥掉未读前缀后正文完全相同，**且组内至少一行带 `[N条]` 前缀**才归并 —— 全是裸正文的相同文本（用户真连发两条「好的」）一律不动；损坏行 / 空正文行一律原位保留。两个容易踩的点：① 老行的 `sender == conversation`（旧解析器没切发言人），必须先按 `NotificationTextCore.splitSender` 还原真实发送者再算键，否则老行与新行永远对不上、一条都删不掉；② 正文两侧都要过 `splitSender` 剥前缀（新行的 sender 已切对，但正文里 `李四: ` 前缀还在）。`MessageStore.dedupeRepeated` 在 `removed == 0` 时**不碰文件、不发变更通知**，别改成无条件重写。

- **首页「按来源 App」筛选 chips 的口径在 `AppFilterCore`，别在 MainActivity 里各写一套**（`internal object`，纯函数 + 16 条单测，`AppFilterStore` 只负责单值持久化 `app_filter` / `selected_pkg`）。四条契约：① chip 只列**当前窗口内确实出现过**的来源（`MessageStore.readRecent(500)` 口径），不列 `AppSourceRegistry` 全集 —— 给一个点了必然空白的 chip 比不给更差（PWA 一次拉全量所以没这个约束）；② 顺序对齐 PWA `appTabList()`：全部（空串）→ 微信 → 其余按展示名码点升序，注意中文码点「钉钉」U+9489 排在「飞书」U+98DE 之前，别按字母直觉排；③ `resolve()` 在渲染前把失效选中项回落「全部」并在 `applyFeed` 落盘同步，否则清库 / 停用 / 换机后列表整体空掉且用户找不到能点的 chip；④ 首页待办 `todoVisibleKeys` 必须与会话同口径过滤（只筛会话不筛待办，会出现「筛了飞书但待办里还有微信条目」）。改候选集合、排序或回落策略前先跑 `app/src/test/java/com/notifyme/android/AppFilterCoreTest.kt`。

- **分析范围（M12）的口径全在 `AnalysisScopeCore`，别在 Worker / 设置页各写一套**（public object，纯函数 + 30 条单测；`AnalysisScopeStore` 仍是 internal，只负责落盘）。五条契约：① **落盘只存选择，不存算出来的毫秒**（period + 自定义起止 + `convIds`）—— 存毫秒会把「近 7 天」钉死在保存那天，`AnalysisScopeStore.load()` 每次按当下重算 range；设置页回填日期框必须用 `loadStored()` 拿原始选择，否则「自定义」框会被填成算出来的区间。② **forceKey（单会话「立即分析」）不受范围约束**：`immediateScope(hasForceKey, picked)` 在 forceKey 时直接返回 `UNLIMITED` —— 单会话点名要的是整窗，不该被设置页存的「今天」砍半。③ **点名会话 = 显式**：`forced()` 让它们跳过 `AppSourceStore` 启用集合与 `ConvTagCore` 的 DONE/NOISE 拦截（`AppSourceRegistry.isBlocked` 的自身/系统源任何情况都不放行）；但 **`skipDedup()` 只在手动轮放开 caseId 去重** —— 周期任务若也跳，窗口没变也会每 15 分钟重算一遍，纯烧 token。④ **「近 N 天」含今天**（from = 今天零点 − (N−1) 天），自定义结束日取 23:59:59.999、两端都可缺省、反填自动交换；`parseDay` 做回读校验，2 月 30 日返回 null 而不是静默改成 3 月 2 日。⑤ **会话 id 用 JSONArray 序列化**（`encodeConvIds` / `decodeConvIds`）—— 会话名含 `|` 与逗号，逗号拼接会被切坏；坏 JSON 返回空集而不是抛异常。改候选集合、跳过去重条件或空结果文案前先跑 `app/src/test/java/com/notifyme/android/AnalysisScopeCoreTest.kt`。
- **姓名别名的盐只有一份口径：`AliasSaltCore` / `AliasSaltStore`**（`AliasSaltCore` 是 `object` 纯函数 + 16 条单测；`AliasSaltStore` 是 internal，只负责落盘）。别名摘要输入是 `sha256(盐 ‖ 0x00 ‖ 名字)` 取前 6 位 hex，盐是本机 `SecureRandom` 32 字节、存 `privacy_config` 的 `alias_salt` 键（所以 `PrivacyConfig.PREFS_NAME` 是 `internal const`，别改回 private 另开 prefs 文件）。四条契约：
  ① **盐为空必须逐字节退化为 `sha256(名字)`**（`LEGACY_SALT = ByteArray(0)`）—— 老设备升级后历史别名不能变，改摘要输入等于让所有历史脱敏记录对不上号；
  ② `decode` 对非 64 字符 / 非 hex 返回 **null 而不是抛**，调用方就地重生成（`ensure` 第二项为 null 表示「无需落盘」，别无条件写回）；
  ③ `redactCase` / `redactMessages` 的 `salt` 是**必填纯位置参数**（在 `enabledRules` 之前）—— 故意不给默认值，避免新调用点忘记传盐而静默退回无盐别名；
  ④ 顾问通道（`AdvisorWorker.buildDigest` 摘要 + `SyncWorker.pushAdvisor` 报告正文）的姓名映射走 **`AdvisorDigestCore`**，别再退回 `emptyMap()`：分析记录里没有 `isGroup` / 窗口发送者名单，三元组只能从最近消息窗口反推（`windows()`）。
  改别名形态、改盐长度或改摘要输入前先跑 `app/src/test/java/com/notifyme/android/AliasSaltCoreTest.kt` 与 `RedactorCoreTest.kt`（后者有 6 条窗口级用例）。
- **`res/` 下的 XML 是 CRLF、`.kt` 是 LF**：用 Python 脚本按字面量替换时先 `.replace("\r\n","\n")` 再匹配，写回用 `newline="\r\n"`；否则 count=0 报 MISS 会让你以为文案不存在。

- **顾问通道的摘要口径与窗口级姓名映射全在 `AdvisorDigestCore`**（internal object，纯函数 + 36 条单测）。五条契约：① 三元组**只能从消息窗口反推**（`windows()` 按 `(pkg, conversation.trim())` 聚合，群聊取「任一条标为群聊」）—— 别给 `AnalysisCaseRecord` 加 `isGroup` / `senders` 字段，那是存量数据补不回来的 schema 变更；② 窗口里查不到该会话 → `nameMapFor()` 返回空表、只走七类规则，这是**下限不是漏洞**（PRIVACY.md 已写明）；③ 记录侧会话名带首尾空白时，别名必须用**窗口里 trim 过的写法**算摘要（同实体同别名），否则跨 case 关联会断；④ 摘要正文（首行「最近 N 天共 X 个分析 case：」/ `预筛过滤(prob=)` 与 `need_action=/importance=/topic=` 两支 / `已深分析 摘要=`+30 字+` 任务数=` / 超限「（其余 N 个较早 case 略）」）是顾问模型输入的分布，**改文案等于改模型输入**，`MAX_CASES_IN_PROMPT=50` / `SUMMARY_TRUNCATE=30` / `CONVERSATION_TRUNCATE=20` 三个常量只在 Core 里留一份；⑤ 盐只在 `privacy.cloudRedact` 时才取（`alias_salt` 的「首次真正需要脱敏才生成」语义），`salt == null` 表示本次不做任何替换。改口径前先跑 `app/src/test/java/com/notifyme/android/AdvisorDigestCoreTest.kt`。
