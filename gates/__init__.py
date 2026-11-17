"""magpie 门禁包。

本文件只做一件事：让中文 Windows 控制台（GBK）也能跑这些门禁。
payload 的自检 / 危害门 / 验收门 / 发布门会打印 ✓ 与 ✗，
这两个字符不在 GBK 里，直接跑会在最后一行抛 UnicodeEncodeError。
这里把标准输出与标准错误重配成 UTF-8（失败则忽略），不影响逻辑。
"""

import sys

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # pragma: no cover - 非 TextIOWrapper 时没有 reconfigure
        pass
