# COMMUNITY_RULES.md — 社区规则

> 社区 PR 由 agent 合并。**但审之前先查危害。**
> 这份清单在 CI 里由 `gates/hazard_check.py` 机器执行，规则表在 `config/hazard_rules.json`。改清单请同时改这两处。

## 1. 碰到任何一条就停，等维护者看过再说

| # | 危害类别 | 具体表现 | 机器规则 |
| --- | --- | --- | --- |
| H1 | CI / 发布链路 | 改 `.github/**`、发布脚本、构建配置、官网 | `ci_release` |
| H2 | 网络出口 | 新增任何外发地址：HTTP 接口、webhook、DNS、上报端点 | `new_network_address` |
| H3 | 上传与遥测 | 新加埋点、崩溃上报、行为统计 | `telemetry_upload` |
| H4 | 凭据 | 往新地方发凭据，或新读写凭据文件（`~/.ssh`、keychain、`*.pem`、dotenv） | `credential_access` |
| H5 | 依赖与不可审阅物 | 加依赖、加二进制、加一团编码过的代码 | `dependency_change` / `opaque_blob` |
| H6 | 商业入口 | 加中转站、经销商、推广链接、`?ref=`、联盟参数 | `promotion_link` |

**维护者放行之前，这个 PR 的代码不准在有真实账号的机器上跑。**

## 2. PR 审查流程

```
新 PR
  ├─ hazard_check ── BLOCK ──→ 打 needs-human，等维护者，到此为止
  └─ PASS
       └─ 逐行读 diff（不是扫标题）
            └─ 拉到本地真跑一遍（不是看 CI 绿了就算）
                 └─ acceptance 记录合格？
                      ├─ 否 → 要求补，不合
                      └─ 是 → 合并
```

三条不许省的：

1. **逐行看。** 跳过 diff 直接合，是 loop3 的常客。
2. **本地真跑。** CI 绿只说明 CI 绿。
3. **验收记录对得上。** `Red/Green` 里说的那个 FAIL，得是真的会 FAIL。

## 3. 不值得做的 issue 也要回一句

不修可以，装死不行。回复里给三点：为什么不做、什么情况下会重新考虑、有没有替代做法。然后打 `wontfix` 或 `needs-info`。

## 4. 总交烂代码的人

同一个账号连续 3 次提交被 loop3 判定“验证不足”或“夹带不相关改动”，写进 `config/blocklist.txt`，后续 PR 一律人工。除名要维护者手动删行，循环不许自己删。

## 5. 钱和位置

合作位是卖的，不是送的。往 PR 里塞推广链接、中转站推荐、返利参数，按 H6 直接停，并且记一条 LESSONS。
