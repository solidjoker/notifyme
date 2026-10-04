# 第三方组件与许可

本项目以 [MIT License](LICENSE) 发布。项目运行还依赖下列第三方组件；
在此向各作者致谢。各组件的完整许可证文本请参见其官方发布。

## 安卓端（Maven 依赖，不随源码分发）

| 组件 | 版本 | 许可证 |
|---|---|---|
| AndroidX Core KTX | 1.13.1 | Apache-2.0 |
| AndroidX RecyclerView | 1.3.2 | Apache-2.0 |
| AndroidX Work Runtime KTX | 2.9.1 | Apache-2.0 |
| OkHttp | 4.12.0 | Apache-2.0 |
| Kotlin Standard Library | 随 Kotlin 插件 | Apache-2.0 |

JSON 处理使用 Android 平台内置的 `org.json`，无额外依赖。

## 服务端（Python）

| 组件 | 版本 | 许可证 |
|---|---|---|
| Flask | 3.1.3 | BSD-3-Clause |

`gen_icons.py` 生成图标时使用 Pillow（HPND 许可证，历史因子许可证兼容），
仅为开发期工具，不属于运行时依赖。

## 构建工具

- **Gradle Wrapper** —— 版权归 Gradle Inc. 所有，Apache-2.0。
- **Android Gradle Plugin / Kotlin Gradle Plugin** —— 构建期使用，不分发其二进制。

## 关于数据来源的说明

- 本项目只读取用户主动授权的系统通知（`NotificationListenerService`）
  与用户自行配置的分析服务。
- `server/` 下可选的数据库历史回填脚本只使用 Python 标准库实现；
  如需配合外部微信数据库解密工具链，请通过环境变量
  `WX_TOOLCHAIN_DIR` 自行指定，该工具链**不随本仓库分发**，
  其使用受各自条款约束。

如发现本清单有遗漏或错误，欢迎提 Issue 指出。
