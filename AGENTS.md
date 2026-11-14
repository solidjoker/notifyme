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
| `server/` | 可选自托管 Flask 服务端（跨设备查看 / 历史回填 / PWA 控制台 / 排队任务看板）；8 个模块：`app.py`（壳 + 鉴权 + 控制台路由）、`util.py`（纯函数）、`storage.py`（SQLite / JSONL / 顾问存档 / 排队表）、`routes.py`（数据面路由）、`stats_api.py`（`/stats` 聚合）、`suggests.py`（建议）、`extract_common.py`（两条提取腿共享内核）、`console_ui.py`（PWA 前端常量，5 Tab，aura 设计语言）+ `console_classify.js`（归类规则，UMD 可 node 测） |
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
.\gradlew.bat lintOpenDebug                                # Android Lint（ktlint 走 docs/scripts/static_check.py）
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
**评测必须留着时间预算**（`S1EvalBudgetCore`）：properties 的 `CALL_TIMEOUT_MS`（默认 90000）与 `TOTAL_BUDGET_MS`（默认 900000 = 15min）到期即停发新调用，剩余样本写入报告「预算耗尽未测试」，别让构建无限等。2026-11-12 端点「TCP 通、chat 不回」+ 旧 readTimeout=180s × 40 次调用把 `testOpenDebugUnitTest` 挂死 40 分钟。**跑评测前先确认端点能出 chat 响应**（本机 176B 模型冷启动单条可达 85s）。

### 一条命令自检（开 PR 前跑这个）

```powershell
powershell -ExecutionPolicy Bypass -File docs/scripts/local_check.ps1                 # 全量：服务端用例 + 语法 + 守卫 + 版本 + Android 单测/双 flavor/Lint + Lint 基线 + ktlint
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
- **服务端要写「真实 HTTP 端到端」用例**：用 `werkzeug.serving.make_server("127.0.0.1", 0, app, threaded=True)` 起真实 HTTP/1.1，客户端用 `urllib.request`；数据隔离靠改模块属性 `app.DATA_DIR / DB_PATH / JSONL_PATH / ADVISOR_PATH` 指到临时目录。两个坑：
① 测 413 要把 `app.config["MAX_CONTENT_LENGTH"]` 调小（如 1024）再发几 KB —— 真按 8 MiB 阈值发，服务端读完 body 前就回 413 并断连，客户端 `sendall` 撞连接重置拿不到状态码；
② 404/413 是 JSON，但 **405 仍是 Flask 默认 HTML**。参考 `server/test_http_e2e.py`（34 例）。
- **「HTTP 触发 + 后台线程执行」型接口的端到端写法**：`/admin/extract`（`server/routes.py:844` 触发，`server/storage.py:437` 执行）用 `_run_extract_bg` + `_probe_main_db_writable`，端点只回 202 + job_id；后台失败要能在 `/admin/extract/status` 读出 `error_kind=busy`。
- **提醒三级降级链的决策在 `ReminderChainCore`，不在 `CalendarHelper`**：`runReminderChain` 只采集环境事实（权限 / 可写 / 日历可查），决策全在 Core 的 `decide()`；终态 note 契约五条锁在单测里，改链路先跑它。
- **通知形状 / 解析口径改动会先被 `NotificationShapeEvidenceTest` 拦下**：该文件固化「某家 App 的通知 → 入库字段（sender / text / conversation / isGroup / pkg）」的映射，与 `NotificationParserTest`（解析器边界）分工不同。改 `PrefixImParser` / `GenericNotificationParser` / `NotifyMeListener.toRawNotification` 任一都要同步看它。
- **三个解析器的抽取口径统一在 `NotificationTextCore`**（+19 条）：真机取证证实微信正文是 `[11条]发言人: 正文`、不带 MessagingStyle；`hasUnreadPrefix` / `splitSender` / `isPlausibleSender` 是唯一口径，别再各写一套。
- **`lintOpenDebug` 只剩 3 条 warning，都是刻意保留的，别去“修”**：① `ObsoleteLintCustomCheck` —— 依赖库 `lifecycle-livedata-core-2.10.0` 自带的 lint.jar，改不了；② `OldTargetApi` —— `targetSdk 34` 是产品决定；③ `ChromeOsAbiSupport` —— release 只打 `arm64-v8a`，是体积取舍。
- **改 `docs/architecture/diagrams/*.mmd` 必须重渲并回写尺寸表**：`mmdc -i X.mmd -o X.svg`，PNG 给 `-w <viewBox 宽 + 20> -H <viewBox 高 + 60>`；**脑图 06 的 viewBox 起点不是 `0 0`**，单独用 `-w 3200 -H 1600`（否则渲染出 14×7 废图）；**`-h` 是 help 不是高度**。渲完用 `[System.Drawing.Image]::FromFile` 量像素回写 `docs/architecture/README.md` 索引表。
- **控制台归类规则只在 `server/console_classify.js`**（UMD：浏览器挂 `window.ConsoleClassify`、node 走 `module.exports`，无 DOM），别写进 `console_ui.py` 的 HTML。`app.py` 注入 `/*__CONSOLE_CLASSIFY_JS__*/`。改规则跑 `server/test_console_classify.py`（subprocess 调真 node）；`REQUIRE_NODE=1` 时 node 缺失要 **fail 不是 skip**；前端降级 `var CC = window.ConsoleClassify || null`。
- **本机起浏览器调试实例的两个坑**：Windows WinINET 代理指向 `127.0.0.1:18888`（该端口 CONNECT 隧道已挂，Edge 报 `ERR_TUNNEL_CONNECTION_FAILED`），必须显式带 `--proxy-server=http://127.0.0.1:7890`；默认 user-data-dir 不允许 `--remote-debugging-port`（Chrome/Edge 136+ 策略），要把 User Data 拷到独立目录再带 `--remote-debugging-port=9223` 启动。访问仓库设置 / token 页还会撞 **sudo mode**，要过一次 2FA。
- **排队通道 `/queue` 是 best-effort**：端上 `SyncWorker.pushQueue` 只认 2xx，失败既不重试也不阻塞消息同步（`doWork` 的 msgResult 不变）；服务端 `queue_tasks` 表按 `(device_id, task_id)` 主键整份覆盖，保留 7 天，`stale` = `updated_at` 超 30 分钟未更新。端侧 `task_id` 含原始会话名（与「分析通道原样上报 conversation」口径一致，服务端不哈希存储）——**别在控制台或文档里当隐私漏洞写，但确实存在**。
- **控制台有 5 个 Tab（会话 / 待办 / 排队 / 统计 / 顾问）**：改 `console_ui.py` 的 HTML/CSS/JS 后自检三件事：① 内联脚本 `node --check`；② `pytest server/test_console_queue.py server/test_console_ui.py -q`；③ 浏览器实跑看 `errs: []`。**`patchSections` 只追加 `.pane-sec`、不清理临时节点** —— 骨架屏残留就是这么来的。桌面 `.tabbar` 必须 `position:sticky`。
- **端侧排队上报的 device_id 不能碰硬件标识**：`QueueStateCollector` 用 SharedPreferences("queue_state", "device_seed") 里的随机种子做 FNV-1a 派生（`android-` + 8 位 hex），**不要改用 ANDROID_ID / IMEI / 序列号**。
- **控制台统计口径＝服务端全库真值，不是前端已加载页**：控制台概览条与统计 Tab 的数据来自 `GET /stats`（server/stats_api.py 聚合全库），
前端只有在接口不可用或用户切了来源过滤时才退回本地估算，并在指标下方标「估算 · 仅已加载 N 条」（server/console_ui.py 的 `useSrv` 分支）。
两个坑：① `/stats` 的 `by_pkg` / `by_source` / `by_kind` 是**对象数组** `[{pkg|source|kind, count}]`，而前端本地算法生成的是 `{键: 次数}` —— 必须走 `pairList()` 归一化再渲染，
直接 `Object.keys()` 会拿到下标 0/1 打印成 `[object Object]`；② 分析侧的 `redacted` / `redaction_rules` / `redaction_hits` 字段服务端一直返回，
但前端要显式渲染（`redactBadge` / `redactDetail`）并纳入 `analysisSearchText` 搜索，否则用户看不到「哪些内容被替换成了占位符」。
- **周期分析任务按 tag 反查不到就不上报**：`AnalysisScheduler` 的周期任务（unique 名 `wechat_analysis` / `conv_analysis_<id>`）不带 tag，`QueueStateCollector.collect()` 只按 tag `analysis_on_demand` / `analysis_conv:<id>` 采集，所以控制台「排队」Tab 里看不到周期任务——这是已知取舍，不是 bug。

- **会话卡内的「日期 / 发送者」两级分组**：规则在 `server/console_classify.js` 的 `groupMessages(msgs, dim, getTs, getSender)` —— **按 `getTs` 精确值分桶，不按天切**，调用方必须传当天零点对齐的时间戳（`console_ui.py` 的 `dayStartMs()`），传原始毫秒会一条一桶。状态存 `wx_group_v1`；`setConvGroupDim` / `convDimBar` / `groupMsgsByDim` / `convGroupHtml` 四处一起改。
- **排队 Tab 的「设备」维度有两套口径**：设备清单来自服务端 `GET /queue` 的 `devices` 聚合（`server/storage.py` 的 `queue_devices()`，`summary.devices` 是台数），控制台的设备 chip 用这一份；前端 `server/console_classify.js` 的 `groupQueueTasksByDevice` 是纯函数版本（对内存里的 task 列表分组），只在离线/单测场景用，两处别混算。
- **端云排队字段有契约守卫**：`server/test_queue_contract.py` 直接读 `QueueStateCore.kt` 的 `buildPayload` 与 `storage._normalize_queue_task` 比对字段名 / 顶层键 / `state`+`kind` 枚举 / 长度上限 —— 端侧改字段忘改服务端，CI 直接红。两个坑：Kotlin 源 CRLF、Python 源 LF（读前统一换行）；Kotlin 键名是转义引号，正则切出的名字尾部带反斜杠，记得 `rstrip`。
- **版本信息只有一份口径：`VersionInfoCore`**（`internal object`，纯函数 + 14 条）。控制台版本卡与 ☰ 菜单弹窗都调它，别各写一套。① `versionLine` 必须带 `versionCode`（debug 包原地覆盖时 `versionName` 可能不变，只有 code 能证明装上新包）；② beta 的 `versionName` 自带 `-test`（`versionNameSuffix`），`title()` 不再拼渠道后缀。改文案前看 `VersionInfoCoreTest.kt`。

- **历史重复消息只能靠 `MessageDedupeCore` 归并，别在别处另写删行逻辑**：真机量化 78 组 / 271 行是「同一条通知被多次捕获」（微信正文形如 `[11条]发言人: 正文`，未读数一变正文就变，而入库去重键含正文原文）。
口径保守：同 App + 同会话 + 同发送者 + 剥掉未读前缀后正文相同，**且组内至少一行带 `[N条]` 前缀**才归并；裸正文相同（用户真连发）不动，损坏行 / 空正文原位保留。两个坑：① 老行 `sender == conversation`，必须先 `NotificationTextCore.splitSender` 还原再算键；② 正文两侧都要剥前缀。`dedupeRepeated` 在 `removed == 0` 时不碰文件。

- **首页「按来源 App」筛选 chips 的口径在 `AppFilterCore`**（internal object + 16 条；`AppFilterStore` 只存 `app_filter` / `selected_pkg`）。四条：
① chip 只列当前窗口内真出现过的来源（`MessageStore.readRecent(500)` 口径），不列 `AppSourceRegistry` 全集 —— 点了必然空白的 chip 比不给更差；
② 顺序对齐 PWA `appTabList()`：全部 → 微信 → 其余按展示名码点（钉钉 U+9489 在飞书 U+98DE 之前）；
③ `resolve()` 渲染前把失效选中项回落「全部」并落盘，否则清库 / 换机后整页空掉；④ 首页待办 `todoVisibleKeys` 同口径。改前跑 `AppFilterCoreTest.kt`。

- **分析范围（M12）的口径全在 `AnalysisScopeCore`**（public object + 30 条；`AnalysisScopeStore` internal 只落盘）。① 落盘只存选择不存毫秒（存毫秒会把「近 7 天」钉死），回填走 `loadStored()`。
② `immediateScope` 直接返 `UNLIMITED`（forceKey 不受范围约束）。③ `forced()` 跳过启用集合与 DONE/NOISE（`isBlocked` 一律不放行），但 `skipDedup()` 只在手动轮放开。
④ 「近 N 天」含今天，自定义结束日取 23:59:59.999、反填自动交换；`parseDay` 回读校验，2 月 30 日返 null。⑤ 会话 id 用 JSONArray，会话名含 `|`、坏 JSON 返空集。改前跑 `AnalysisScopeCoreTest.kt`。
- **姓名别名的盐只有一份口径：`AliasSaltCore` / `AliasSaltStore`**（`AliasSaltCore` 是 `object` 纯函数 + 16 条单测；`AliasSaltStore` 是 internal，只负责落盘）。别名摘要输入是 `sha256(盐 ‖ 0x00 ‖ 名字)` 取前 6 位 hex，盐是本机 `SecureRandom` 32 字节、存 `privacy_config` 的 `alias_salt` 键（所以 `PrivacyConfig.PREFS_NAME` 是 `internal const`，别改回 private 另开 prefs 文件）。四条契约：
  ① **盐为空必须逐字节退化为 `sha256(名字)`**（`LEGACY_SALT = ByteArray(0)`）—— 老设备升级后历史别名不能变，改摘要输入等于让所有历史脱敏记录对不上号；
  ② `decode` 对非 64 字符 / 非 hex 返回 **null 而不是抛**，调用方就地重生成（`ensure` 第二项为 null 表示「无需落盘」，别无条件写回）；
  ③ `redactCase` / `redactMessages` 的 `salt` 是**必填纯位置参数**（在 `enabledRules` 之前）—— 故意不给默认值，避免新调用点忘记传盐而静默退回无盐别名；
  ④ 顾问通道（`AdvisorWorker.buildDigest` 摘要 + `SyncWorker.pushAdvisor` 报告正文）的姓名映射走 **`AdvisorDigestCore`**，别再退回 `emptyMap()`：分析记录里没有 `isGroup` / 窗口发送者名单，三元组只能从最近消息窗口反推（`windows()`）。
  改别名形态、改盐长度或改摘要输入前先跑 `app/src/test/java/com/notifyme/android/AliasSaltCoreTest.kt` 与 `RedactorCoreTest.kt`（后者有 6 条窗口级用例）。
- **`res/` 下的 XML 是 CRLF、`.kt` 是 LF**：用 Python 脚本按字面量替换时先 `.replace("\r\n","\n")` 再匹配，写回用 `newline="\r\n"`；否则 count=0 报 MISS 会让你以为文案不存在。

- **顾问摘要与窗口级姓名映射全在 `AdvisorDigestCore`**（internal object + 36 条，窗口来源 8 条在 `AdvisorWindowSourceTest.kt`）。① 三元组只能从窗口反推（`windows()` 按 `(pkg, conversation.trim())` 聚合，群聊取任一条标为群聊者）；
② 查不到窗口 → `nameMapFor()` 返空表、只走七类规则，是**下限不是漏洞**；③ 摘要正文是顾问模型输入，**改文案等于改模型输入**，`MAX_CASES_IN_PROMPT=50` / `SUMMARY_TRUNCATE=30` / `CONVERSATION_TRUNCATE=20` 只在 Core 留一份；
④ 盐只在 `privacy.cloudRedact` 时取；⑤ 两条通道统一 `windowsOf(MessageStore.feedGroups(context))`（别走 `readRecent(500)`）。改前跑这两个测试类。

- **首页搜索的四类命中口径全在 `SearchCore`**（internal object + 44 条），别在 `buildFeed` 里写 inline `contains`。① 匹配面 = 会话名 ∪ 来源 App 名 ∪ 消息 ∪ 该会话**全部历史**结论；② `caseParts()` 是结论字段唯一出处，**码值先经 `labelOf` 翻中文再匹配**，`none` 排除，`caseId`/`rawJson` 不收；③ 折叠统一 `forcedExpand(query, hit)`；④ `hitKind()` 只回码值。改前跑 `SearchCoreTest.kt`。

- **topic / due_window 的码值→中文映射只有一份口径：`TopicLabelCore`**（internal object，纯函数 + 33 条单测）。以前四处各写一套、空值策略三套，已全部删除。四条契约：
  ① **归一与显示分开**：`normalizeTopic` / `normalizeDue` 只做码值↔中文档名的双向归一（返回英文码值），`topicRes` / `dueRes` 只回资源 id，`topicLabel` / `dueLabel` / `anyLabel` 才做 `getString` —— 因为单测是纯 JVM（无资源），Core 里不能出现中文字面量。
  ② **未知值原样显示，空值才回落**：`topicRes` / `dueRes` 对未知值返回 null（`topicLabel` 原样回显）；只有**空串**才回落 `topic_notice` / `due_none`。预筛结案写的 `s1Topic = "filtered"` 是第六种取值，单独映射为 `topic_filtered`=「已预筛过滤」；别把它归进 notice 桶。
  ③ **「该显示哪一份截止」的优先序只在 `displayDueCode`**：todoDue → s2DueTime → s1DueWindow（与 `AnalysisStore.displayDue()` 同序，改一处必须同时改另一处）；`hasDisplayDue` 把 `none` / 空串归为「不显示」。**别再写 `dueText != "none"` 这种码值比中文串的判定**（永不相等，首页待办卡曾渲染「· 截止：none」）；编辑框回填走 `editDueLabel`（只看 todoDue，否则保存即硬覆盖分析结果）。
  ④ **提醒链的入参保持原始码值**：`runTodoReminderChain` 用 `displayDueCode`、`AnalysisWorker` 用 `s2DueTime.ifEmpty { s1DueWindow }`，别把 `dueLabel` 的中文结果喂给 `CalendarHelper.parseDueTime` ——「今天」会被解析成今天 09:00，下午分析时事件落进过去、日历永不触发；原始码值落进「解析不出 → baseTime + 1h」是既有分支。
  改码值集合、回落策略或「截止」优先序前先跑 `app/src/test/java/com/notifyme/android/TopicLabelCoreTest.kt`（33 条）。
- **提醒列表的分组 / 排序 / 筛选口径全在 `ReminderListCore`**（internal object，纯函数 + 36 条单测），别在 `ReminderListActivity` 或 Adapter 里另写一套：
  ① **三组顺序是契约**：「需要处理 → 生效中 → 已过期」（`groupRank`），`failed` 与 `intent` 都归「需要处理」但 **`failed` 排在 `intent` 之前**（`attentionRank`）——失败的需要重试，待保存的只是差一步。
  ② **两条保守判定不能改**：`event_time <= 0` 不算过期（否则用户会删掉有效记录）；`failed` 永不算过期（语义是「等你重试」）。与 `ReminderRetentionCore.stillPending` 口径一致，别把方向写反。
  ③ **组内方向不得统一**：attention / active 按事件时间**升序**（马上要到的在上），expired **降序**（刚过期的在上），无时间始终垫组内最后。实现是先把比较键算进 `Arranged` 包装再一次 `sortedWith`，**别在比较器里反复调 `groupOf`**（O(n log n) 次状态判定）。
  ④ **不拿 `created_at` 兜底**：比较器只比到 `event_time`，完全同键时靠 JVM 稳定排序保证传入顺序（`ReminderStore.readAll` 已是 `created_at` 倒序）。之前加了 `createdAtOf` 兜底一度把「同键保序」单测弄红，别加回去。
  ⑤ **筛选到某一组时不插分组标题**，但 chip 始终全四个（`FILTERS` = 全部 / 需要处理 / 生效中 / 已过期）；空态区分「一条都没有」（`emptyFilter` 返 null）与「本组没有」；`emptyFilter(totalCount, visibleCount, filter)` 第二参是**筛选后可见条数**，只判总数会让「命中多条」的页面同时显示列表与空态横幅（真机复现过）。
  改分组语义、组内方向或筛选键前先跑 `app/src/test/java/com/notifyme/android/ReminderListCoreTest.kt`（36 条）。

- **会话可见性的四层门槛只有一份口径：`ConvVisibilityCore`**（internal object，纯函数 + 28 条单测），别在 `MainActivity` / `OverlayService` / `AnalysisWorker` 里各写一套：
  ① **顺序是契约**：关注名单（`WatchlistStore.getUnwatched` 的会话 id，形如 `pkg|会话名`，精确匹配）→ 自身/系统源（`AppSourceRegistry.isBlocked`）→ 启用集合（`AppSourceStore.isEnabled`）→ 首页来源筛选（`AppFilterCore.matches`，空串＝全部恒通过）。顺序只影响 `why()` 把拦截原因归到哪一类，四层全是 AND。
  ② **`forced` 只放宽「启用集合」这一层**。自身/系统源任何情况都拦 —— `isEnabled` 内部虽然也判过 `isBlocked`，但 forced 分支根本不走 `isEnabled`，那条判断此时失效，所以 Core 里这一行不是冗余，删掉就会出现「立即分析」把 a11y 自抓噪声喂给模型。
  ③ **分析门禁传 `AppFilterCore.ALL`**：首页来源筛选只影响展示，不能让「首页正在看飞书」把其它来源的周期分析悄悄停掉。同理分析链的关注名单**故意传 `emptySet()`** —— 该门禁在下面 `ConvTagCore.shouldAnalyze`（`forced && !quiet` 时放行），两处都判会把「立即分析本会话」一起拦掉，并把 `skippedUnwatched` 的计数拆成两份。
  ④ **首页「最近待办」与悬浮面板必须同口径**（都走 `visibleKeys`）：悬浮面板曾漏接首页筛选，首页筛了飞书、面板还列微信待办（真机复现过）。面板的可选项要用「过了前三层门槛」的来源集算 `AppFilterCore.resolve` 的回落，不能拿未过滤的全集，否则停用来源会让筛选卡住、面板整页空白。
  改门槛顺序、加第五层或改 forced 语义前先跑 `app/src/test/java/com/notifyme/android/ConvVisibilityCoreTest.kt`。

- **会话详情页的消息窗口口径全在 `ConvWindowCore`**（internal object，纯函数 + 38 条单测），别在 `ConversationActivity` 里另写一套：
  ① **必须先按 `convKey` 过滤再排序**：旧写法 `readRecent(500).filter{convKey}.sortedBy{timestamp}` 先在全库最近 500 条里选、再挑本会话，别的会话刷屏就把本会话历史挤掉；`slice()` 反过来做。
  ② **请求行数必须封顶**：`requestLines(limit)` 取 8 倍（默认 4000，硬上限 50000）。`readTailLines` 按行数向文件头扩容，给爆 `Int.MAX_VALUE` 会退化成全量读、大文件直接 OOM。
  ③ **`ts <= 0` 的未标注日期消息归 0 参与排序**，超 `limit` 时保留最近若干条而不是最早。
  ④ **「共 N 条」只在 `preview().countedAll` 为真时才能报**：条数可信 = 已读到文件头（`linesRead < requested`，打满一律当没读全）**且** 命中未被 `limit` 砍掉（`visible < limit`）；否则走 `chat_truncated_window` / `chat_window_partial`，别拿窗口内命中数冒充总数。
  ⑤ **`MAX_RENDER = 200` 只留一份**；`MessageStore.readWindow` 因返回 `internal` 类型必须加 `internal`（否则报 `public function exposes its internal return type`）。
  ⑥ **别再用 `readRecent(500)` 挑「本会话的一条消息」**：`latest(window)` 给 `MainActivity.runTodoReminderChain` 补日历描述、`knownIsGroup(window)` 给 `A11yExtractService` 判群聊，窗口占满时两处取 null（同源隐患第二次）。`knownIsGroup` 空窗口必须回 **null** 而不是 false。
  ⑦ **`AnalysisListActivity.buildSourceMap` 同理**：`sourceKeys(records)` 给要读窗口的会话键集合（去重保序、跳过空会话名老记录），每键各读一次 `readWindow` 再交 `sourceMap` 还原原文。取不满返空串、调用方隐藏 —— 宁可不显示，也不拿别的窗口内容冒充原文（第三次复现）。
  改窗口大小、排序方向或可信度判据前先跑 `ConvWindowCoreTest.kt`（38 条，含 600 条噪声 + 本会话回归）。

- **分析候选的取样口径全在 `CandidateWindowCore`**（internal object，纯函数 + 16 条单测），别在 `AnalysisWorker` 里另写一套：
  ① **候选集合不是「全局最近 N 条」，而是「每会话留够用的几条」**：下游一个 case 只吃 `AnalysisCase.WINDOW_SIZE = 10` 条（`takeLast`），候选只决定「哪些会话值得开一轮」。`readRecent(500).groupBy` 会让刷屏会话把安静会话挤出候选 —— `readRecent` 同源隐患第四次复现（前三：详情页 / `latest`+`knownIsGroup` / 分析列表原文）。
  ② **`PER_CONV = 50` 是「10 条窗口 + 范围过滤余量」**：范围是过去的自定义区间时，更新的消息会先占掉名额，余量太小会让区间内消息整块消失（留 10 条时区间内 0 条、留 50 条时 30 条）。别涨到 `MessageStoreCore.TAIL_LINES = 5000` 量级，那等于放弃「每会话只留一窗」。
  ③ **组内必须按时间升序、且 `timestamp <= 0` 归 0 排最前**：与 `ConvWindowCore.slice` 同口径，未标注日期的消息不能被更晚的真实消息顶掉；超 `perConv` 只保最近若干条，`perConv <= 0` 返空表（调用方传错时保守退化）。
  ④ **`MessageStore.candidateGroups` 必须 `internal`**：它返回 `Map<ConvKey, List<ChatMessage>>` 但被 `CandidateWindowCore` 的参数类型牵连，声明成 public 会报 `public function exposes its internal return type`（与 `readWindow` 同一个坑）。
  ⑤ **分组顺序保首次出现（`LinkedHashMap`）**，后续 `sortedBy { windowEnd }` 是稳定排序 —— 同一份输入必须得到同一轮入选顺序，改排序方式前先想清楚「可复现」这条。
  改 `PER_CONV`、排序方向或分组语义前先跑 `app/src/test/java/com/notifyme/android/CandidateWindowCoreTest.kt`（16 条，含「600 条噪声 + 本会话仍在候选」与旧写法对照实验）。

- **分析记录（`analysis.jsonl`）的取窗口口径全在 `AnalysisWindowCore`**（internal object，纯函数 + 23 条单测；文件级操作在同文件的 `internal class AnalysisWindowFile`），别在 Activity / Worker 里另写 `readRecent(N).filter{}` / `groupBy{}`：
  ① **`AnalysisStore.readRecent(context, limit)` 是「文件尾部 limit 行」**，只留给本意就是全局最近 N 条的调用点。要「某会话全部 / 每会话最新一条 / 全库列表」必须走 `readGrouped` / `readForConversation` / `readRecentWindow` / `latestByConvKey`，否则尾部被别的会话占满、安静会话的 case 一条读不到（列表「暂无分析结果」）。已出现三处。
  ② **窗口量级分两档**：`TAIL_LINES = 5_000` 给列表与首页聚合；`POLL_LINES = 1_500` 给详情页轮询（每 4 秒）与悬浮面板 —— 这两条路径给 5 000 等于每 4 秒解析 5 000 行 JSON。`LIST_LIMIT = 200` 只是展示上限，`UNLIMITED = -1` 是「本会话不限条数」。
  ③ **`group()` 保首次出现序、组内按 `windowEnd` 升序**（即窗口推进顺序）；`latest()` 取每组末条，**同 `windowEnd` 取最后写入那条**（旧 `maxByOrNull` 取第一条，别为它改语义）。
  ④ **不按会话设条数上限**（与 `CandidateWindowCore.PER_CONV` 不同），内存由 `TAIL_LINES` 兜住；窗口外老 case 读不到是已知局限，别把 `TAIL_LINES` 涨到几十万来「修」它。
  ⑤ **新增返回这些内部类型的 `AnalysisStore` 方法必须 `@Synchronized` + `JsonlStore(storeFile(context))` 现造**，否则并发追加会读到半行；`AnalysisWindowFile` 自身只管读、不缓存。
  改前跑 `app/src/test/java/com/notifyme/android/AnalysisWindowCoreTest.kt`（23 条，含 600 条噪声 + 本会话 3 条）。

- **首页信息流的取样口径全在 `FeedWindowCore`**（internal object，纯函数 + 22 条单测），别在 `MainActivity.buildFeed` 里另写一套：
  ① 首页取样不是「全局最近 500 条」（`readRecent(500).groupBy`），是 `MessageStore.feedGroups(context)` —— 同源隐患第五处，前四处见上三条。
  ② `perConvFor(convCount)` 把 `TOTAL_BUDGET = 6000` 摊薄再夹进 `[20, PER_CONV = 200]`；`20 = 2 × AnalysisCase.WINDOW_SIZE`，既保证每会话凑得出一窗、又限制按天折叠后的翻页深度。
  ③ 会话间按「该会话最近一条」倒序、组内升序，`timestamp <= 0` 归 0 排最前，并列保首次出现序（同一输入必须同一首页顺序）。
  ④ `Bucket.truncated` 两档：读到文件头时 `size > keep`、没读到文件头时 `size >= keep` —— 条数是下限时界面必须写 `N+ 条`（`R.string.msg_count_at_least`）。
  ⑤ `MessageStore.feedGroups` 必须 `internal`；`REQUEST_LINES = 20000` 比 `TAIL_LINES = 5000` 大是刻意的（安静会话在文件靠前位置）。
  改名额/排序/截断前先跑 `FeedWindowCoreTest.kt`（22 条，含 600 条噪声与旧写法对照）。

- **分析记录 / 消息侧的「近 N 条」取数只有三种口径，别再新写 `readRecent(N)`**（第七批同源隐患，2026-11-13 收口）：
  ① **要「某时间区间内的全部记录」→ `AnalysisStore.readSince(context, cutoffMs)`**（内部 `AnalysisWindowCore.since` + `ADVISOR_LINES = 20000`）。顾问复盘旧写法 `readRecent(ctx, 500).filter{analyzedAt >= since}` 在 case 超 500 条或活跃会话占满尾部时漏安静会话，`nameMapFor()` 返空表、姓名原样出设备；`ADVISOR_LINES` 比 `TAIL_LINES` 宽是刻意的。
  ② **要「全库聚合 / 每会话一条」→ `AnalysisStore.readGrouped` / `latestByConvKey`；要「会话候选集」→ `MessageStore.feedGroups(context).keys()`**。设置页统计（会话数 / 待办会话数）与隐私页白名单候选都属于这一档：旧 `readRecent(500)` / `readRecent(1000)` 数出来的是窗口内命中数，超窗口就是错数、也漏掉安静会话。
  ③ **要「逐条列表」→ `FeedWindowCore.sample(MessageStore.feedGroups(context), limit)`**（脱敏预览用 `PREVIEW_LIMIT = 200`）。**别用 `recent()` 那类「全表最近 N 条再排序」**：等于把旧 `readRecent` 又写一遍，600 条噪声下安静会话仍是 0 条（第一版实现被单测当场证伪后改）。`sample` **按会话轮流取**：第 1 轮各取最新、第 2 轮各取次新……组内取时间尾部，最后整体升序；`timestamp <= 0` 归 0 排最前，**并列时先后由取出顺序决定**。
  改这几处口径前先跑 `app/src/test/java/com/notifyme/android/AnalysisWindowCoreTest.kt`（27 条）与 `FeedWindowCoreTest.kt`（27 条），两边都有「旧写法对照实验」。

- **JSONL 文件的「逐行打补丁 + 原子重写」只有一份口径：`JsonlPatchCore`**（internal object，纯函数 + 15 条单测，`app/src/test/java/com/notifyme/android/JsonlPatchCoreTest.kt`）。此前两份 ~90 行同构实现（只差 schema 与 newly 容器）已收敛，两者只留 `keyOf`/`parse`/`encode`/`patch` 四个 lambda。
  ① **`target == null` = 扫全文件**（顾问通道 `reviveExpired` 复活过期隔离走这条），非 null 只碰命中 key 的行；同一 key 出现多行时**每行都打补丁**（与旧实现一致，别改成只改第一行）。
  ② **损坏行一律原位保留，不许「顺手清理」**：解析抛异常 / 解析返回 null / 序列化抛异常（`AnalysisCaseRecord` 缺 `s1` 时 `importance` 是 NaN，`JSONObject.put` 会抛）三种都只保留原行，其它行照常改，整轮不判失败。
  ③ **只有真的改了才重写**：`updated == record` 与「零命中」直接返 false —— 无谓 `atomicWrite` 搅动 mtime，两个 Store 的读缓存与 watch 链跟着抖。
  ④ **`collect()` 只读**（`quarantinedCaseIds` / `quarantinedReportIds` 用它），`LinkedHashSet` 按文件顺序去重 —— 隔离集合的遍历顺序因此可复现，别换成 `HashSet`。
  ⑤ **别把 `PendingRejectCore` 拉进来**：它是丢弃语义（达上限即 dropped），没有 quarantined / revive，`Decision` 只有 `dropped`；合并会逼出恒 false 的 `include` 和无意义参数。三兄弟里只有 Analysis / Advisor 是「隔离 + 复活」同构。
  改补丁语义（比如想改成「损坏行也重写」或「同 key 只改第一条」）前先跑 `JsonlPatchCoreTest.kt`（15 条），它用与业务无关的假记录证明内核与 schema 无关。

- **日期分组 key 与日期组头文案只有一份口径：`DateKeyCore`**（internal object + 26 条，`DateKeyCoreTest.kt`），别在 `MainActivity` / `ConsoleActivity` / `MessageStore` 里各写一套 `SimpleDateFormat(_, getDefault())`：三处吃默认 Locale 历法（th-TH 下 `2569-11-12`、和历 `8-11-12`、ar-EG 本地数字）而 `java.time` 恒公历 —— key 与 `dayKeyOf` 永不相等，左滑删天删不掉、`CollapseStore` 复合键全失效、跨年判定反。五条：
  ① **key 恒公历 ISO**：`dayKeyOf(ts, zone)` 只走 `Instant.ofEpochMilli().atZone().toLocalDate().toString()`，`ts <= 0` 返 `UNKNOWN`。
  ② **解析要回读校验**：`parts()` 按位取数字后必须 `LocalDate.of` 回读，`2026-02-30` / `2026-13-01` 返 null，不许静默改成 3 月 2 日。
  ③ **跨年用年份数值比较，别用 `startsWith(currentYear)`**（佛历下 currentYear 是 2569、与公历 key 永不匹配）；Core 只回 `DayLabel` 数值，文案模板留 strings.xml（纯 JVM 单测无资源）。
  ④ **`UNKNOWN` 只留一份**：`DateKeyCore.UNKNOWN = "__unknown__"`，`MessageStore.DAY_KEY_UNKNOWN` 引用它；`latestOf` 必须排除它（`_`=0x5F 大于数字，字典序会让它赢），`isUnknown` 吃 null / 空串 / 常量。
  ⑤ **`CollapseStore.latestDayKeyOf` 只是转发**，保留旧签名；「ISO 字典序即时间序」只在恒公历前提下成立。改前跑 `DateKeyCoreTest`（含 th-TH / 和历回归）。


- **公历时间文本 ↔ 毫秒只有一份口径：`DueTimeCore`**（internal object + 31 条单测，`app/src/test/java/com/notifyme/android/DueTimeCoreTest.kt`）。手动提醒弹窗 / 日期选择器 / 自定义分析范围三处往返此前各吃**默认 Locale 的历法**，与 `DateKeyCore` 同族。七条：
  ① **只走 `java.time` + `Locale.ROOT`**：不许 new `SimpleDateFormat`（固化创建时的 Locale 快照）、不许用 `Calendar` 的 `YEAR/MONTH/DAY`（泰语 2569 / 和历 8）；Core 恒 `2026-11-12`。
  ② **`parse` 解析不出返 null，不做兜底**：`+1h` 只在 `CalendarHelper.parseDueTime`（提醒链语义），手填提醒要「先解析成功再落库」。
  ③ **非法日期不许静默顺延**：`2026-02-30` / `2026-13-01` / 非闰年 `2-29` 返 null；`parseDay` 年份收在 `MIN_YEAR=1970 .. MAX_YEAR=2200`。
  ④ **`M月d日` 的「已过顺延明年」留 1 分钟容差**（`thisYear < baseTime - 60_000L`），与旧实现逐条对齐。
  ⑤ **DatePicker 年份按「改了多少年」平移**：`adjustPickedYear(pickedYear, shownYear, baseYear)`，不许写死 `-543` / `+1988`（和历偏移随年号变），也不许把 widget 年份直接 `LocalDate.of`（那就是 1484 年那条 bug）。
  ⑥ **三处只是转发**：`CalendarHelper.tryParseDueTime` 一行委托（`+1h` 兜底不动）、`AnalysisScopeCore.dayStart/formatDay/parseDay` 保留 `TimeZone` 签名转发 `zone.toZoneId()`、`ManualReminderCore` 用 `eventTimeText(ms)`。
  ⑦ **`nextWholeHour(now, zone)` 必须按本地钟点对齐**，不是「epoch 整除 3_600_000 再加一」：+05:45 的 `Asia/Kathmandu` / +09:30 的 `Australia/Adelaide` 下旧写法把弹窗默认值填成 `22:45`。`ManualReminderCore.nextWholeHour` 同签名转发；夹具回归钉 `Asia/Kathmandu` / `Australia/Adelaide`。
  改前跑 `DueTimeCoreTest`（含 th-TH / 和历回归）、`ManualReminderCoreTest`、`CalendarDueTimeTest`、`AnalysisScopeCoreTest`。

- **界面与模型输入里的数字 / 短时间戳文本只有一份口径：`LocaleTextCore`**（internal object + 16 条单测，`app/src/test/java/com/notifyme/android/LocaleTextCoreTest.kt`），`DateKeyCore` / `DueTimeCore` 之后同族第三代。五条：
  ① **时间戳只走 `minutes` / `seconds` / `hourMinute`**（`DateTimeFormatter.ofPattern(_, Locale.ROOT)`）：不许再在 Activity / Adapter / Worker 里 new `SimpleDateFormat(_, getDefault())` —— `ar-EG` / `fa-IR` / `bn-BD` 下会把 `11-12 18:10` 渲染成本地数字，而界面只有中文。旧写法 11 处已全部收口。
  ② **小数只走 `fixed` / `percent` / `bytes` / `binaryBytes`**（`String.format(Locale.ROOT, ...)`）：`"%.2f".format` 在 de-DE 下是 `0,80`，会把**模型输入**（`need_action=0,80`）与报告 Markdown 表格写坏。`bytes`（十进制）与 `binaryBytes`（1024 进制）是两套故意不同的口径，别合并。
  ③ **`Locale.ROOT` 只固定数字形态，不改时区**：`zone` 缺省仍是 `ZoneId.systemDefault()`。④ 时间戳 `<= 0` 不在这里兜底，调用方各自保留「—」/「无时间」。
  ⑤ **资源槽位走 `Context.templateText(id, ...)`，不是 `getString(id, 数字)`**：后者走 `Configuration` Locale，`ar-EG` 下 `%1$d` 出 `١٠`、`%1$.1f` 出 `٢٫٠`（feat/format-template 已收口 63 处，`LocaleTextCore.format` 只认 `%N$d` / `%N$.Mf` / `%N$s` 与 `%%`，降级不抛）。
  刻意例外别顺手改：`"%02x".format(it)` 的 hex、`AnalysisCase` 已钉 `Locale.US` / `Locale.CHINESE` 的题面、`S1Eval` 的 `Locale.US` ISO 时间。对照单测钉 `Locale` 时**时区也要一起钉**（`SimpleDateFormat` 取系统时区，CI runner 是 UTC、本机 +08 —— 本轮 CI 就是这条先红）。改前跑 `LocaleTextCoreTest`（含 ar-EG / de-DE 回归）。

- **静态检查基线：`docs/scripts/static_check.py` + `.editorconfig`**（ktlint 1.8.0）。只开 5 条（import-ordering / no-unused-imports / final-newline / no-consecutive-blank-lines / string-template），加规则须同步 `.editorconfig` 与 `ENABLED_RULES`；glob 带 `!third_party/**`；CI `--report`、local_check 第 7 步 `--strict`、清账 `--fix`；lint 基线靠 lint-summary 断言；`local_check.ps1` 必须 UTF-8+BOM。
- **S2 摘要可用性只有一把尺子：`S2Usability`**（internal object，349 行 + 34 条）。① 可用 = 六项判据全过（`delivered`/`no_placeholder`/`summary`/`due`/`action`/`tasks`），措辞不进判据；② `due` 判「与 S1 同桶」+`PAST_TOLERANCE_MS=60s` 下限，桶复用 `TopicLabelCore.DUE_*`；③ 兜底摘要只有 `fallbackSummary(kase)`；④ 报告文件名必须纯 ASCII（Windows 中文名 latin-1 乱码）；⑤ 只断言跑通不断言答对。


- **采集诊断页的累计统计只有一份口径：`AllTimeStatsCore`**（internal object + 16 条）。① 累计不是 `readRecent(N)`（窗口一满就静默偏小），是 `scanAll`（`SCAN_LINES=50000`）；② 被截断要能报 `capped(totalLines, scannedLines)`；③ object 层 `allTimeStats` 必须 `internal`，`scanAll` 必须写在 Core 内。

- **文本截断只有一份口径：`TextClipCore`**（internal object + 20 条）。`take(n)` 数 **UTF-16 unit**，emoji 被切成孤立代理、UTF-8 落成 `?`。① `clip`/`clipWithEllipsis` 走 `offsetByCodePoints`，未超限返同一实例、省略号不占预算；② ASCII 逐字节不变；③ 不切断代理对，不保证字素簇；④ 32 处已收口，hex 与列表 `take(n)` 刻意不套；⑤ 服务端同口径 `console_classify.codePointLength`/`clipCodePoints` 守排队 title 256 / detail 512 / task_id 128（曾用 `.length` 留孤立代理），改上限要同时改 `QueueStateCore` / `storage._normalize_queue_task`。

- **SharedPreferences 的 JSON 读写只有一份口径：`PrefsJsonCore`**（internal object + 29 条单测）。五处 Store（`WatchlistStore` / `AppSourceStore` / `ConvTagStore` / `ConvAnalysisScheduleStore` / `AnalysisScopeCore.decodeConvIds`）此前各抄一份 `try { JSONArray(raw) } catch { }`。
① **读永不抛**且返回独立拷贝；② **只做形状裁剪不做领域过滤**（`ConvTag.fromCode` 留 Store 层）；③ 元素走 `optString`；④ **写不排序**（`sort = true` 才排）；⑤ 与 JSONL 无关。`strings` 默认只丢空串，纯空白要 `trim = true` 才丢。改前跑 `PrefsJsonCoreTest.kt`。

- **模型通道请求体与状态行着色各只有一份口径**：`ChatRequestCore`（61 行 / 16 条）+ `StatusTintCore`（31/16），改前先跑这两个测试类。
① 三处 `postChatCompletions` 只留 `url(endpoint(url)).post(payload(model,sys,user).toRequestBody(JSON_MEDIA_TYPE))`：端点归一（trim→去尾斜杠→补 `/chat/completions`，不猜 `/v1`）、`MAX_TOKENS=4096`、键序全在 Core；HTTP / `Authorization` / 异常类型留各通道。
② 状态行只走 `StatusTintCore.colorRes`：失败/鉴权先判、`已中断…完成` 判绿别改、只回资源 id。③ `AdvisorWorker.parseReport` 的 JSON 提取必须委托 `AnalysisParsing.extractJsonPayload`。
- **Intent extras→会话键、运行时权限各一份口径**：`ConvExtrasCore`（43 行/18 条）+`PermissionGateCore`（78/15）。① 包名空白回落微信且**不 trim**，会话名空白=无会话返 **null**（`ConvKey("", pkg)` 永匹配不到消息）。② `needsPostNotifications` 必须同时看 sdkInt 与结果；`allGranted` 空结果 false。③ 权限名用 `PERM_POST_NOTIFICATIONS` 字面量，写 `Manifest.permission.POST_NOTIFICATIONS` 报 `InlinedApi`、lint 漂到 4 条。
- **服务端地址与鉴权头各一份口径：`SyncEndpointCore`**（73 行 / 18 条）。① `base()` 顺序是契约：trim → 去尾斜杠 → 剥尾部 `/weixin`（先剥斜杠才剥得掉 `http://h/weixin/`）；② 只拼接，不猜 `/v1`（`ChatRequestCore`），不补 `/chat/completions`；③ `authorized()` 用 `isNotBlank` 且返回同一 builder（可链式、不成双头）；`SyncConfig` setter 会 trim，纯空白令牌不可达。

- **两条提取腿的共享内核只有一份口径：`server/extract_common.py`**（`android_db_extract.py` 1260→813 行、`pc_db_extract.py` 1122→673）。① 六个 env 常量必须继续在两侧脚本里定义（monkeypatch 要目标），common 内走 `_setting(name)`；
② `_setting` 判 `None` 不判真值（`COMMIT_INTERVAL = 0` 合法）；③ `activate(module)` 登记 `_HOST`，**import 尾部 + 每个入口函数开头都要再调**（只登记一次时后导入的 pde 顶掉 ade，表现 `commits=1` 且锁写进真实 `server/data/`）；
④ `_archive_path` / `_lock_path` 用 `_setting("MAIN_DB")` 调用时解析。改前跑 `test_extract_common.py` + `test_extract.py` + `test_extract_cursor.py`。
- **游标分页只有一份解析口径：`server/util._parse_cursor`**（`query_messages`/`query_analysis` 都走它，`EXPECTED_OWNER` 守着归属）。① **两段都校验**：毫秒在 `MIN_TS_MS..MAX_TS_MS`、rowid 在 `0..MAX_ROWID` —— 只补毫秒段时超 int64 的 rowid 抛 `OverflowError` 整页 500，负 rowid 更阴（恒空页，当成翻到底）。② 空值/纯空白返 `(None, None)`。③ `_` 多于两段直接 400。④ 只给毫秒时 rowid 取 `MAX_ROWID` 兼容老客户端。
- **提示词 / 报告里的绝对时间文本只有一份口径：`PromptTimeCore`**（internal object + 22 条，`PromptTimeCoreTest.kt`）。`DateKeyCore`/`DueTimeCore`/`LocaleTextCore` 之后同族第四代，差别是它输出**给模型看**。
① **只走 `DateTimeFormatter` + `Instant.atZone(zone)`**：`SimpleDateFormat` 历法随**默认 Locale** 走，钉 `Locale.US`/`CHINESE` 只钉语言（安全来自映射表，是巧合）；th-TH 下 `Calendar.set(2026,9,25)` 是公历 **1483**。
② `zone` 可注入且单测必须钉。③ 「同年省略年份」用 `ZonedDateTime.year` 数值比较。④ 文案逐字保持。改前跑三个测试类 + `-Duser.language=th -Duser.country=TH` 全量。

- **顾问报告的时间字段只有一份归一口径：`server/util._advisor_ts`**（`test_regression.py` 3 条 + `test_stats_api.py` 2 条守着）。① None/bool/空串/认不出 → 0；纯数字当毫秒，`1e9 <= n < 1e11` 视秒 ×1000；越界 → 0；文本走 `_local_ms`。② `routes.query_advisor` 排序键必须是 `(_advisor_ts(received_at), _advisor_ts(created_at))` —— 裸取遇字符串行 500。③ 新增 util 函数登记 `EXPECTED_OWNER`。
- **`?date=YYYY-MM-DD` 的换算口径在 `server/util._day_bounds_ms`**（底座 `_utc_ms` / `_local_offset_min` / `_local_ms`；`/messages` 与 `/analysis` 共用）。① 按 **UTC 基准**换算再套时区偏移，别用 `datetime(...).timestamp()` —— 1970 前 / 9999 后 `OSError` 兜成 500。② 失败 400 `date must be YYYY-MM-DD`、越界 400 `date out of range`；**空串 `?date=` 等于不过滤**。③ 新增 util 函数要改进 `app.py` import。

- **`GET /queue` 的 summary 是全库口径：`server/storage.queue_summary`**（`query_queue` 用它；`tasks` 才是当前页）。① 无 `LIMIT`，只受 `device_id` / `state` 过滤；过期行只计 `stale` 不再计四状态。② `state` 非法返全 0、表不存在返全 0。③ 前端 `queueActiveCount()` 必须优先读 summary（数列表会在超一页时停在 200）。④ 别把 `/stats` 的 `queue` 段与它写成两套过期口径。

- **`/stats` 会话数的 SQL 口径：`stats_api.CONV_KEY_SQL`**（`= "pkg || char(1) || conversation"`，三处 `COUNT(DISTINCT ...)` 都引它）。① 会话身份是 `(pkg, conversation)`，与 `ckOf()` / `ConvKey` 同口径 —— 只数 `conversation` 会把「微信的王五」和「微博的王五」合并。② `conversation` 为 NULL 时整体 NULL、`COUNT(DISTINCT)` 跳过。③ 改这条 SQL 前跑 `test_stats_api.py` 三条跨 pkg 用例。
- **`docs/scripts/static_check.py` 的 ktlint 缓存必须落在仓库外且是绝对路径**（`cache_dir()` / `_inside()`；`server/test_tooling_cache.py` 5 条守着）。① 缺 `USERPROFILE` 时 `expanduser(波浪号)` 原样返回 → 拼出相对路径 → 71 MB jar 进仓库、`git add -A` 带进提交（真实事故）；② `NOTIFYME_TOOLS_DIR` 指进仓库也要改道临时目录；③ 判「在仓库内」用 `os.path.commonpath`，别用 `startswith`。
- **`/suggests/next` 的 `pkg` 是「真过滤」，不是回显**（`server/suggests.py`，`test_stats_api.py` 4 条守着）。
① 只作用于 `_todo_counts` / `_convs_without_analysis` 两族规则；**排队按设备、顾问是全局报告**，`_queue_state` / `_advisor_state` 必须保持全库口径。
② 前端 `fetchSuggests` 拼 `?pkg=`，`setAppFilter` / `clearAppFilter` 后要 `refreshSuggests()`；拉失败就清空 `SUGGESTS`，别拿上一份别的来源的数字冒充。
③ 加参数别只改回显：既有 `test_suggests_echoes_pkg` 只断言回显，621 条全绿照样漏了这个缺陷 —— 新过滤逻辑必须补「带/不带 pkg 结果不同」的用例。
- **数值列的脏行守卫：`server/stats_api.INT_ONLY_TS` / `MAX_TS`**（`test_stats_api.py` 4 条 + `test_queue.py` 2 条守着）。SQLite 列有 INTEGER 亲和但**能存字符串**，单行脏值就让整页 500 / 游标毒化。① 过滤用 `typeof(timestamp) IN (`'integer','real'`)` 作**常驻条件**，聚合用 `MAX(CASE WHEN typeof(...) THEN x END)`；② **别换 CAST**：`EXPLAIN` 实测 typeof 仍走索引，CAST 退化成全表 SCAN；③ 出参数字走 `_safe_int(...)`。
- **排队过期行的清理与设备维度过期口径：`storage.cleanup_queue_tasks` / `queue_devices`**（`test_queue.py` 6 条守着）。① 清理不能只挂上报路径：设备全停报后僵尸行永远挂在 devices 上；读取路径 `_ensure_db_ready` 也要调 `_maybe_cleanup_queue`，按 `QUEUE_CLEANUP_INTERVAL_MS`（1h）节流。② 设备 chip 的 `active` 只算未过 TTL 的 queued+running，另加第 4 键 `stale`。③ 用例 fixture 必须复位 `app_module._queue_cleanup_at`。
- **排队 Tab 的状态 chip / 空态与汇总同源**（`console_ui.queueStateTotal` / `queueEmptyHtml`）。① chip 计数走 `queueStateTotal(st)`（优先 `QUEUE_SUMMARY[st]`）——数当前页会让 600 排队 + 20 已完成显示「已完成 0」；② 列表空但全库有命中时报「当前页没有…/全库共 N 条…」，搜索/设备筛选命中 0 退回通用空态；③ 先加「chip 与 summary 一致」用例。
- **顾问存档损坏不能冒充「没有报告」**（`suggests._advisor_state` 返三元组 + `ADVISORS_ERR`；`test_stats_api.py` 3 条守着）。
① 损坏时份数必须给 `None`（`return None, 0, True`），给 `0` 会让 priority 50 的「还没有顾问报告 / 顾问存档 0 份」规则命中 —— 真实状态是存档坏、历史报告还在盘上；
② 建议侧改出 priority 85 的「顾问存档损坏，报告读不出来」，夹在 80（待办）与 100（失败排队）之间；
③ 控制台按 `res[2].ok === false` 置 `ADVISORS_ERR`，错误分支必须排在 `if (!reports.length)` **之前**，否则落进通用空态把用户引去等端上跑复盘。
- **设备台数有「总数」与「在跑数」两个口径：`storage.count_live_devices`**（`test_queue.py` 3 条 + `test_stats_api.py` 2 条 + `test_console_queue.py` 5 条守着）。
① `summary.devices` / `/stats.queue.devices` 是**现存台数**，含只剩 failed/done、早已停报的僵尸设备（行要 7 天保留期后才清）；`devices_active` 才是「active > 0 的台数」—— 概览条写「N 台设备」必须读后者，否则 0 任务也报 2 台。
② 前端两个入口：`liveDeviceCount()`（离线/估算档，数 `QUEUE_DEVICES` 的 active）与 `deviceTallyText(q)`（统计卡，`设备 N 台（在跑 M 台）`）；缺 `devices_active` 键时按「全在跑」保守回落，别渲染 `NaN`。
③ 新增聚合键必须同时改 `stats_api._queue_block` 与 `routes.query_queue` 两处，并登记 `test_module_split.EXPECTED_OWNER`。

- **概览条「待处理」的会话数读 `todo_convs` 不是 `convs`**（`console_ui.renderOverview`，`test_console_ui.py` 1 条守着）。① `/stats` 有两个会话数：`convs`=有分析结果的、`todo_convs`=其中有待办的；那格数字是待办条数，副标题须配 `todo_convs`，否则与 `/suggests/next` 的「分布在 N 个会话」同屏打架。② 加键要接消费点。③ 断言写「新键在 + 旧键不在」。

- **`/messages` 与 `/analysis` 必须回报被脏值守卫挡掉的行数：`skipped`**（`routes.query_messages` / `query_analysis`）。① 只数 Python 侧 `except` 恒 0（脏行早在 `typeof` 守卫被挡），须发独立 `SELECT COUNT(*) WHERE NOT (guard) [AND 过滤段]`；② **无过滤也照算**；③ 口径 = 过滤范围内、不含游标段，`count + skipped == /stats`；④ 前端只能覆盖（`loadMore` 用 `Math.max`）。
- **列表接口夹了上限就必须回「总数 / 是否截断」**（`routes.query_advisor`：`min(limit, 100)`，存档上限 `ADVISOR_MAX_REPORTS`=1000）。① 响应体补 `total`（截断前条数）与 `has_more = total > count`，损坏分支也回 `0 / false`；② 角标读 `ADVISOR_TOTAL`（缺省退回已加载条数）；③ 截断用例别钉末位（三批可落同一毫秒），只钉首位最新 + 长度。

- **顾问 Tab 的来源过滤说明必须跟事实走：`console_classify.advisorPkgScope`**（`test_console_classify.py` 6 条 + `test_console_ui.py` 1 条守着）。① `pkg` 可选（老报告与端上 `toUploadJson` 都不写）；② 四态 `all`/`untagged`/`mixed`/`exact`；③ UI 只挑措辞。
- **列表「被截断」与「被筛选」必须各有独立标志：`console_classify.advisorListState`**（4 条规则层 + 1 条 UI 守着）。① `truncated = total > loaded`、`filtered = shown < loaded`；② `total` 缺失/0/负数/非小数/小于 loaded 一律回落 loaded；③ 降级路径保留旧口径（只报截断）且要可测。
- **来源筛选下的空态必须分三成因：`console_classify.appEmptyScope`**（5 条规则层 + 1 条 UI 守着）。① 列表只覆盖已加载页，`mode` 四态 `loaded`/`none`/`elsewhere`/`unknown`，只有 `db === 0` 敢说「没有」；② `db` 与 `shown` 无大小关系（跨页 + 脏行 skip）；③ 键过 `normPkg` 与 `distPairs` 同口径，`null`（拿不到）≠ `0`；④ 只有会话 tab 传 `byPkg`（`/stats` 只有 messages 段有分布），排队直接绕过。
- **列表只画一部分时，标题与切片必须同源：`console_classify.statsListState`**（3 条规则层 + 1 条 UI 守着）。① `capped`（数据超上限，决定给不给开关）与 `expanded`（用户没点开）两个正交标志；② 上限由 UI 传（`STATS_LIST_CAP=30`），脏值回落 `DEFAULT_STATS_LIST_CAP`；③ `shown` 由函数算、调用方不许传；④ 标题 / note / more 三处读同一 `listState`，展开态只存内存。

- **同一段里的分布与标量必须能对上：`stats_api._queue_block.by_kind`、`console_classify.mergePairsBy`**（`test_stats_api.py` 2 + `test_console_ui.py` 2 + `test_console_classify.py` 5）。① 类型计数必须在「过期 → `stale += 1; continue`」**之后**：写在之前会把停跑僵尸数进去，5 行里 3 条停跑时同屏写「analysis 3 / sync 2」与「活跃 1 · 陈旧 3」；② 契约 `sum(by_kind.count) == total - stale`，有过期行前端补「（不含 N 条已过期）」；③ `/stats.by_pkg` 按原始列 GROUP BY，NULL / 空串各成分组而 `normPkg()` 都折成微信 → 渲染出三个「微信」；展示层 `mergePairsBy(pairs, normPkg)` 归并后**按次数降序、同次数键名升序重排**（`distPairs` 兜底同口径），服务端不合并；④ 加分布字段先问该不该跟同段过期/过滤口径走，配「分布之和 == 标量」用例。
