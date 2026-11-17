# LOOP_PROMPTS.md — 三个循环的提示词与启动方式

提示词全文放在 `prompts/`，是仓库里的文件，不是藏在某个平台的配置里——因为循环之间靠仓库传话，规矩必须是可读、可 diff、可 review 的。

| 文件 | 谁用 | 干什么 |
| --- | --- | --- |
| `prompts/classify.md` | **便宜模型** | 开放入口去噪。判断这条文本是不是关于本产品的反馈 |
| `prompts/triage.md` | 贵模型 | 判断值不值得做，给标签和复现步骤 |
| `prompts/fix.md` | coding agent | 复现 → 写红测试 → 改 → 跑 → 出验收记录 |
| `prompts/review.md` | 贵模型（loop3） | 回头审前两个循环，写错题本，拉黑 |

## 三种跑法

### 一、Claude Code 的 `/loop`（最省事）

在 Claude Code 里开三个窗口，各敲一次：

```
/loop 15m 你是 loop1。读 AGENTS.md，然后按 docs/LOOP_PROMPTS.md 里 loop1 的流程，
处理 GitHub 上还没处理过的 issue 和 PR。
```

```
/loop 10m 你是 loop2。读 AGENTS.md，按 loop2 的流程处理 X 关键词流里还没处理过的反馈。
先过 prompts/classify.md，不相关的直接扔。
```

```
/loop 1h 你是 loop3。读 AGENTS.md，按 loop3 的流程审查前两个循环最近的提交。
```

`/loop` 让同一段提示词隔一阵跑一遍，模型每次都是新的上下文——所以**规矩必须在文件里**，不能靠“上次说过”。

### 二、本仓库的 runner（有状态、能去重、能卡门）

```sh
python -m loops.run loop1 --once            # 跑一轮
python -m loops.run loop1 --interval 900    # 常驻，15 分钟一轮
python -m loops.run all --once --dry-run    # 三个循环各跑一轮，不写任何东西
```

runner 比 `/loop` 多做的事：

- **记住处理过哪些反馈**（`.loopstate/state.json`），不会重复回同一句话
- **卡三道门**（red/green、验收格式、危害清单），过不了就不合不发版
- 调 coding agent 时用的是 `prompts/fix.md` 渲染后的文本，不是让模型自己回忆流程
- 每轮的输入输出都落盘，loop3 有据可查

### 三、混着来

`/loop` 负责 loop3（纯读纯写文档，不需要状态），runner 负责 loop1 / loop2（需要去重和发版许可）。这是最贴近原文的用法：前两个动手，第三个只记。

## 三个循环的差异，只在三处

1. **入口**：`loop1` 拉 GitHub / Discord，`loop2` 拉 X。
2. **要不要预筛**：`loop2` 必须，因为产品名撞了球队外号；`loop1` 默认不筛（issue 本来就是冲着你来的），配置里可以打开。
3. **回复去哪**：issue 评论 / 推文回复。

其余全部共用：同一套 `triage.md`、`fix.md`、同一个验收格式、同一本 LESSONS。

## loop3 的提示词为什么第一句要那样写

原文那句 “现在代码改动和 PR 合并完全是自动化的”，不是描述，是**定位**。

写明这一句，模型才不会把“我看到一个 bug，顺手修了吧”当成自己的活。它一旦开始修，就会开始护自己的实现，审查就废了。所以 loop3 的写权限只到 `LESSONS.md`、`CODE_STANDARDS.md`（晋升区）、`config/blocklist.txt`——**碰不到业务代码**。
