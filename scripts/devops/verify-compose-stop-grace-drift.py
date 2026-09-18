#!/usr/bin/env python3
"""check-compose-stop-grace 门禁的「判据有效性」A/B 验证（2026-09-18）。

为什么必须有这个脚本
--------------------
「实跑通过 ≠ 判据有效」。只会打 OK 的门禁等于一条 `exit 0`。本脚本**真注入漂移**，
证明每条分支都会红；同时用 `expect_red=False` 的反向用例证明它**不会乱红**
（假红与假绿同害）。任何情况下都会还原被改动的文件。

沿用同目录先例（`verify-ops-alert-channels-drift.py`、`verify-line-endings-drift.py`）的形态。

覆盖的分支（与 check-compose-stop-grace.mjs 的 fail() / 违规汇总一一对应）
--------------------------------------------------------------------------
  主判据  1. 删掉某常驻服务的 stop_grace_period          → 「未声明 stop_grace_period」
          2. postgres 60s → 5s（落盘型低于下限）        → 「低于下限 60s」
          3. redis 30s → 5s（常驻型低于下限）           → 「低于下限 30s」
          4. minio-init 10s → 5s（一次性低于下限）      → 「低于下限 10s」
          5. 新增服务但不声明宽限                        → 「未声明 stop_grace_period」
  锚点守卫 6. `services:` 锚点被改名                       → 「一个 service 都没解析到」
          7. 全部文件里 postgres 被改名                  → 「分层表里的落盘型服务」
  —— 第 7 例不是凑数：分层规则**按名字**匹配（postgres 要 ≥60s），若有人把服务改名而不同步
  STATEFUL，规则会**静默失效**（那个服务从此只按 30s 判）。这一例证明守卫真的会响。
  反向    8. redis 30s → 45s                            → **必须仍绿**（判据只设下限，不钉死值）
          9. postgres 60s → `1m`（等价写法）             → **必须仍绿**（时长解析不许假红）
 10. 新增服务且声明 30s                                 → **必须仍绿**（合法扩充不拦）
 11. 全部还原后                                         → 必须回绿（防「永久红」被当有效）

用法
----
    python scripts/devops/verify-compose-stop-grace-drift.py

⚠️ **不要与聚合门禁链并发跑**：本脚本按设计会真改 `infra/docker-compose*.yml`，
若此刻 `check:audit-gates` 正在跑，链内的 `check-compose-stop-grace` 会拍到注入中的状态而红
——那是自己造出来的假红（同类坑 2026-09-18 已踩过一次）。串行执行。

退出码 0 = 全部符合预期；1 = 有分支未按预期表现（判据可疑）。
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from shutil import which

ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / "scripts" / "check-compose-stop-grace.mjs"
INFRA = ROOT / "infra"
FULL = INFRA / "docker-compose.full.yml"


def node_bin() -> str:
    found = which("node")
    if found:
        return found
    fallback = (
        Path.home() / ".workbuddy" / "binaries" / "node" / "versions" / "22.22.2-3" / "node.exe"
    )
    if fallback.exists():
        return str(fallback)
    print("找不到 node，无法运行门禁", file=sys.stderr)
    sys.exit(2)


NODE = node_bin()


def compose_files() -> list[Path]:
    return sorted(p for p in INFRA.glob("docker-compose*.yml"))


def run_gate() -> tuple[int, str]:
    proc = subprocess.run(
        [NODE, str(GATE)],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


# ---------- 注入器：每个都返回一个「还原」闭包 ----------


def _blocks(lines: list[bytes]):
    """产出 (service_name, 起始行号, 结束行号)。service 键 2 空格缩进。"""
    idx = [
        (i, ln)
        for i, ln in enumerate(lines)
        if ln.startswith(b"  ") and not ln.startswith(b"   ") and ln.rstrip().endswith(b":")
    ]
    for n, (i, ln) in enumerate(idx):
        end = idx[n + 1][0] if n + 1 < len(idx) else len(lines)
        yield ln.strip()[:-1].decode(), i, end


def _crlf_suffix(data: bytes) -> bytes:
    """`infra/**` 里 CRLF 与 LF 两种文件混存，注入时必须沿用该文件自己的行尾。

    第一版没做这件事：`services:` 这类**精确匹配**的锚点直接 miss（CRLF 文件的每行尾部带 \\r），
    而改值那一路写出的又是不带 \\r 的行、把文件搞成混杂行尾 —— 虽然 finally 会还原，
    但“验证脚本自己会写坏文件”不该留在库里。
    """
    return b"\r" if b"\r\n" in data else b""


def _find_line(lines: list[bytes], text: str) -> int:
    want = text.encode()
    for i, ln in enumerate(lines):
        if ln.rstrip() == want:
            return i
    return -1


def inject_drop_key(path: Path, service: str):
    saved = path.read_bytes()
    lines: list[bytes | None] = list(saved.split(b"\n"))

    def restore() -> None:
        path.write_bytes(saved)

    dropped = False
    for name, start, end in _blocks([ln for ln in lines if ln is not None]):
        if name != service:
            continue
        for i in range(start, end):
            if lines[i] is not None and lines[i].startswith(b"    stop_grace_period:"):
                lines[i] = None
                dropped = True
    assert dropped, f"{path} 的 {service} 里没找到 stop_grace_period"
    path.write_bytes(b"\n".join(ln for ln in lines if ln is not None))
    return restore


def inject_set_value(path: Path, service: str, new_value: str):
    saved = path.read_bytes()
    lines = saved.split(b"\n")
    sfx = _crlf_suffix(saved)

    def restore() -> None:
        path.write_bytes(saved)

    changed = False
    for name, start, end in _blocks(list(lines)):
        if name != service:
            continue
        for i in range(start, end):
            if lines[i].startswith(b"    stop_grace_period:"):
                lines[i] = f"    stop_grace_period: {new_value}".encode() + sfx
                changed = True
    assert changed, f"{path} 的 {service} 里没找到 stop_grace_period"
    path.write_bytes(b"\n".join(lines))
    return restore


def inject_add_service(path: Path, name: str, with_key: bool):
    saved = path.read_bytes()
    lines = saved.split(b"\n")
    sfx = _crlf_suffix(saved)

    def restore() -> None:
        path.write_bytes(saved)

    i = _find_line(lines, "services:")
    assert i >= 0, f"{path} 里找不到 services: 锚点"
    block = [f"  {name}:".encode() + sfx, b"    image: busybox:latest" + sfx]
    if with_key:
        block.append(b"    stop_grace_period: 30s" + sfx)
    lines[i + 1 : i + 1] = block
    path.write_bytes(b"\n".join(lines))
    return restore


def inject_rename_anchor(path: Path):
    saved = path.read_bytes()
    lines = saved.split(b"\n")
    sfx = _crlf_suffix(saved)

    def restore() -> None:
        path.write_bytes(saved)

    i = _find_line(lines, "services:")
    assert i >= 0, f"{path} 里找不到顶层 services:"
    lines[i] = b"servicesRenamed:" + sfx
    path.write_bytes(b"\n".join(lines))
    return restore


def inject_rename_service(old: str, new: str):
    saved = {p: p.read_bytes() for p in compose_files()}
    hit = 0
    for p, b in saved.items():
        lines = b.split(b"\n")
        i = _find_line(lines, f"  {old}:")
        if i >= 0:
            lines[i] = f"  {new}:".encode() + _crlf_suffix(b)
            p.write_bytes(b"\n".join(lines))
            hit += 1
    assert hit, f"没有任何 compose 文件里出现 `  {old}:`"

    def restore() -> None:
        for p, b in saved.items():
            p.write_bytes(b)

    return restore


class Case:
    def __init__(self, name: str, expect_red: bool, marker: str, mutate) -> None:
        self.name = name
        self.expect_red = expect_red
        self.marker = marker
        self.mutate = mutate

    def run(self) -> bool:
        try:
            restore = self.mutate()
        except Exception as e:  # 注入本身失败也要报出来，不能整脚本崩掉掩盖其它用例
            print(f"  ✗ {self.name} [注入失败]  ← {type(e).__name__}: {e}")
            return False
        try:
            code, out = run_gate()
        finally:
            restore()
        red = code != 0
        ok = red == self.expect_red
        if ok and self.expect_red:
            ok = self.marker in out
        mark = "✓" if ok else "✗"
        want = "RED" if self.expect_red else "GREEN"
        got = "RED" if red else "GREEN"
        detail = "" if ok else f"  ← 期望 {want}，实得 {got}"
        if not ok and self.expect_red and self.marker not in out:
            detail += f"（未出现标记 {self.marker!r}）"
        print(f"  {mark} {self.name} [{want}]{detail}")
        if not ok:
            tail = out.strip().splitlines()[-1][:200] if out.strip() else "(无输出)"
            print(f"      gate 输出尾部：{tail}")
        return ok


CASES = [
    Case(
        "1 删掉 trade-service 的 stop_grace_period",
        True,
        "未声明 stop_grace_period",
        lambda: inject_drop_key(FULL, "trade-service"),
    ),
    Case(
        "2 postgres 60s→5s（落盘型低于下限）",
        True,
        "低于下限 60s",
        lambda: inject_set_value(FULL, "postgres", "5s"),
    ),
    Case(
        "3 redis 30s→5s（常驻型低于下限）",
        True,
        "低于下限 30s",
        lambda: inject_set_value(FULL, "redis", "5s"),
    ),
    Case(
        "4 minio-init 10s→5s（一次性低于下限）",
        True,
        "低于下限 10s",
        lambda: inject_set_value(FULL, "minio-init", "5s"),
    ),
    Case(
        "5 新增服务但不声明宽限",
        True,
        "未声明 stop_grace_period",
        lambda: inject_add_service(FULL, "probe-svc-nokey", with_key=False),
    ),
    Case(
        "6 services: 锚点被改名（解析失效守卫）",
        True,
        "一个 service 都没解析到",
        lambda: inject_rename_anchor(FULL),
    ),
    Case(
        "7 全部文件里 postgres 改名（分层表脱节守卫）",
        True,
        "分层表里的落盘型服务",
        lambda: inject_rename_service("postgres", "pg-renamed"),
    ),
    Case(
        "8 redis 30s→45s（只设下限，不钉死值）",
        False,
        "",
        lambda: inject_set_value(FULL, "redis", "45s"),
    ),
    Case(
        "9 postgres 60s→1m（等价写法不许假红）",
        False,
        "",
        lambda: inject_set_value(FULL, "postgres", "1m"),
    ),
    Case(
        "10 新增服务且声明 30s（合法扩充）",
        False,
        "",
        lambda: inject_add_service(FULL, "probe-svc-ok", with_key=True),
    ),
]


def main() -> int:
    n = len(compose_files())
    if n < 8:
        print(f"前置检查失败：infra/docker-compose*.yml 只有 {n} 个，先修好再谈判据有效性")
        return 1

    code, out = run_gate()
    if code != 0:
        print("前置检查失败：干净工作区下门禁本应绿，先修好再谈判据有效性")
        print(out)
        return 1
    print(f"前置：{n} 个 compose 文件、干净状态下门禁为绿 ✓\n")

    passed = all(c.run() for c in CASES)

    print()
    code, out = run_gate()
    back_green = code == 0
    print(f"  {'✓' if back_green else '✗'} 11 全部还原后回绿 [期望 GREEN]")
    if not back_green:
        print(out)
    passed = passed and back_green

    print(f"\n{'全部符合预期' if passed else '存在未按预期表现的分支'}")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
