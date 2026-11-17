# 使用说明

这份文档写给三种人：要把这套东西装进自己项目的、装完之后天天维护它的、以及出问题了来查症状的。

设计思路和取舍在 [ARCHITECTURE.md](ARCHITECTURE.md)，代码规范在 [CODE_STANDARDS.md](CODE_STANDARDS.md)。这份只讲怎么用。

---

## 〇、先确认环境

```sh
python --version     # 要 3.11 或以上（用了标准库 tomllib，不装第三方包）
git --version
```

模型网关和 coding agent 都是可选的——没有它们，这套东西照样能装、能自检、能离线演练，只是不能真的修代码。

---

## 一、装进去

### 方式 A：图形界面（推荐第一次用）

```sh
python install.py          # 或者双击 install.cmd
```

浏览器打开 `http://127.0.0.1:8765`。左边填，右边实时看生成出来的 `config/workflow.toml`，点「开始安装」。

要注意一件事：**从模板仓库里启动时，目标路径默认是空的**。因为当前目录就是模板本身，装进去等于自己覆盖自己，所以它不给你预填。换个目录。

### 方式 B：把安装器拷到目标项目里跑

```sh
cp -r magpie_workflow/installer 你的项目/
cd 你的项目
python -m installer        # 或者双击 installer/install.cmd
```

这一档默认目标就是当前目录。安装器自带 `installer/payload/` 里的模板副本，所以它不依赖原仓库还在不在。**放到哪个项目里运行，就装到哪个项目。**

### 方式 C：不用界面，手工装

```sh
cd 你的项目
cp -r <模板仓库>/{loops,gates,prompts,tests,docs,config} .
cp -r <模板仓库>/.github/workflows/* .github/workflows/    # 没有就 mkdir -p
cp <模板仓库>/{AGENTS.md,LESSONS.md} .
mkdir -p .loopstate/{logs,acceptance,review,inbox,outbox}
```

然后手改 `config/workflow.toml` 里这五处，缺一处就跑不起来：

| 改什么 | 为什么 |
| --- | --- |
| `[repo].product` | 便宜模型靠它区分"同名不同物"。这名字越普通，误判越多 |
| `[models.cheap]` `[models.strong]` | 网关地址、模型名、key 的环境变量名 |
| `[agent].command` | 真正改代码的那个 agent，必须带 `{prompt}` 或 `{prompt_file}` |
| `[loop2].keywords` | X 上真正会提到你的词 |
| `[release].command` | 打 tag 的脚本，占位符 `{tag}` |

手工装漏掉的是 `.gitignore` 里那三行（`.loopstate/`、`__pycache__/`、`*.pyc`），记得自己加，不然运行状态会被提交。

### 装完先验这三件

```sh
python -m gates.selfcheck
python -m loops.run status
python -m loops.run all --once --offline --fixtures --dry-run --ignore-window
```

图形界面装的话，后两项它已经替你跑过了。三条都过，才算装成。

---

## 二、参数怎么填

### 目标目录

必须绝对路径，必须**已经存在**。安装器不替你建目录——建错位置的代价比多点一下大。

已有同名文件时：

- **备份后覆盖**（默认）：留 `<文件名>.bak.<时间戳>`。只有这一种能把覆盖掉的原文件找回来
- **保留旧的，跳过**：目标项目里已有的 `AGENTS.md`、`LESSONS.md` 不会被碰
- **直接覆盖**：不 backup

如果你往一个已有文档的项目里装，建议选「跳过」，然后手工合并——这套文档和你原有的很可能有重叠。

### 产品名

这一项最容易被随手填，但它决定 cheapest 模型那一层的准确度。

便宜模型拿到的提示词是"判断这条文本是不是关于 `<产品名>` 的真实使用反馈"，而 `<产品名>` 是英文单词时，撞车概率极高。原项目遇到的就是这个：magpie 是"喜鹊"，也是英超纽卡斯尔联队的外号，还是常见鸟名。所以它必须先筛一遍，否则贵模型会被球迷和观鸟内容淹没。

名字越独特，预筛压力越小。名字越普通，越不能关掉 `loop2.cheap_gate`。

### 两档模型

分工是固定的，不是给你省钱的花招：

| 档 | 干什么 | 典型选择 |
| --- | --- | --- |
| cheap | 只做「是/不是」分类，不拿主意 | 便宜、快、能扛量 |
| strong | 分诊、审查 PR、写教训 | 贵但判断准 |

`base_url` 写网关地址（`http://.../v1` 这种），key **不写进任何文件**，只填环境变量名。运行时从环境变量读。

如果 `base_url` 是 `127.0.0.1` 或 `localhost`，安装器会提醒你：目标项目不在本机跑的时候，这里连不上。

### agent 命令

真正改代码的是它。两种占位符：

- `{prompt}` — 把渲染好的提示词文本直接塞进命令行。命令太长会被系统截断
- `{prompt_file}` — 塞落盘后的文件路径。**推荐这个**，长提示词不会出问题

```toml
command = ["claude", "-p", "{prompt_file}"]        # Claude Code
command = ["codex", "exec", "{prompt_file}"]       # Codex
command = ["C:\\Program Files\\cc\\cc.exe", "-p", "{prompt_file}"]   # 路径带空格就这样写
```

这个可执行文件**必须在 PATH 里**。不在的话，分诊、卡门、落盘都正常，只有"真改代码"那一步会明确报 `agent 命令找不到`——这是故意写成硬错的，不静默降级。

### 入口

loop1 默认监听 GitHub issue 和 PR。Discord 走 `.loopstate/inbox/discord.jsonl`（外部进程追加，循环只读）。

`local_run` 那一栏对应原文的"拉到本地真跑一遍"。**空着就等于没跑**，PR 会一直停在 ready-to-merge 等人。填你的测试命令，比如 `["npm","test"]`。

loop2 只处理含关键词的推文，且**必须先过便宜模型**。关掉它的唯一理由是"我的产品名足够独特"。

### 那四个开关

| 开关 | 打开之后会发生什么 |
| --- | --- |
| `loop1.release` | 循环自己打 tag 发版 |
| `loop1.reply` | 循环自己在 issue 里回话 |
| `loop1.merge_pr` | 循环自己合 PR |
| `loop2.allow_release_permission` | 读了整个开放互联网的循环拿到发版权 |

前三个开两个以上，安装器会警告。第四个和第一个同时开，会额外警告——**读开放互联网 + 能发版**这个组合，一句夹带的指令就能变成一个正式版本。这是原文没细说、但这套实现刻意留的一道限制。

### 发布三条件

`[release]` 对应原文那三条：一次跑完的绿色测试、安装包一个不少（`installer_count`）、标成 Latest。

没配的东西一律记 `NOT RUN` 并**拒发**，不当没这回事。所以如果你的 `dist` 目录不存在，发布门会一直拦——那是它在告诉你发布链还没接上。

---

## 三、第一次真跑：四档，一档一档来

```sh
# 01 演练：不联网、不调模型、不写文件
python -m loops.run all --once --offline --fixtures --dry-run --ignore-window

# 02 试跑：真的拉 issue，但不写不提交
export MAGPIE_GATEWAY_KEY=...          # 或你配的那个变量名
python -m loops.run loop1 --once --dry-run

# 03 只修不发：真改、真提交，发版和回话仍然关着
python -m loops.run loop1 --once

# 04 全开
# 先把 config/workflow.toml 里 release/reply/merge_pr 改成 true
python -m loops.run loop1 --interval 900
```

每一档该看到什么：

- **01**：loop1 处理掉一条假 issue，loop2 把无关推文丢掉，loop3 说 `nothing_to_review`。日志里每条都标着 `offline`
- **02**：拉到你项目真实的 issue，分诊出结论，但工作区没变(`git status` 干净)
- **03**：真的多了一个提交，提交说明里带 `Acceptance:` 那一整段
- **04**：多了一个 tag，issue 下面多了一条回复

**不要跳档。** 03 档建议至少跑一周——你要先积累"它修对了吗"的证据，再决定要不要把发版权交出去。

---

## 四、日常维护

### 每天

看两样东西：

```sh
python -m loops.run status          # 各循环这一轮干了什么
git log --oneline -20               # 循环产生的提交
```

重点不是"跑了没有"，是"修对没有"。看提交说明里的 `Cause` 是不是真原因，`Red/Green` 里那个 FAIL 是不是真的失败过。

### 每次自己提交前

```sh
python -m gates.acceptance_check --message .git/COMMIT_EDITMSG
```

或者直接 `--commit HEAD` 查上一个。它会拦下这些：`Cause` 写成"修复了一些问题"、`Red/Green` 里没有 FAIL、跑了什么没说清、`Not run` 一节缺失、改到了受保护路径。

### 每周

```sh
python -m gates.lessons_promote --check    # 看哪条教训够格晋升
python -m gates.lessons_promote --apply    # 真晋升
```

晋升是三级台阶，别手抄：

```
LESSONS.md 里 Seen 有 3 个不同日期
  → 挪进 docs/CODE_STANDARDS.md（每次合并逐条对）
    → 还标着 Machine-checkable: true 的，再落一条 config/ci_rules.json（CI 机器查）
```

手抄会漏掉 Seen 计数，也会把同一条记两遍。

### 循环跑飞了

```sh
python -m gates.release_check --tag v1.2.3    # 发布前单独查一遍
```

发现循环判断错了，往 `config/blocklist.txt` 里加一行人工复核标记。loop3 只追加，**只有人能删**。

### 反过来改规矩

`docs/`、`LESSONS.md`、`config/*.json` 是循环自己的规矩。循环不许改它们——验收门里有一条硬检查：动到受保护路径直接红。要改规矩，人来做。

---

## 五、出问题了

**`ModuleNotFoundError: No module named 'loops'`**
不在项目根目录。这套东西按"当前工作目录就是仓库根"设计的，`cd` 到装它的那一层再跑。

**`agent 命令找不到: claude`**
`[agent].command` 里第一个词不在 PATH。Windows 上写全路径，注意反斜杠要么双写要么整个字符串用单引号。或者换成你确实装了的 agent。

**`模型 xxx 没有 key`**
`[models.*].api_key_env` 写的那个变量名在环境里没值。注意填的是**变量名**不是 key 本身。

**发布门一直拒**
先看它说的哪条没过。最常见是 `dist` 目录不存在或安装包数量不够 `installer_count`。这不是 bug，是发布链没接完。

**loop3 每轮都 `nothing_to_review`**
它只审带 `Loop: loop1` / `Loop: loop2` 标记的提交。前两个循环还没真跑过，它当然没东西可审。等 03 档跑出第一个循环提交，它自己就活了。

**危害门拦了我自己的正常改动**
它拦的是改 CI 配置、改发布脚本、新增外发地址、疑似凭据、新依赖、编码块、推广链接。前两类是"停下来等人"而不是"永远不许"——确认没问题就人工放行。

**验收门说 `Cause` 是空话**
`修复了一些问题`、`optimize code`、`misc` 这类词被硬编码成空话。换一句说清"到底哪里错了、为什么错"。

**CI 红了**
`python -m gates.selfcheck` 在本地跑一遍，它和 CI 跑的是同一个。四节：编译导入、单测、文件格式、启用的规则。

---

## 六、升级与卸载

**重装 / 升级**：再跑一次安装器。它读 `.magpie_install.json` 预填上次的参数，文件按你选的冲突策略处理。升级前建议留一个 commit。

**改配置**：直接改目标项目的 `config/workflow.toml`。改完这一份就够，没有别处要同步。

**卸载**：删 `loops/ gates/ prompts/ config/ docs/ .github/workflows/ .loopstate/ .magpie_install.json`，以及 `tests/` 里那三个测试文件。

**别删 `LESSONS.md`。** 那是这套东西唯一会增值的资产——循环本身是可替换的，教训不是。

`AGENTS.md` 也建议留着：它只是一张"做某件事之前先读哪份文档"的索引表，删了对下一轮接手的 agent（或人）不友好。

---

## 七、一句话总结

这套东西跑顺之后，你的主要工作不是写代码，是**读 LESSONS.md 和拒绝需求**。

机器负责把反馈变成提交，人负责决定哪些反馈值得变成提交、哪些教训值得变成规矩。这两件事它替不了你。
