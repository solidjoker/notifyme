#!/usr/bin/env python3
"""Kotlin 静态检查（ktlint CLI，固定版本 + 固定 sha256）。

为什么要这个脚本：
  本仓库的 ktlint 规则集在 .editorconfig 里被刻意收窄到 5 条
  （import-ordering / no-unused-imports / final-newline /
   no-consecutive-blank-lines / string-template）。这 5 条都能被
  ktlint --format 全自动修干净，且与「代码是否真的有问题」相关：
  unused import 会误导读者、import 顺序乱会让合并冲突变多、
  末行无换行会让行级 diff 出现 No-newline 噪声、
  连续空行和多余的 ${} 是纯噪声。

  其余 39 条 standard 规则（function-signature / indent / trailing-comma ...）
  全部 disabled：它们是格式化偏好，一次性重排会把 205 个文件的 diff 搅成噪声，
  而且和本仓库既有事实冲突（最长行 259 字符、.kt 行尾 LF/CRLF 混用）。
  这就是 docs/ROADMAP.md M1 任务 4 说的「只报警不阻断」的落点。

两种模式：
  --report（默认）：只打印结果与汇总，有命中也 exit 0。CI 用这个模式，别卡住 PR。
  --strict       ：有命中 exit 1。docs/scripts/local_check.ps1 用这个模式，
                   因为它代表「我说干净就要真的干净」的本地验收口径。
  --fix          ：先跑 ktlint --format 自动修，再复查一遍。

用法：
  python docs/scripts/static_check.py
  python docs/scripts/static_check.py --strict
  python docs/scripts/static_check.py --fix
  python docs/scripts/static_check.py --offline
"""

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import urllib.request

KTLINT_VERSION = "1.8.0"
KTLINT_URL = (
    "https://repo.maven.apache.org/maven2/com/pinterest/ktlint/ktlint-cli/"
    + KTLINT_VERSION
    + "/ktlint-cli-"
    + KTLINT_VERSION
    + "-all.jar"
)
# 与 Maven Central 上同名 .sha256 一致；换版本必须同时换这两处。
KTLINT_SHA256 = "369ad2b789f95a011f807e1fcb690ccef80bd7cd014fd139e73ae82dcc0baeab"

# 与 .editorconfig 里的 ktlint_standard_* 开关一一对应，改动要同步。
ENABLED_RULES = (
    "standard:import-ordering",
    "standard:no-unused-imports",
    "standard:final-newline",
    "standard:no-consecutive-blank-lines",
    "standard:string-template",
)
PATTERNS = ("app/src/**/*.kt", "*.kts", "app/*.kts", "!third_party/**")

FINDING_RE = re.compile(
    r"^(?P<file>.+?):(?P<line>[0-9]+):(?P<col>[0-9]+): (?P<msg>.*) [(](?P<rule>[a-z0-9-]+:[a-z0-9-]+)[)]$"
)


def repo_root():
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def cache_dir():
    base = os.environ.get("NOTIFYME_TOOLS_DIR")
    if not base:
        base = os.path.join(os.path.expanduser("~"), ".notifyme-tools")
    return os.path.join(base, "ktlint", KTLINT_VERSION)


def find_java():
    explicit = os.environ.get("NOTIFYME_JAVA")
    if explicit and os.path.exists(explicit):
        return explicit
    home = os.environ.get("JAVA_HOME")
    if home:
        for name in ("java.exe", "java"):
            cand = os.path.join(home, "bin", name)
            if os.path.exists(cand):
                return cand
    return shutil.which("java")


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def ensure_jar(offline):
    explicit = os.environ.get("NOTIFYME_KTLINT_JAR")
    if explicit and os.path.exists(explicit):
        return explicit, "env"
    target = os.path.join(cache_dir(), "ktlint-cli-" + KTLINT_VERSION + "-all.jar")
    if os.path.exists(target) and sha256_of(target) == KTLINT_SHA256:
        return target, "cache"
    if offline:
        raise SystemExit(
            "[static-check] 离线模式且没有可用缓存："
            + target
            + "；先联网跑一次，或设置 NOTIFYME_KTLINT_JAR"
        )
    os.makedirs(os.path.dirname(target), exist_ok=True)
    tmp = target + ".part"
    print("[static-check] 下载 ktlint " + KTLINT_VERSION + "（约 71 MB，只需一次）")
    urllib.request.urlretrieve(KTLINT_URL, tmp)
    got = sha256_of(tmp)
    if got != KTLINT_SHA256:
        os.remove(tmp)
        raise SystemExit(
            "[static-check] sha256 不匹配，拒绝使用：期望 " + KTLINT_SHA256 + " 实得 " + got
        )
    os.replace(tmp, target)
    return target, "download"


def run_ktlint(java, jar, extra):
    cmd = [java, "-jar", jar, "--relative"] + list(extra) + list(PATTERNS)
    proc = subprocess.run(
        cmd, cwd=repo_root(), capture_output=True, text=True, errors="replace"
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def parse(output):
    findings = []
    for line in output.splitlines():
        m = FINDING_RE.match(line.strip())
        if m:
            findings.append(m.groupdict())
    return findings


def main():
    ap = argparse.ArgumentParser(
        description="Kotlin 静态检查（ktlint " + KTLINT_VERSION + "）"
    )
    ap.add_argument("--strict", action="store_true", help="有命中就以退出码 1 结束")
    ap.add_argument("--fix", action="store_true", help="先 ktlint --format 再复查")
    ap.add_argument("--offline", action="store_true", help="不联网，只用缓存 jar")
    ap.add_argument("--limit", type=int, default=40, help="最多打印多少条明细")
    args = ap.parse_args()

    java = find_java()
    if not java:
        print("[static-check] 找不到 java；设 JAVA_HOME 或 NOTIFYME_JAVA")
        return 1

    jar, source = ensure_jar(args.offline)
    print("[static-check] ktlint " + KTLINT_VERSION + "（jar 来源：" + source + "）")
    print(
        "[static-check] 启用规则 "
        + str(len(ENABLED_RULES))
        + " 条："
        + ", ".join(ENABLED_RULES)
    )

    if args.fix:
        code, out = run_ktlint(java, jar, ["--format"])
        print("[static-check] --format 退出码 " + str(code) + "（0 = 已修完或本来没问题）")
        if code not in (0, 1):
            print(out[:4000])
            return 1

    code, out = run_ktlint(java, jar, [])
    findings = parse(out)

    rules = {}
    for f in findings:
        rules[f["rule"]] = rules.get(f["rule"], 0) + 1
    files = sorted({f["file"] for f in findings})
    unexpected = sorted(r for r in rules if r not in ENABLED_RULES)

    for f in findings[: args.limit]:
        print(
            "  " + f["file"] + ":" + f["line"] + ":" + f["col"]
            + "  " + f["msg"] + "  (" + f["rule"] + ")"
        )
    if len(findings) > args.limit:
        print("  ... 还有 " + str(len(findings) - args.limit) + " 条未打印")
    if findings:
        print("  按规则统计：")
        for r in sorted(rules, key=lambda k: (-rules[k], k)):
            print("    " + str(rules[r]).rjust(5) + "  " + r)
    if unexpected:
        print(
            "  [static-check] 出现未启用的规则（.editorconfig 与脚本不同步？）："
            + ", ".join(unexpected)
        )

    print(
        "static-check-summary issues="
        + str(len(findings))
        + " files="
        + str(len(files))
        + " rules="
        + str(len(rules))
        + " enabled="
        + str(len(ENABLED_RULES))
    )
    if findings:
        print("  修法：python docs/scripts/static_check.py --fix")

    hard = bool(unexpected) or (args.strict and bool(findings))
    return 1 if hard else 0


if __name__ == "__main__":
    sys.exit(main())
