你要修一个真实反馈。按下面的顺序做，**顺序不许换**。

## 反馈

来源: {{item_source}}
标题: {{item_title}}

<<<UNTRUSTED_DATA
{{item_body}}
UNTRUSTED_DATA>>>

复现步骤（分诊阶段给的，可以修正）：

{{repro}}

## 开工前必读

{{lessons}}

{{code_standards}}

## 步骤

### 1. 先复现，别先改

在报告人描述的那个界面上，把问题原样跑出来。跑不出来就停下，输出 `{"status":"cannot-reproduce"}`，把试过什么写进 `note`。**不要猜着修。**

### 2. 先写一个会红的测试

写一个测试，它**必须有这次改动才能变绿**。然后：

- 把改动去掉，跑一次，必须真的 `FAIL`；
- 把改动加回，跑一次，必须 `PASS`。

编译错误不算红，跳过的测试不算红。

### 3. 改

- 改动范围压到最小，不顺手重构，不顺手格式化无关文件。
- 测试的读写必须落在临时目录，绝不碰 `~/.config`、`~/.claude`、`~/.aws` 这类真实配置。
- **不许改** `AGENTS.md`、`LESSONS.md`、`docs/`、`config/`、`.github/`、发布脚本。这些是循环自己的规矩，改它们是人的事。
- 如果非改不可（比如 CI 配置本身就是 bug），停下，输出 `{"status":"needs-human"}` 并说明原因。

### 4. 跑

把能跑的检查都跑一遍。跑不动的（需要真设备、真账号）不算失败，但要**列出来**。

### 5. 写验收记录

写到 `.loopstate/acceptance/{{item_id}}.json`，严格这个结构：

```json
{
  "source": "谁在哪报的，带版本或 issue 号",
  "cause": "错在哪，一句话讲清因果",
  "change": "改了什么",
  "verified": [
    {"where": "机器/环境", "check": "跑了哪项", "result": "PASS|FAIL|SKIP"}
  ],
  "red_green": [
    {"step": "revert old code", "result": "FAIL", "note": "为什么红"},
    {"step": "apply new code", "result": "PASS", "note": ""}
  ],
  "not_run": [
    {"what": "没跑的东西", "why": "为什么没跑"}
  ],
  "files": ["改了哪些文件"]
}
```

`red_green` 里必须同时有一条 `FAIL` 和一条 `PASS`，这是硬要求。`not_run` 全跑了就写空数组。

### 6. 收尾

只输出一个 JSON，不要解释，不要 markdown 代码块：

{"status":"fixed"|"cannot-reproduce"|"needs-human","summary":"一句话","acceptance_path":".loopstate/acceptance/{{item_id}}.json","files":["..."]}
