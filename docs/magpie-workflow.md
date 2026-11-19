# magpie 维护工作流（v0.2.0）—— 已装进 notifyme

> 这份文档写给 notifyme 的维护者：装了什么、刻意没开什么、平时怎么用。
> magpie 自身的说明在 [ARCHITECTURE.md](ARCHITECTURE.md)、[USAGE.md](USAGE.md)、
> [CODE_STANDARDS.md](CODE_STANDARDS.md)、[COMMUNITY_RULES.md](COMMUNITY_RULES.md)、
> [LOOP_PROMPTS.md](LOOP_PROMPTS.md)。

## 〇、一句话

magpie = 一套可搬走的「AI 维护工作流」骨架：三个循环（守 issue/PR → 守关键词流 → 回头复盘写错题本）
+ 一本错题本（`LESSONS.md`）+ 五道机器门（自检 / 危害 / 验收 / 发布 / 教训晋升）。
它本身**不改 notifyme 的业务代码**，只提供「让 agent 干活之前先过门」的机制。
本仓库用的是 **dry 安全档**：不发版、不回复、不合 PR、不动 CI。

安装记录：magpie-workflow 安装器 **0.2.0**，`POST /api/install` → `ok=true`，
`POST /api/verify` → `ok=true`（自检全过 + 三循环离线演练全过），装于 **2026-11-17 23:54 +08:00**。

## 一、装了什么（文件地图）

| 路径 | 内容 | notifyme 里的处置 |
| --- | --- | --- |
| `loops/` | 三个循环的实现（`loop1_github.py` / `loop2_x.py` / `loop3_review.py` + `common.py` / `pipeline.py` / `run.py` / `sources.py`） | 入库，循环代码 |
| `gates/` | 五道门：`selfcheck.py` / `hazard_check.py` / `acceptance_check.py` / `release_check.py` / `lessons_promote.py` | 入库，门禁代码 |
| `prompts/` | 五个提示词（triage / classify / fix / review / pr_review） | 入库，提示词模板 |
| `tests/` | magpie 自己的单测（`test_gates.py` / `test_loops.py` / `test_release.py`）+ `fixtures/` 离线演练夹具 | 入库；**夹具必须入库**（见 §五） |
| `config/` | `workflow.toml`（唯一配置入口）+ `hazard_rules.json` + `ci_rules.json` + `blocklist.txt` | 入库 |
| `docs/ARCHITECTURE.md` 等 5 篇 | magpie 自带文档 | 入库（与 notifyme 原有 `docs/BUILD.md`、`docs/ROADMAP.md`、`docs/architecture/` 不撞名） |
| `LESSONS.md` | 错题本 | 入库 |
| `.magpie_install.json` | 安装元数据（版本 / 参数 / 装进来的文件清单） | 入库（无密钥，只有环境变量名；会记录安装时的本机目标路径） |
| `.loopstate/` | 运行时状态（日志 / 收件箱 / 验收产物） | **不入库**，已加进 `.gitignore` |
| `.github/workflows/ci.yml`、`hazard.yml` | magpie 自带的两条 CI | **不入 CI**：移到 `docs/magpie-workflow/ci/` 留档 |
| `magpie_workflow-v0.2.0.zip` | 安装包本体 | **不入库**（`.gitignore` 已加），本体放在 `C:\project\` 下 |

## 二、刻意没开的三件事（安全档，逐条说明理由）

### 1. magpie 自带的两条 CI 不进 `.github/workflows/`

notifyme 自己的 CI（`.github/workflows/android.yml` / `release.yml`）是权威口径：
Android 单测 + 双 flavor 构建 + Lint 基线 + pytest + 隐私守卫 + 版本号校验。
magpie 的 `ci.yml` / `hazard.yml` 是另一套（Python 侧自检 + 危害门），直接并进来会让
每个 PR 多两个 job，且两套门互相不理解对方的仓库结构。
**处置**：两条文件移到 `docs/magpie-workflow/ci/` 留档（内容一字未改），
magpie 的自检不检查 workflow 文件是否存在，移走不影响 `/api/verify`。
想启用时自己决定要不要搬回 `.github/workflows/`。

### 2. payload 自带的 `AGENTS.md` 没有覆盖 notifyme 的

notifyme 的 `AGENTS.md`（约 65 KB）是本仓库 agent 的唯一入口，写满了口径契约；
magpie payload 自带的 `AGENTS.md` 只有 1.6 KB，是它自己的通用版。
安装时同名冲突按「跳过」策略处理，**notifyme 的 `AGENTS.md` 原样保留**，
只在末尾加了一条指针（见 §三）。

### 3. 三个会「真的动手」的开关全关

`config/workflow.toml` 里：

- `loop1.release = false`（不打 tag）、`loop1.reply = false`（不在 issue 里回话）、
  `loop1.merge_pr = false`（不合 PR）；
- `loop2.allow_release_permission = false`（读了开放互联网的流，默认不给发版权）；
- `loop2.release = false`、`loop2.reply = false`；
- `loop3.nightly = "02:00-05:00"`（只在深夜窗口干活）。

即：循环可以跑、可以出结论，但**不会**替维护者发版、回复或合并。

## 三、notifyme 的 AGENTS.md 指针

本仓库 `AGENTS.md` 末尾有一条：

> **magpie 维护工作流 v0.2.0 已装进仓库**：三循环 + 错题本 + 五道门在 `loops/` `gates/`，
> 配置只有 `config/workflow.toml`；自带两条 CI 不启用（副本 `docs/magpie-workflow/ci/`），
> 见 `docs/magpie-workflow.md`。

## 四、怎么用（日常命令，都在仓库根跑）

```powershell
# 1. 自检（= CI 跑的那个入口）：编译全仓库 .py + magpie 单测 + 错题本格式 + CI 规则 + 配置占位符
python -m gates.selfcheck

# 2. 离线演练：不联网、不调模型、不改文件，只验证三个循环的管道
python -m loops.run all --once --offline

# 3. dry-run：真读 GitHub，但不提交、不发版、不回复
python -m loops.run all --once --dry-run

# 4. 危害门：看某个 PR 的改动该不该直接放行
python -m gates.hazard_check --base origin/main --head HEAD

# 5. 错题本：谁够格晋升 / 记一条新教训 / 给某条教训加一次 Seen
python -m gates.lessons_promote --check
python -m gates.lessons_promote --add --rule "..." --why "..." --evidence "abc1234, #42"
python -m gates.lessons_promote --seen L-001

# 6. 状态与最近日志
python -m loops.run status
```

## 五、配置只有一份：`config/workflow.toml`

| 键 | notifyme 当前值 | 说明 |
| --- | --- | --- |
| `[repo].product` | `notifyme` | 便宜模型靠它区分「同名不同物」 |
| `[repo].main_branch` | `main` | |
| `[models.cheap]` | `http://127.0.0.1:8080/v1` / `deepseek/deepseek-chat` | 只做分类；key 走环境变量 `NOTIFYME_GATEWAY_KEY`，**不写进文件** |
| `[models.strong]` | `http://127.0.0.1:8080/v1` / `claude/claude-opus-5-5` | 分诊 / 审查 / 写教训 |
| `[agent].command` | `cmd /c dsh.cmd --profile headless {prompt_file}` | 真正改代码的 agent，1800 秒超时 |
| `[loop1].sources` | `github_issue` + `github_pr` | 守本仓库的 issue 与 PR |
| `[loop1].local_run` | `cmd /c gradlew.bat testOpenDebugUnitTest` | 「拉到本地真跑一遍」——PR 一律停在等人确认 |
| `[loop1].release / reply / merge_pr` | 全 `false` | 见 §二.3 |
| `[loop2].keywords` | `["notifyme"]` | X 关键词流；`cheap_gate = true` 必须开 |
| `[loop2].inbox` | `.loopstate/inbox/x.jsonl` | 本地投递口 |
| `[loop3].lookback_hours` | `24` | 复盘窗口 |
| `[gate].hazard_policy` | `config/hazard_rules.json` | 危害门机器版 |
| `[gate].standards` | `docs/CODE_STANDARDS.md` | 错题本晋升的落点 |
| `[release].command` | `git push origin {tag}` | magpie 的发布门；**notifyme 不走这条路**（见 §七） |

危害门当前三条规则（`config/hazard_rules.json`）：

- **H1 `ci_release`**：改 `.github/**`、`dist/**`、`build/**`、`Makefile`、`Dockerfile*` 等 → block（改了等于能改自己怎么被验证）；
- **H5 `dependency_change`**：改 `package.json` / `requirements*.txt` / `pyproject.toml` / `*.whl` / `*.so` / `*.exe` 等 → block（供应链入口）；
- **H6 `pollution`**：改 `config/workflow.toml` / `config/hazard_rules.json` / `config/blocklist.txt` → block（循环不许改自己的开关与门禁）。

另有凭据扫描（`.ssh` / `.aws` / `.pem` / `.env` 等）与「外链白名单 + 推广链接 + 遥测」检查。

## 六、与 notifyme 自己的门的关系（两套并行，不互相替代）

| 门 | 入口 | 管的什么 |
| --- | --- | --- |
| **notifyme 权威自检** | `docs/scripts/local_check.ps1`（七步） | 服务端 pytest + Python 语法 + 隐私守卫 + 版本号 + Android 单测/双 flavor/Lint + Lint 基线 + ktlint |
| **magpie 自检** | `python -m gates.selfcheck` | 全仓库 `.py` 编译（跳过被剪枝的目录）+ magpie 自己的 61 条单测 + 错题本格式 + `config/ci_rules.json` 里启用的规则 + 配置与提示词占位符 |
| **notifyme CI** | `.github/workflows/android.yml` | 与 local_check 同一套口径 |
| **magpie 自带 CI** | `docs/magpie-workflow/ci/{ci.yml,hazard.yml}` | 留档未启用 |

开 PR 前仍以 `local_check.ps1` 打印「全部通过，可以开 PR」为准；
`gates.selfcheck` 是额外一道（61 条单测），两者都要绿。
「CI 规则」那一步要扫全仓库 —— 但**已经剪过枝**：vendored 依赖（`third_party/`）、
构建产物与本地缓存（`build/` `dist/` `tmp/` `.gradle/` `.gradle-home/` `.loopstate/` 等）
与 `.git/` 都不进（`gates/selfcheck.py` 的 `SKIP_DIR_NAMES`）。
本机有 `third_party/` 时整轮约 8 秒，剪枝前是 25 秒。

## 七、已知边界（别踩）

1. **magpie 的发布门不适用于 notifyme**：它要求 `dist/` 下 `installer_count = 2` 个安装包且 `dist/LATEST` 标着当前 Latest，
   notifyme 的产物是 Gradle 出的 APK（`app/build/outputs/apk/...`），发版仍走自己的 `release.yml`（打 `v<version>` 标签触发）。
   `gates.release_check` 在本仓库会记成 NOT RUN，属预期。
2. **`config/ci_rules.json` 现在有 1 条真规则（L-002）**：2026-11-19 把 L-002「测试只许在临时目录里跑」
   补成了真会查的规则（`enabled: true` + `pattern` + `scope`）——同一行里同时出现「真实 home 引用」
   （`Path.home()` / `expanduser("~")` / `$HOME` / `environ["HOME"]` / 字符串里的 `~`）与
   「用户配置目录名」（`.claude` / `.config` / `.aws` / `.ssh` / `.gnupg` / `.kube` / `.docker` / `.gem` / `.npm`）
   即判违规；scope 覆盖 `tests/` / `app/src/test/**` / `server/` / `gates/` / `loops/` / `docs/scripts/`，
   `third_party`（vendored）与 `tmp`（本地草稿）不扫。**新增顶层代码目录要往 scope 里补一条**。
   口径的三个已知边界：引擎**逐行**匹配，拆成两行的写法抓不到；硬编码 `C:\Users\<真实名>` 不在本规则范围
   （那是 AGENTS.md §4.3 的红线）；同一行命中即算，**纯读也报**（从严）。
3. **`LESSONS.md` 里没有待晋升的条目了**：L-001（修之前先原样复现）/ L-002（测试只许在临时目录里跑）
   都在 2026-11-19 满了 Seen=3，进了 `docs/CODE_STANDARDS.md` 的 `promoted-zone`；
   L-002 因为标着 `Machine-checkable: true`，还落了一条 CI 规则（见上一条）。
4. **模型网关与 agent 都是可选项**：没配好也能装、能自检、能离线演练，只是循环不能真的改代码。
   notifyme 侧网关地址指向本机 `127.0.0.1:8080`，安装器给了两条 warning，属预期。
5. **`tests/fixtures/*.jsonl` 必须入库**：仓库的 `*.jsonl` 全局忽略规则会把它挡掉，
   所以 `.gitignore` 里显式加了 `!tests/fixtures/*.jsonl` 例外；新克隆后离线演练才跑得起来。
6. **`selfcheck` 编译仓库内的 `.py`，但跳过被剪枝的目录**：`gates/selfcheck.py` 的 `SKIP_DIR_NAMES`
   里有 `.git` / `.venv` / `__pycache__` / `third_party` / `tmp` / `dist` / `build` / `.loopstate` /
   `.gradle` / `.gradle-home` / `.idea` / `.kotlin` / `.pytest_cache` / `node_modules`。
   这些目录里**没有本仓库的正规源码**（`git ls-files` 里没有任何被跟踪的文件落在这些名字的目录下），
   剪掉只影响速度、不影响覆盖；代价是「第三方代码的语法错误」不再由 selfcheck 报出来
   （第三方代码坏了该由它自己的构建负责）。**新增一个叫 build / dist / tmp 的源码目录时要同步改这个清单。**
   剪枝必须用 `os.walk` 的 in-place 剪枝：`rglob` 即使逐条过滤路径段，也已经把整棵树走了一遍
   （本机 5 万条目 1.5 秒 → 剪枝后 1900 个文件 0.04 秒）。
7. **payload 的 `tests/` 在仓库根**：notifyme 的 pytest 只跑 `server/` 目录级发现（CI 与 local_check 都是），
   根目录 `tests/` 不会被误收进服务端用例。
8. **Windows 中文控制台**：`gates/*` 会打印 ✓ 与 ✗，这两个字符不在 GBK 里，裸跑会在最后一行抛
   `UnicodeEncodeError`。`gates/__init__.py` 里加了 reconfigure shim（标准输出与标准错误改 UTF-8），
   所以 `python -m gates.selfcheck` / `hazard_check` / `acceptance_check` / `release_check` 在中文控制台直接能跑。
9. **错题本的晋升会改写活文件**：`python -m gates.lessons_promote --apply` 会把够格的条目挪进
   `docs/CODE_STANDARDS.md` 并从 `LESSONS.md` 删掉它们，同时给 `Machine-checkable: true` 的条目
   在 `config/ci_rules.json` 落一条**空桩**（`enabled: false` + `pattern: null`）。
   空桩要人补实现（补 `pattern` / `scope` 并把 `enabled` 改成 `true`），否则 selfcheck 只把它列成
   「还是桩」不判红 —— **2026-11-19 才把欠着的 L-002 桩补成真规则**。
   另：`LESSONS.md` 里若已有同 id 的规则，`--apply` 不会覆盖它（`append_ci_stub` 按 id 去重），
   所以补好的 `pattern` 不会被下一次晋升抹掉。

## 八、维护提醒

- 改循环行为 → 改 `loops/`，改门禁 → 改 `gates/`，改判定 → 改 `config/workflow.toml`（唯一配置入口）；
- 改完跑 `python -m gates.selfcheck` + `python -m loops.run all --once --offline`；
- 每次真踩坑 → `python -m gates.lessons_promote --add ...` 记进 `LESSONS.md`（别手抄，脚本会记 Seen 计数）；
- 新教训满 Seen=3 → `python -m gates.lessons_promote --apply` 晋升；标着 `Machine-checkable: true` 的
  还要**补 `config/ci_rules.json` 里那条桩的 `pattern` / `scope` 并把 `enabled` 改成 `true`**，
  否则它永远只是桩（selfcheck 会一直列「还是桩」提醒你）；
- 危害门命中 block 时**停下等维护者**，循环不许自己放行（`config/blocklist.txt` 也只许追加不许删）。
