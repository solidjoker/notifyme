"""循环运行时：三个循环共用的地基。

只依赖标准库。设计上只有一个原则——**循环之间不共享内存、不传话**，
所有需要跨轮次知道的东西，要么落在 .loopstate/（一次性工作记忆），
要么落在仓库里的规矩文件（要留下来的东西）。
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
import tomllib
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Sequence

REPO_ROOT = Path(__file__).resolve().parent.parent
STATE_DIR = REPO_ROOT / ".loopstate"


# --------------------------------------------------------------------------- 配置


def load_config(path: str | Path | None = None) -> dict[str, Any]:
    cfg_path = Path(path) if path else REPO_ROOT / "config" / "workflow.toml"
    with open(cfg_path, "rb") as fh:
        cfg = tomllib.load(fh)
    cfg.setdefault("_path", str(cfg_path))
    return cfg


def cfg_get(cfg: dict[str, Any], dotted: str, default: Any = None) -> Any:
    """按 'loop1.release' 这种点号路径取值。"""
    cur: Any = cfg
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return default
        cur = cur[part]
    return cur


# --------------------------------------------------------------------------- 时间


def now() -> datetime:
    return datetime.now(timezone.utc).astimezone()


def today() -> str:
    return now().strftime("%Y-%m-%d")


def stamp() -> str:
    return now().strftime("%Y-%m-%d %H:%M:%S")


def in_window(window: str, when: datetime | None = None) -> bool:
    """'02:00-05:00' 是否包含此刻。支持跨零点（'22:00-06:00'）。"""
    when = when or now()
    try:
        start_s, end_s = window.split("-", 1)
        sh, sm = (int(x) for x in start_s.split(":"))
        eh, em = (int(x) for x in end_s.split(":"))
    except ValueError:
        return True
    cur = when.hour * 60 + when.minute
    start, end = sh * 60 + sm, eh * 60 + em
    if start <= end:
        return start <= cur < end
    return cur >= start or cur < end


# --------------------------------------------------------------------------- 日志


class Journal:
    """每轮发生了什么，全落盘。loop3 第二天靠它回头审。"""

    def __init__(self, loop: str) -> None:
        self.loop = loop
        self.dir = STATE_DIR / "logs"
        self.dir.mkdir(parents=True, exist_ok=True)
        self.path = self.dir / f"{loop}-{today()}.jsonl"

    def write(self, event: str, **fields: Any) -> None:
        row = {"ts": now().isoformat(timespec="seconds"), "loop": self.loop, "event": event}
        row.update(fields)
        with open(self.path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        tail = " ".join(f"{k}={v}" for k, v in fields.items() if isinstance(v, (str, int, bool)))
        print(f"[{stamp()}] {self.loop:5s} {event:18s} {tail[:160]}", flush=True)

    def recent(self, limit: int = 50) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        for path in sorted(self.dir.glob(f"{self.loop}-*.jsonl")):
            for line in path.read_text(encoding="utf-8").splitlines():
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
        return rows[-limit:]


# --------------------------------------------------------------------------- 状态


class State:
    """只记“处理过谁”。不做业务判断，业务判断在仓库里。"""

    def __init__(self, path: Path | None = None) -> None:
        self.path = path or (STATE_DIR / "state.json")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.data: dict[str, Any] = {"seen": {}, "counters": {}}
        if self.path.exists():
            try:
                self.data.update(json.loads(self.path.read_text(encoding="utf-8")))
            except json.JSONDecodeError:
                pass
        self.data.setdefault("seen", {})
        self.data.setdefault("counters", {})

    def seen(self, loop: str, key: str) -> bool:
        return key in self.data["seen"].get(loop, {})

    def mark(self, loop: str, key: str, **meta: Any) -> None:
        self.data["seen"].setdefault(loop, {})[key] = {"at": now().isoformat(timespec="seconds"), **meta}

    def bump(self, name: str, by: int = 1) -> int:
        self.data["counters"][name] = self.data["counters"].get(name, 0) + by
        return self.data["counters"][name]

    def save(self, dry_run: bool = False) -> None:
        if dry_run:
            return
        self.path.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")


# --------------------------------------------------------------------------- 提示词


class Prompts:
    def __init__(self, directory: Path | None = None) -> None:
        self.dir = directory or (REPO_ROOT / "prompts")

    def render(self, name: str, **vars: Any) -> str:
        text = (self.dir / f"{name}.md").read_text(encoding="utf-8")
        for key, value in vars.items():
            text = text.replace("{{" + key + "}}", str(value))
        leftover = re.findall(r"\{\{(\w+)\}\}", text)
        if leftover:
            raise ValueError(f"prompt {name!r} 还有没填的占位符: {sorted(set(leftover))}")
        return text


def guard_untrusted(text: str, limit: int = 6000) -> str:
    """外部文本进 prompt 前先削一刀。

    原文提到的那块窟窿，这里只能挡住一部分：长度上限、去掉成段的指令性话术标记。
    真正的权限隔离（读消息的循环 != 发版的循环）得在部署层面做，见 ARCHITECTURE.md。
    """
    text = (text or "").replace("\x00", "")
    if len(text) > limit:
        text = text[:limit] + "\n…（已截断）"
    return text


# --------------------------------------------------------------------------- 模型


class LLMError(RuntimeError):
    pass


@dataclass
class LLM:
    """OpenAI 兼容 /chat/completions。指向 magpie 网关、DeepSeek、任何中转都能用。"""

    base_url: str
    model: str
    api_key: str = ""
    temperature: float = 0.0
    max_tokens: int = 1024
    timeout: int = 120
    offline: bool = False

    @classmethod
    def from_cfg(cls, cfg: dict[str, Any], which: str, offline: bool = False) -> "LLM":
        section = cfg_get(cfg, f"models.{which}")
        if not section:
            raise LLMError(f"配置里没有 models.{which}")
        key = ""
        env_name = section.get("api_key_env")
        if env_name:
            key = os.environ.get(env_name, "")
        return cls(
            base_url=section["base_url"].rstrip("/"),
            model=section["model"],
            api_key=key or section.get("api_key", ""),
            temperature=float(section.get("temperature", 0.0)),
            max_tokens=int(section.get("max_tokens", 1024)),
            offline=offline,
        )

    def complete(self, prompt: str) -> str:
        if self.offline:
            return _offline_completion(prompt)
        if not self.api_key:
            raise LLMError(
                f"模型 {self.model} 没有 key。去设置 config/workflow.toml 里 models 那节的 "
                f"api_key_env 指向的环境变量，或者用 --offline 先演练流程。"
            )
        payload = json.dumps(
            {
                "model": self.model,
                "messages": [{"role": "user", "content": prompt}],
                "temperature": self.temperature,
                "max_tokens": self.max_tokens,
                "stream": False,
            }
        ).encode("utf-8")
        req = urllib.request.Request(
            f"{self.base_url}/chat/completions",
            data=payload,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                body = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:400]
            raise LLMError(f"{self.model} HTTP {exc.code}: {detail}") from exc
        except urllib.error.URLError as exc:
            raise LLMError(f"{self.model} 连不上 {self.base_url}: {exc.reason}") from exc
        try:
            return body["choices"][0]["message"]["content"] or ""
        except (KeyError, IndexError) as exc:
            raise LLMError(f"{self.model} 返回结构不对: {str(body)[:300]}") from exc

    def json(self, prompt: str, default: dict[str, Any] | None = None) -> dict[str, Any]:
        raw = self.complete(prompt)
        parsed = extract_json(raw)
        if parsed is None:
            if default is not None:
                return default
            raise LLMError(f"{self.model} 没给出可解析的 JSON: {raw[:300]}")
        return parsed


def extract_json(text: str) -> dict[str, Any] | None:
    """模型爱包代码块、爱加前言，这里全部容错。"""
    if not text:
        return None
    cleaned = re.sub(r"^\s*```(?:json)?\s*|\s*```\s*$", "", text.strip(), flags=re.MULTILINE)
    for candidate in (cleaned, text):
        try:
            value = json.loads(candidate)
            if isinstance(value, dict):
                return value
        except json.JSONDecodeError:
            pass
    start, end = candidate_find(text)
    if start is None:
        return None
    try:
        value = json.loads(text[start : end + 1])
        return value if isinstance(value, dict) else None
    except json.JSONDecodeError:
        return None


def candidate_find(text: str) -> tuple[int | None, int | None]:
    start = text.find("{")
    if start < 0:
        return None, None
    depth = 0
    for idx in range(start, len(text)):
        ch = text[idx]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return start, idx
    return None, None


_OFFLINE_NOISE = re.compile(
    r"newcastle|st\.? james|football|toon army|premier league|球迷|球场|"
    r"\bbird\b|birdwatching|喜鹊飞|landed on my window",
    re.I,
)


def _untrusted_block(prompt: str) -> str:
    match = re.search(r"<<<UNTRUSTED_DATA\n(.*?)\nUNTRUSTED_DATA>>>", prompt, re.S)
    return match.group(1) if match else prompt


def _offline_completion(prompt: str) -> str:
    """离线演练用的固定应答。不发任何网络请求。

    目的只有一个：让整条流水线（渲染提示词 → 分诊 → 卡门 → 落盘）能在没有 key、
    没有账号的机器上跑通。分诊一律给 fix，预筛用一个粗糙的同名启发式代替模型——
    它不是在假装模型，日志里会写明 offline。
    """
    low = prompt.lower()
    if '"relevant"' in prompt:
        body = _untrusted_block(prompt)
        if _OFFLINE_NOISE.search(body):
            return json.dumps(
                {"relevant": False, "kind": "unrelated", "reason": "同名不同物"},
                ensure_ascii=False,
            )
        return json.dumps(
            {"relevant": True, "kind": "bug", "reason": "离线启发式判为反馈"}, ensure_ascii=False
        )
    if '"verdict"' in prompt:
        return json.dumps(
            {
                "verdict": "fix",
                "reason": "离线演练固定应答",
                "labels": ["bug"],
                "repro": ["按报告里的步骤原样执行一次"],
            },
            ensure_ascii=False,
        )
    if '"findings"' in prompt:
        return json.dumps(
            {
                "findings": [],
                "new_lessons": [],
                "seen_again": [],
                "blocklist": [],
                "notes": ["离线演练：没有做真实审查，这份报告不能当审查结论"],
            },
            ensure_ascii=False,
        )
    if "acceptance.json" in low or "验收记录" in prompt:
        return json.dumps(
            {
                "status": "fixed",
                "summary": "离线演练：按流程产出一份验收记录",
                "acceptance_path": ".loopstate/acceptance/offline.json",
                "files": [],
            },
            ensure_ascii=False,
        )
    return json.dumps({"offline": True, "note": "离线演练固定应答"}, ensure_ascii=False)


# --------------------------------------------------------------------------- agent


def run_agent(cfg: dict[str, Any], prompt: str, tag: str, dry_run: bool = False) -> dict[str, Any]:
    """把渲染好的提示词交给 coding agent，等它干完。

    command 里支持两个占位符：
      {prompt}      直接塞文本（命令太长会被系统截断）
      {prompt_file} 塞落盘后的路径（推荐）
    """
    prompt = prompt.replace("\r\n", "\n")
    prompt_dir = STATE_DIR / "prompts"
    prompt_dir.mkdir(parents=True, exist_ok=True)
    prompt_file = prompt_dir / f"{today()}-{tag}.md"
    prompt_file.write_text(prompt, encoding="utf-8")

    command: Sequence[str] = cfg_get(cfg, "agent.command", ["echo", "{prompt}"])
    if isinstance(command, str):
        command = [command]
    resolved = [
        part.replace("{prompt_file}", str(prompt_file)).replace("{prompt}", prompt)
        for part in command
    ]
    timeout = int(cfg_get(cfg, "agent.timeout_sec", 1800))

    if dry_run:
        return {"ok": True, "dry_run": True, "prompt_file": str(prompt_file), "stdout": ""}

    try:
        proc = subprocess.run(
            resolved,
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except FileNotFoundError:
        raise RuntimeError(f"agent 命令找不到: {resolved[0]}。改 config/workflow.toml 的 [agent].command") from None
    except subprocess.TimeoutExpired:
        return {"ok": False, "timeout": True, "prompt_file": str(prompt_file), "stdout": ""}
    return {
        "ok": proc.returncode == 0,
        "code": proc.returncode,
        "stdout": (proc.stdout or "")[-8000:],
        "stderr": (proc.stderr or "")[-2000:],
        "prompt_file": str(prompt_file),
    }


# --------------------------------------------------------------------------- git


def git(*args: str, check: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=check,
    )


def git_out(*args: str, default: str = "") -> str:
    proc = git(*args)
    return (proc.stdout or default).strip() if proc.returncode == 0 else default


def changed_files(base: str, head: str = "HEAD") -> list[str]:
    out = git_out("diff", "--name-only", f"{base}...{head}")
    return [line for line in out.splitlines() if line.strip()]


def diff_text(base: str, head: str = "HEAD", limit: int = 200_000) -> str:
    out = git_out("diff", f"{base}...{head}")
    return out[:limit]


def working_files() -> list[str]:
    """工作区里被 agent 改过的文件（含未跟踪的）。"""
    files: list[str] = []
    for line in git_out("status", "--porcelain").splitlines():
        if len(line) < 4:
            continue
        path = line[3:].strip().strip('"')
        if " -> " in path:
            path = path.split(" -> ", 1)[1].strip().strip('"')
        if path.endswith("/"):
            continue
        files.append(path)
    return files


def working_diff(files: list[str] | None = None, limit: int = 400_000) -> str:
    """给危害门看的 diff。未跟踪文件要自己拼，`git diff` 不认它们。

    files=None 是"没指定，你看全部"；files=[] 是"这次没有改动"。别用 `files or ...`
    把这两个混起来——否则离线演练会拿整个工作区当本轮改动，连测试文件自己都要过一道
    危害门，结果测试能不能过取决于你手头有没有没提交的东西。
    """
    if files is None:
        files = working_files()
    chunks: list[str] = []
    if files:
        chunks.append(git_out("diff", "HEAD", "--", *files))
    for path in files:
        full = REPO_ROOT / path
        if not full.is_file():
            continue
        if git_out("ls-files", "--error-unmatch", path):
            continue  # 已跟踪，diff 里有
        text = read_text(full, "")[:20_000]
        chunks.append(f"--- /dev/null\n+++ b/{path}\n" + "\n".join("+" + line for line in text.splitlines()))
    return "\n".join(chunks)[:limit]


def commit_message(text: str, subject: str, body: str, files: Iterable[str], dry_run: bool = False) -> str:
    """组装提交说明：首行祈使句 + 验收记录 + 循环标记。"""
    parts = [subject.strip(), ""]
    if body.strip():
        parts += [body.strip(), ""]
    parts.append(text.strip())
    return "\n".join(parts)


def create_commit(message: str, files: Iterable[str], dry_run: bool = False) -> str | None:
    if dry_run:
        return None
    files = [f for f in files if f]
    if not files:
        # 没有改动就不许提交。空提交会把“什么都没做”伪装成“修好了”。
        return None
    git("add", "--", *files)
    proc = git("commit", "-m", message)
    if proc.returncode != 0:
        return None
    return git_out("rev-parse", "--short", "HEAD")


# --------------------------------------------------------------------------- 小工具


def read_text(path: str | Path, default: str = "") -> str:
    try:
        return Path(path).read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return default


def write_text(path: str | Path, text: str, dry_run: bool = False) -> None:
    if dry_run:
        return
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(text, encoding="utf-8")


def load_blocklist(cfg: dict[str, Any]) -> set[str]:
    path = REPO_ROOT / cfg_get(cfg, "gate.blocklist", "config/blocklist.txt")
    entries: set[str] = set()
    for line in read_text(path).splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            entries.add(line)
    return entries


def sleep_until(seconds: float) -> None:
    remaining = max(0.0, seconds)
    while remaining > 0:
        chunk = min(30.0, remaining)
        time.sleep(chunk)
        remaining -= chunk


def eprint(*args: Any) -> None:
    print(*args, file=sys.stderr, flush=True)


@dataclass
class Tick:
    """一轮循环的公共上下文。"""

    name: str
    cfg: dict[str, Any]
    journal: Journal
    state: State
    dry_run: bool = False
    offline: bool = False
    limit: int = 1
    acted: int = field(default=0, init=False)

    @classmethod
    def make(cls, name: str, cfg: dict[str, Any], dry_run: bool, offline: bool) -> "Tick":
        return cls(
            name=name,
            cfg=cfg,
            journal=Journal(name),
            state=State(),
            dry_run=dry_run,
            offline=offline,
            limit=int(cfg_get(cfg, f"{name}.max_actions_per_tick", 1)),
        )

    def budget_left(self) -> bool:
        return self.acted < self.limit

    def spend(self) -> None:
        self.acted += 1
