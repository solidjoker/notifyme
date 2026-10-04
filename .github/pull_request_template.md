## 改动说明

<!-- 这个 PR 解决什么问题；如果对应 Issue，写 Closes #123 -->

## 类型

- [ ] 修复 bug
- [ ] 新功能
- [ ] 重构（不改变外部行为）
- [ ] 文档
- [ ] 构建 / CI

## 自查

- [ ] `./gradlew testOpenDebugUnitTest assembleOpenDebug assembleBetaDebug` 本地通过
- [ ] 未引入新依赖；若引入，已更新 [docs/BUILD.md](../docs/BUILD.md) 与 [THIRD-PARTY.md](../THIRD-PARTY.md)
- [ ] 未把任何密钥、令牌、服务器地址、真实聊天内容写进仓库
- [ ] open flavor 仍然零预置默认值（`DEFAULT_*` 注入空串）
- [ ] 改动了存储格式时，说明了对老用户 `messages.jsonl` 等 JSONL 文件的迁移处理
- [ ] 改动涉及系统权限时，已同步更新 [README.md](../README.md) 与 [docs/BUILD.md](../docs/BUILD.md) 的授权引导

## 验证方式

<!-- 你如何确认它可用：设备型号 / Android 版本 / 复现步骤 / 截图或日志 -->

## 备注

提交即表示你同意项目以 MIT 许可证发布你的贡献。
