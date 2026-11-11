#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""notifyme 隐私与仓库卫生守卫 —— CI 与本地跑的是同一份实现。

用法：
    python docs/scripts/privacy_guard.py                # 检查当前仓库
    python docs/scripts/privacy_guard.py --root C:\\repo # 指定仓库根目录
    python docs/scripts/privacy_guard.py --rule G3      # 只跑某一条规则
    python docs/scripts/privacy_guard.py --list         # 列出规则清单

退出码：
    0 = 全部通过
    1 = 有违规（每条违规打印一行 ::error:: 供 GitHub Actions 直接高亮）

为什么把规则搬到这里：
    以前 CI 的 grep 只写在 .github/workflows/android.yml 里，本地自查靠复制命令，
    两边很容易漂移。现在规则正文只有这一份：
      CI   -> .github/workflows/android.yml 的「Guard」步骤调用本脚本
      本地 -> 直接 python docs/scripts/privacy_guard.py
    对应 AGENTS.md 第 4 节「硬红线」五条。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Iterable, List, NamedTuple, Optional

# --------------------------------------------------------------------------
# 规则常量
#
# 注意：所有正则 / 字面量一律用「自规避写法」（方括号前缀、字符串拼接），
# 否则本文件自己就会命中自己定义的规则。
# --------------------------------------------------------------------------

# G3：私有母仓库旧命名（企微系 4 个旧标识）。代码与文档都不许出现。
#     .github/ 与本文件自身除外（AGENTS.md 第 4 节第 2 条）。
PRIVATE_NAME_RE = re.compile(
    r"[q]iyeweixin|[w]eixin_android|[W]eixinAndroid|[w]eixin-monitor"
)

# G4：真实个人用户目录。一律写占位符 C:\Users\<你>。
#     用拼接构造，避免本文件出现该字面量。
_USER_DIR = "C:" + chr(92) + "Users" + chr(92)
_USER_DIR_SLASH = "C:" + "/" + "Users" + "/"
USER_DIR_PLACEHOLDER = _USER_DIR + "<"
USER_DIR_SLASH_PLACEHOLDER = _USER_DIR_SLASH + "<"

# G1：禁止入库的本地密钥 / 机器配置（文件名级）
FORBIDDEN_BASENAMES = {
    "secrets.local.properties",
    "secrets.local.bat",
    "keystore.properties",
    "local.properties",
}

# G2：禁止入库的文件类型与目录（扩展名级 + 目录前缀级）
FORBIDDEN_SUFFIXES = {
    ".apk", ".aab", ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".der",
}
FORBIDDEN_PATH_PREFIXES = (
    "app/build/",
    "build/",
    "server/data/",
    "files/",
    "app/files/",
)
# *.jsonl = 真实用户数据（消息原文 / 待上报队列）。文档与测试资源目录白名单。
JSONL_ALLOW_PREFIXES = ("docs/", "app/src/test/resources/")

# G6：.gitignore 必须一直保留的条目（谁把忽略规则删了，守卫就报）
REQUIRED_GITIGNORE_ENTRIES = (
    "secrets.local.properties",
    "secrets.local.bat",
    "keystore.properties",
    "*.keystore",
    "*.jks",
    "*.apk",
    "*.aab",
)

# 扫描字符串时跳过的目录（构建产物 / 第三方源码 / IDE 缓存）
SKIP_DIR_NAMES = {
    ".git", ".gradle", ".gradle-home", ".idea", "build", "dist", "tmp",
    "captures", "__pycache__", ".venv", "venv", "node_modules", "third_party",
    ".kotlin", ".cxx",
}
# 兜底遍历时还要跳过的运行时目录（相对仓库根）
SKIP_REL_DIRS = (
    "server/data",
    "files",
    "app/files",
)
# 二进制文件不扫字符串
BINARY_SUFFIXES = {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".jar", ".aar", ".so",
    ".ttf", ".otf", ".mp3", ".wav", ".pdf", ".zip", ".keystore", ".jks", ".db",
}
MAX_SCAN_BYTES = 2 * 1024 * 1024

RULES = {
    "G1": "禁止入库的本地密钥 / 机器配置（secrets.local.properties / secrets.local.bat / keystore.properties / local.properties）",
    "G2": "禁止入库构建产物与真实用户数据（apk / aab / 密钥库 / jsonl / build 目录）",
    "G3": "禁止出现私有母仓库旧命名（企微系 4 个旧标识）",
    "G4": "禁止出现真实个人用户目录（必须写占位符）",
    "G5": "open flavor 的 BuildConfig 默认值必须全部为空串（发布包零密钥）",
    "G6": ".gitignore 必须保留敏感文件的忽略规则",
    "G7": "工作区里不允许存在本地密钥文件（CI 模式，用 --forbid-local-secrets 开启）",
}


class Violation(NamedTuple):
    rule: str
    path: str
    line: int
    message: str

    def render(self) -> str:
        loc = "file=" + self.path + ",line=" + str(self.line)
        return ("::error " + loc + ",title=" + self.rule + "::"
                + self.rule + " " + self.message + " —— " + self.path + ":" + str(self.line))


# --------------------------------------------------------------------------
# 工具
# --------------------------------------------------------------------------
def _git(root: Path, *args: str) -> Optional[str]:
    """跑 git 并取回 UTF-8 文本。

    必须用 bytes 模式自己解码：Windows 默认编码是 GBK，而仓库里有中文
    文件名（docs/评测-*.md），用 text=True 会让 CPython 的读线程抛
    UnicodeDecodeError，stdout 变成空串 —— 守卫会退化成目录遍历，把
    .gitignore 已经忽略掉的本地产物全当成违规（实测 3310 条误报）。
    """
    try:
        proc = subprocess.run(
            ["git", "-C", str(root)] + list(args),
            capture_output=True, check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    return proc.stdout.decode("utf-8", errors="replace")


def list_tracked(root: Path) -> List[str]:
    out = _git(root, "ls-files", "-z")
    if out is None:
        return []
    return [p for p in out.split("\0") if p]


def list_untracked(root: Path) -> List[str]:
    """未被 .gitignore 忽略的未跟踪文件：这些文件一旦 git add 就会入库。"""
    out = _git(root, "ls-files", "-z", "--others", "--exclude-standard")
    if out is None:
        return []
    return [p for p in out.split("\0") if p]


def walk_fallback(root: Path) -> List[str]:
    """没有 git 时的兜底目录遍历（跳过构建产物与缓存目录）。"""
    found: List[str] = []
    for dirpath, dirnames, filenames in os.walk(str(root)):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIR_NAMES]
        rel_dir = Path(dirpath).resolve().relative_to(root).as_posix()
        if rel_dir == ".":
            rel_dir = ""
        if any(rel_dir == p or rel_dir.startswith(p + "/") for p in SKIP_REL_DIRS):
            dirnames[:] = []
            continue
        for name in filenames:
            rel = (Path(dirpath) / name).relative_to(root).as_posix()
            found.append(rel)
    return found


def read_text(path: Path) -> Optional[str]:
    try:
        if path.stat().st_size > MAX_SCAN_BYTES:
            return None
        raw = path.read_bytes()
    except OSError:
        return None
    if b"\0" in raw:
        return None
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return None


def extract_block(text: str, marker: str) -> str:
    """从 marker 处的花括号块原文（brace matching，不靠正则硬啃嵌套）。"""
    idx = text.find(marker)
    if idx < 0:
        return ""
    start = text.find("{", idx)
    if start < 0:
        return ""
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return ""


# buildConfigField("String", "DEFAULT_X", bcString(<表达式>))
BC_FIELD_RE = re.compile(
    r'buildConfigField\(\s*"String"\s*,\s*"([A-Z0-9_]+)"\s*,\s*bcString\(\s*(.+?)\s*\)\s*\)'
)


# --------------------------------------------------------------------------
# 规则实现
# --------------------------------------------------------------------------
def rule_forbidden_files(tracked: Iterable[str], untracked: Iterable[str]) -> List[Violation]:
    """G1 + G2：禁止入库的密钥文件 / 构建产物 / 真实用户数据。"""
    out: List[Violation] = []
    for rel in list(tracked) + list(untracked):
        base = rel.rsplit("/", 1)[-1]
        if base in FORBIDDEN_BASENAMES:
            out.append(Violation("G1", rel, 0, "禁止入库的本地密钥 / 机器配置：" + base))
        suffix = Path(base).suffix.lower()
        if suffix in FORBIDDEN_SUFFIXES:
            out.append(Violation("G2", rel, 0, "禁止入库的文件类型：" + suffix + "（" + base + "）"))
        if suffix == ".jsonl" and not rel.startswith(JSONL_ALLOW_PREFIXES):
            out.append(Violation("G2", rel, 0, "禁止入库的真实用户数据 JSONL"))
        for prefix in FORBIDDEN_PATH_PREFIXES:
            if rel.startswith(prefix):
                out.append(Violation("G2", rel, 0, "禁止入库的构建产物 / 运行时数据目录：" + prefix))
    return out


def rule_strings(files: Iterable[str], self_rel: str, root: Path) -> List[Violation]:
    """G3 + G4：私有母仓库旧命名 / 真实个人用户目录。"""
    out: List[Violation] = []
    for rel in files:
        if rel.startswith(".github/"):
            continue  # AGENTS.md 第 4 节：.github/ 自身除外
        if rel == self_rel:
            continue  # 本守卫文件自身除外
        if Path(rel).suffix.lower() in BINARY_SUFFIXES:
            continue
        text = read_text(root / rel)
        if text is None:
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            if PRIVATE_NAME_RE.search(line):
                out.append(Violation("G3", rel, lineno, "发现私有母仓库旧命名残留"))
            hit_win = _USER_DIR in line and USER_DIR_PLACEHOLDER not in line
            hit_slash = _USER_DIR_SLASH in line and USER_DIR_SLASH_PLACEHOLDER not in line
            if hit_win or hit_slash:
                out.append(Violation(
                    "G4", rel, lineno,
                    "发现真实个人用户目录路径，请改用占位符 " + _USER_DIR + "<你>",
                ))
    return out


def rule_open_flavor(root: Path) -> List[Violation]:
    """G5：open flavor 的默认值必须全为空串，且两个 flavor 的字段集合一致。"""
    rel = "app/build.gradle.kts"
    text = read_text(root / rel)
    if text is None:
        return [Violation("G5", rel, 0, "读不到 app/build.gradle.kts，无法校验 open flavor 默认值")]
    open_block = extract_block(text, 'create("open")')
    beta_block = extract_block(text, 'create("beta")')
    out: List[Violation] = []
    if not open_block:
        return [Violation("G5", rel, 0, "在 app/build.gradle.kts 里找不到 open flavor 的定义块")]

    open_fields = {}
    for m in BC_FIELD_RE.finditer(open_block):
        open_fields[m.group(1)] = m.group(2).strip()
    for name, expr in open_fields.items():
        if expr != '""':
            out.append(Violation(
                "G5", rel, 0,
                "open flavor 的 " + name + " 默认值必须为空串，实际是 " + expr
                + "（发布包零密钥红线）",
            ))
    if "secret(" in open_block:
        out.append(Violation(
            "G5", rel, 0,
            "open flavor 不得调用 secret(...) 读取 secrets.local.properties",
        ))

    beta_fields = {m.group(1) for m in BC_FIELD_RE.finditer(beta_block)}
    if beta_fields and set(open_fields) != beta_fields:
        only_open = sorted(set(open_fields) - beta_fields)
        only_beta = sorted(beta_fields - set(open_fields))
        out.append(Violation(
            "G5", rel, 0,
            "open / beta 两个 flavor 的 BuildConfig 字段集合不一致（仅 open: "
            + str(only_open) + "；仅 beta: " + str(only_beta) + "）",
        ))
    return out


def rule_local_secret_files(root: Path) -> List[Violation]:
    """G7（CI 模式）：工作区里不允许存在本地密钥文件。

    G1 只管「有没有入库」；这条管「文件在不在工作区」。
    CI 里 checkout 是干净的，secrets.local.* / keystore.properties 出现即事故；
    开发机上它们本来就该存在（且被 .gitignore 忽略），所以默认不查。
    """
    out: List[Violation] = []
    for name in ("secrets.local.properties", "secrets.local.bat", "keystore.properties"):
        if (root / name).exists():
            out.append(Violation(
                "G7", name, 0,
                "工作区里存在本地密钥文件：" + name
                + "（CI 上不允许存在；本地请确认它已被 .gitignore 忽略）",
            ))
    return out


def rule_gitignore(root: Path) -> List[Violation]:
    """G6：.gitignore 必须保留敏感文件的忽略规则。"""
    rel = ".gitignore"
    text = read_text(root / rel)
    if text is None:
        return [Violation("G6", rel, 0, "读不到 .gitignore")]
    out: List[Violation] = []
    for entry in REQUIRED_GITIGNORE_ENTRIES:
        if entry not in text:
            out.append(Violation(
                "G6", rel, 0,
                ".gitignore 缺少忽略规则：" + entry + "（密钥文件一旦入库就是事故）",
            ))
    return out


# --------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------
def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="notifyme 隐私与仓库卫生守卫")
    parser.add_argument("--root", default=None, help="仓库根目录（默认取本脚本上两级）")
    parser.add_argument("--rule", default=None, help="只跑指定规则，如 G3")
    parser.add_argument("--list", action="store_true", help="列出规则清单后退出")
    parser.add_argument(
        "--forbid-local-secrets",
        action="store_true",
        help="额外执行 G7：工作区里不允许存在 secrets.local.* / keystore.properties（CI 用）",
    )
    args = parser.parse_args(argv)

    if args.list:
        for key, desc in RULES.items():
            print(key + ": " + desc)
        return 0

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parents[2]
    if not (root / "app").exists() or not (root / ".git").exists():
        print("::error::看起来不是 notifyme 仓库根目录：" + str(root))
        return 1

    self_path = Path(__file__).resolve()
    try:
        self_rel = self_path.relative_to(root).as_posix()
    except ValueError:
        self_rel = ""

    tracked = list_tracked(root)
    untracked = list_untracked(root)
    if not tracked:
        tracked = walk_fallback(root)
    scan_files = sorted(set(tracked) | set(untracked))

    # G7 只在 CI 模式（--forbid-local-secrets）或显式 --rule G7 时执行：
    # 开发机上本来就应该存在 secrets.local.properties / secrets.local.bat（已被 .gitignore 忽略）。
    selected = {args.rule} if args.rule else (set(RULES) - {"G7"})
    if args.forbid_local_secrets:
        selected.add("G7")
    violations: List[Violation] = []

    if "G1" in selected or "G2" in selected:
        violations += rule_forbidden_files(tracked, untracked)
    if "G3" in selected or "G4" in selected:
        violations += rule_strings(scan_files, self_rel, root)
    if "G5" in selected:
        violations += rule_open_flavor(root)
    if "G6" in selected:
        violations += rule_gitignore(root)
    if "G7" in selected or args.forbid_local_secrets:
        violations += rule_local_secret_files(root)

    print("[guard] 仓库：" + str(root))
    print("[guard] 扫描：已跟踪 " + str(len(tracked)) + " 个文件 + 未跟踪未忽略 "
          + str(len(untracked)) + " 个文件")
    print("[guard] 规则：" + ", ".join(sorted(selected)))

    if not violations:
        print("[guard] 通过：未发现违规。")
        return 0

    print("[guard] 发现 " + str(len(violations)) + " 处违规：")
    for v in violations:
        print(v.render())
    print("[guard] 修完再跑一次：python docs/scripts/privacy_guard.py")
    return 1


if __name__ == "__main__":
    # Windows 控制台默认 GBK，中文输出会乱码；统一按 UTF-8 输出。
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except AttributeError:
        pass
    sys.exit(main())
