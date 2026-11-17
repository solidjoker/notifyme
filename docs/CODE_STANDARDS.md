# CODE_STANDARDS.md — 代码规范

> 每次合并前逐条对；也是 `AGENTS.md` 里“写代码”那条链的终点。
> 本文件末尾有一节 **「由 LESSONS 晋升」**，由 `gates/lessons_promote` 自动追加，不要手改那一节的标记。

## 1. 一个改动要算“做完”，得同时满足

1. **有一个“没有这次改动就会失败”的测试。** 把旧代码放回去，测试必须真的 `FAIL`。编译错误不算，`skip` 不算，`xfail` 不算。
2. **测试在临时目录里跑**，绝不碰 `~/.config`、`~/.claude`、`~/.aws` 这类真实配置（LESSONS L-002）。
3. **跑了什么、在哪跑的、什么结果，全写进验收记录**；没跑的列成没跑，不能省略。
4. **验收记录能被机器解析通过**：`python -m gates.acceptance_check --message <file>` 退出码 0。

> 原文那句话留着当尺子：*A check that wasn't run is listed as not run, never left out.*

## 2. 验收记录（Acceptance record）

提交说明里必须带下面这一块。机器只认这套格式，散文不算。

```text
Acceptance:
Source: x/@someone (v0.1.3336) / issue #412
Cause: dash 不支持 `function name()` 这种写法，写进 NAS 的 shell 配置就语法错。
Change: 改用 POSIX 兼容写法，并加了一条在 dash 下跑的回归测试。
Verified:
  - macos-15 arm64 | full suite | PASS
  - wsl ubuntu 24.04, /bin/sh=dash | full suite | PASS
Red/Green:
  - revert old code: FAIL (syntax error from dash)
  - apply new code: PASS
Not run:
  - 真实那台 NAS (手上没有设备)
```

### 字段规则

| 字段 | 必填 | 机器校验 |
| --- | --- | --- |
| `Source` | 是 | 首行给出来源；带版本号或 issue 号会额外记一分（软校验） |
| `Cause` | 是 | 非空，写错在哪，不写“修复了一些问题” |
| `Change` | 是 | 非空，写改了什么 |
| `Verified` | 是 | ≥1 行，每行 `位置 \| 检查项 \| 结果`，结果只能是 `PASS` / `FAIL` / `SKIP` |
| `Red/Green` | 是 | **必须同时出现一次 `FAIL` 和一次 `PASS`**——旧代码变红、新代码变绿，缺一不算数 |
| `Not run` | 是 | 必须有这一节；全跑了写 `none`，否则逐条列，能写原因就写 |

### 为什么卡得这么死

前两个循环既修又验，等于自己给自己验收。这套格式就是拿来防这件事的：跑没跑、在哪跑、哪些没跑，全摊在提交说明里，loop3 和维护者回头能对账。

## 3. 提交

- 一个提交只做一件事。修 bug 和顺手重构，分两次。
- 提交说明首行是祈使句，≤ 72 字符。
- 提交说明末尾带循环标记，loop3 靠它找人：
  ```
  Loop: loop1
  ```
- 标签（tag）由发布脚本打，循环不手打。

## 4. 发布

一个提交要打成正式版，三件事缺一不可：

1. 一次**跑完的绿色**全量测试（不是被取消的那种）；
2. 安装包全齐（当前 16 个），一个不少；
3. 标成 `Latest`。

满足这三条才算“发布了一次”。数字对不上就不发。

## 5. 机器检查

```sh
python -m gates.selfcheck            # 格式 + 导入 + 单测，CI 跑的就是这个
python -m gates.acceptance_check --message .git/COMMIT_EDITMSG
python -m gates.hazard_check --base origin/main --head HEAD
python -m gates.release_check --tag v20261117.1
```

CI 里任何一条红，不许合。

---

## 由 LESSONS 晋升

<!-- promoted-zone:start -->
<!-- 本节由 gates/lessons_promote 追加，请勿手改 -->
<!-- promoted-zone:end -->
