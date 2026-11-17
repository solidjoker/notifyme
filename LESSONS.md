# LESSONS.md — 错题本

> **每个循环每轮开工前必须从头读一遍。**
> 一条教训写三样：**规则**、**为什么**、**证据**（commit / issue）。最后记 `Seen`：在几天的复盘里被抓到过。

## 这本册子怎么升级

不是所有教训都停在这里。三级台阶，一级比一级硬：

| 级别 | 触发条件 | 落到哪 |
| --- | --- | --- |
| 1 | 记下来 | 本文件 |
| 2 | `Seen` 里有 **3 个不同日期** | 挪进 [docs/CODE_STANDARDS.md](docs/CODE_STANDARDS.md)，以后每次合并逐条对 |
| 3 | 晋升后仍标 `Machine-checkable: true` | 再写进 `config/ci_rules.json`，CI 机器查 |

升级由 `python -m gates.lessons_promote --apply` 执行，别手抄。晋升过的条目会从本文件消失，痕迹留在 CODE_STANDARDS.md 的标记里。

## 条目格式

```markdown
## L-000 一句话规则

- Rule: 祈使句，说清“做什么”而不是“要小心”。
- Why: 出过什么事，一句话讲完因果。
- Evidence: <commit-sha>, #<issue>
- Seen: YYYY-MM-DD, YYYY-MM-DD
- Machine-checkable: false
```

`Machine-checkable` 只在能写成一条确定性检查时才填 `true`（grep / 正则 / 断言），需要人判断的一律 `false`。

---

## L-001 修之前先原样复现

- Rule: 动手改之前，先在报告人用的那个界面上原样复现一次；复现不出来，就先问，不要猜着修。
- Why: 一个 issue 返工 4 次，每次都只修了截图里的那一个 Agent，根本原因一直没碰上。
- Evidence: 3f9c1a2, #412
- Seen: 2026-10-06, 2026-10-08, 2026-10-11
- Machine-checkable: false

## L-002 测试只许在临时目录里跑

- Rule: 测试读写配置必须落在临时目录，绝不允许碰 `~/.claude`、`~/.config` 这类真实配置。
- Why: 自动化循环在开发机上跑测试，把维护者自己的凭据文件写坏了。
- Evidence: 8b41d07, #388
- Seen: 2026-10-07, 2026-10-09, 2026-10-12
- Machine-checkable: true

## L-003 上一轮测试没跑完，不许发版

- Rule: main 分支上的测试必须跑完才算数；为了赶下一个提交去取消它，等于没测。
- Why: 10 月 5 日一天 167 个版本，其中约 40 个在修当天刚发出去的版本——测试被下一次推送连续取消。
- Evidence: c05ae91, #301
- Seen: 2026-10-05
- Machine-checkable: true

## L-004 没跑的检查要列出来，不能不提

- Rule: 提交说明里必须有 `Not run:` 一节；确实全跑了就写 `Not run: none`。
- Why: 省略没跑的项目，等于把“没验证”伪装成“验证过”。
- Evidence: 5d2e6f0, #455
- Seen: 2026-10-06, 2026-10-14
- Machine-checkable: true

## L-005 提交里不许塞编码过的二进制

- Rule: 任何 base64 / hex 长块、未声明的二进制，一律不进 PR。
- Why: 无法审阅的代码块是投毒最常见的载体。
- Evidence: a71bc33, #467
- Seen: 2026-10-15
- Machine-checkable: true
