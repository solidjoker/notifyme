# LESSONS.md — 夹具

> 错题本解析与晋升的测试夹具。LessonTests 读它，不读活仓库的 LESSONS.md ——
> 活仓库那本会随 `python -m gates.lessons_promote --apply` 变化（够格的条目会
> 挪进 docs/CODE_STANDARDS.md），用例跟着它变就会在一次晋升后自己跑红。

## L-001 修之前先原样复现

- Rule: 动手改之前，先原样复现一次；复现不出来，就先问，不要猜着修。
- Why: 一个 issue 返工 4 次，每次都只修了截图里的那一个，根本原因一直没碰上。
- Evidence: 3f9c1a2, #412
- Seen: 2026-10-06, 2026-10-08, 2026-10-11
- Machine-checkable: false

## L-002 测试只许在临时目录里跑

- Rule: 测试读写配置必须落在临时目录，绝不允许碰真实配置。
- Why: 自动化循环在开发机上跑测试，把维护者自己的凭据文件写坏了。
- Evidence: 8b41d07, #388
- Seen: 2026-10-07, 2026-10-09, 2026-10-12
- Machine-checkable: true

## L-005 提交里不许塞编码过的二进制

- Rule: 任何 base64 / hex 长块、未声明的二进制，一律不进 PR。
- Why: 无法审阅的代码块是投毒最常见的载体。
- Evidence: a71bc33, #467
- Seen: 2026-10-15
- Machine-checkable: true
