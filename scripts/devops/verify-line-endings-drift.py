#!/usr/bin/env python3
"""check-line-endings 门禁的「判据有效性」A/B 验证（2026-09-18）。

为什么必须有这个脚本
--------------------
「实跑通过 ≠ 判据有效」。只会打 OK 的门禁等于一条 `exit 0`。本脚本**真注入漂移**，
证明每条分支都会红；同时用 `expect_red=False` 的反向用例证明它**不会乱红**
（假红与假绿同害）。任何情况下都会还原被改动的文件。

沿用同目录先例（`verify-ops-alert-channels-drift.py`、`verify-checkin-gate-drift.py`）的形态。

覆盖的分支（与 check-line-endings.mjs 的 fail() / 违规汇总一一对应）
--------------------------------------------------------------------
  主判据  1. 一个受管文件磁盘改 CRLF                 → 「磁盘是 CRLF」
          2. 两个受管文件磁盘改 CRLF                 → 两个路径都要被列出
          3. 索引 blob 改 CRLF（git --cacheinfo）    → 「索引是 CRLF」
  修复    4. 磁盘 CRLF 后跑 `--fix`                  → 退出 0 且磁盘真的回到 LF
  守卫    5. .gitattributes 去掉 `eol=lf` 规则        → 「失去判别力」（防「一条都没扫到」）
          6. MIN_SCANNED 抬到 999999                 → 「输出格式可能已变」（锚点守卫）
          7. MIN_LF_MANAGED 抬到 999999              → 「失去判别力」（锚点守卫）
  反向    8. 受管文件加一行注释（保持 LF）            → **必须仍绿**（不绑「文件没被动过」）
          9. **非受管**路径（.java）改 CRLF          → **必须仍绿**（不许越界管辖）
  —— 注意第 9 例不是凑数：本机 `core.autocrlf=true`，`services/**` 的 .java 在磁盘上
  **本来就是 CRLF**，若门禁按「磁盘是 CRLF 就红」判，会在几百个正常文件上恒红。
        11. 新建**未跟踪**文件即 CRLF                  → **必须红**（`??` 不是 ` M`，最容易漏）
 10. 全部还原后                                      → 必须回绿（防「永久红」被当有效）

用法
----
    python scripts/devops/verify-line-endings-drift.py

⚠️ **不要与聚合门禁链并发跑**：本脚本按设计会**真注入 CRLF / 改索引 / 改 .gitattributes**，
若此刻 `check:audit-gates` 正在跑，`check:line-endings`（链内第 2 位）会拍到注入中的状态而红 ——
那是自己造出来的假红（2026-09-18 实测踩到过一次：并发跑的聚合链报「失败 1 个」，
隔离重跑即 0 个）。串行执行。

退出码 0 = 全部符合预期；1 = 有分支未按预期表现（判据可疑）。
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from shutil import which

ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / "scripts" / "check-line-endings.mjs"
GITATTRIBUTES = ROOT / ".gitattributes"

# 受管（.gitattributes 声明 eol=lf）且当前是纯 LF 的文件
MANAGED_A = ROOT / "clients" / "consumer-mp" / "README.md"
MANAGED_B = ROOT / "packages" / "shared-dict" / "tsconfig.json"
MANAGED_C = ROOT / "clients" / "admin-vue" / "src" / "config" / "feature-flags.ts"

# 非受管路径：attr 为空，磁盘本来就是 CRLF（autocrlf=true 的常态）
UNMANAGED = (
    ROOT
    / "services"
    / "trade-service"
    / "src"
    / "main"
    / "java"
    / "com"
    / "aicabinet"
    / "trade"
    / "service"
    / "SystemConfigService.java"
)


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


def run_gate(*extra: str) -> tuple[int, str]:
    proc = subprocess.run(
        [NODE, str(GATE), *extra],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def git(*args: str) -> str:
    proc = subprocess.run(
        ["git", *args],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if proc.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {proc.stderr}")
    return proc.stdout


# ---------- 注入器：每个都返回一个「还原」闭包 ----------


def inject_crlf_on_disk(*paths: Path):
    saved = {p: p.read_bytes() for p in paths}

    def restore() -> None:
        for p, data in saved.items():
            p.write_bytes(data)

    for p, data in saved.items():
        assert b"\r\n" not in data, f"{p} 本来就是 CRLF，本例前提不成立"
        p.write_bytes(data.replace(b"\n", b"\r\n"))
    return restore


def inject_crlf_in_index(path: Path):
    orig = git("ls-files", "-s", "--", str(path.relative_to(ROOT))).split()[1]
    rel = str(path.relative_to(ROOT)).replace("\\", "/")
    crlf_blob = subprocess.run(
        ["git", "hash-object", "-w", "--stdin", "--no-filters"],
        cwd=str(ROOT),
        input=b"probe\r\n",
        capture_output=True,
    ).stdout.decode().strip()
    git("update-index", "--cacheinfo", f"100644,{crlf_blob},{rel}")

    def restore() -> None:
        git("update-index", "--cacheinfo", f"100644,{orig},{rel}")

    return restore


def inject_gitattributes_without_eol():
    saved = GITATTRIBUTES.read_bytes()

    def restore() -> None:
        GITATTRIBUTES.write_bytes(saved)

    assert b"text=auto eol=lf" in saved
    GITATTRIBUTES.write_bytes(saved.replace(b"text=auto eol=lf", b"text=auto"))
    return restore


def inject_gate_constant(name: str, value: str):
    saved = GATE.read_bytes()
    needle = f"const {name} =".encode()
    assert needle in saved, f"门禁里找不到 `const {name} =`"

    def restore() -> None:
        GATE.write_bytes(saved)

    lines = saved.split(b"\n")
    for i, line in enumerate(lines):
        if line.startswith(needle):
            lines[i] = f"const {name} = {value};".encode()
            break
    GATE.write_bytes(b"\n".join(lines))
    return restore


def inject_extra_lf_line(path: Path):
    saved = path.read_bytes()

    def restore() -> None:
        path.write_bytes(saved)

    path.write_bytes(saved + b"<!-- drift-probe: harmless LF line -->\n")
    return restore


def inject_untracked_crlf_file():
    """新建**未跟踪**文件、且一落盘就是 CRLF —— 这一幕在 git status 里是 `??`，最容易漏。"""
    probe = ROOT / "clients" / "admin-vue" / "src" / "config" / "__eol_drift_probe.ts"
    assert not probe.exists(), f"{probe} 已存在，本例前提不成立"
    probe.write_bytes(b"export const probeA = 1;\r\nexport const probeB = 2;\r\n")

    def restore() -> None:
        probe.unlink(missing_ok=True)

    return restore


class Case:
    def __init__(self, name: str, expect_red: bool, marker: str, mutate) -> None:
        self.name = name
        self.expect_red = expect_red
        self.marker = marker
        self.mutate = mutate

    def run(self) -> bool:
        restore = self.mutate()
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
        if ok is False and self.expect_red and self.marker not in out:
            detail += f"（未出现标记 {self.marker!r}）"
        print(f"  {mark} {self.name} [{want}]{detail}")
        if not ok:
            tail = out.strip().splitlines()[-1][:200] if out.strip() else "(无输出)"
            print(f"      gate 输出尾部：{tail}")
        return ok


CASES = [
    Case(
        "1 一个受管文件磁盘改 CRLF",
        True,
        "磁盘是 CRLF",
        lambda: inject_crlf_on_disk(MANAGED_A),
    ),
    Case(
        "2 两个受管文件磁盘改 CRLF（都要被列出）",
        True,
        "tsconfig.json",
        lambda: inject_crlf_on_disk(MANAGED_A, MANAGED_B),
    ),
    Case(
        "3 索引 blob 改 CRLF",
        True,
        "索引是 CRLF",
        lambda: inject_crlf_in_index(MANAGED_C),
    ),
    Case(
        "5 .gitattributes 去掉 eol=lf → 不再受管",
        True,
        "失去判别力",
        inject_gitattributes_without_eol,
    ),
    Case(
        "6 MIN_SCANNED 抬到 999999（锚点守卫）",
        True,
        "输出格式可能已变",
        lambda: inject_gate_constant("MIN_SCANNED", "999999"),
    ),
    Case(
        "7 MIN_LF_MANAGED 抬到 999999（锚点守卫）",
        True,
        "失去判别力",
        lambda: inject_gate_constant("MIN_LF_MANAGED", "999999"),
    ),
    Case(
        "8 受管文件加一行注释（保持 LF）",
        False,
        "",
        lambda: inject_extra_lf_line(MANAGED_A),
    ),
    Case(
        "9 非受管 .java 改 CRLF（不许越界管辖）",
        False,
        "",
        lambda: inject_crlf_on_disk(UNMANAGED),
    ),
    Case(
        "11 新建未跟踪文件即 CRLF（git status 只显示 ??）",
        True,
        "磁盘是 CRLF",
        inject_untracked_crlf_file,
    ),
]


def main() -> int:
    code, out = run_gate()
    if code != 0:
        print("前置检查失败：干净工作区下门禁本应绿，先修好再谈判据有效性")
        print(out)
        return 1
    print("前置：干净状态下门禁为绿 ✓\n")

    passed = all(c.run() for c in CASES)

    # 案例 4：--fix 修复路径（单独跑，因为它期望绿而不是红）
    restore = inject_crlf_on_disk(MANAGED_A)
    try:
        fix_code, fix_out = run_gate("--fix")
        on_disk = MANAGED_A.read_bytes()
        fixed_ok = fix_code == 0 and b"\r\n" not in on_disk
        print(
            f"  {'✓' if fixed_ok else '✗'} 4 `--fix` 把磁盘改回 LF"
            f" [期望 exit 0 + 无 CRLF]{'' if fixed_ok else '  ← 未按预期'}"
        )
        if not fixed_ok:
            remaining = on_disk.count(b"\r\n")
            print(f"      exit={fix_code} 剩余 CRLF={remaining}")
            if fix_out.strip():
                print("      gate 输出尾部：" + fix_out.strip().splitlines()[-1][:200])
        passed = passed and fixed_ok
    finally:
        restore()

    print()
    code, out = run_gate()
    back_green = code == 0
    print(f"  {'✓' if back_green else '✗'} 10 全部还原后回绿 [期望 GREEN]")
    if not back_green:
        print(out)
    passed = passed and back_green

    print(f"\n{'全部符合预期' if passed else '存在未按预期表现的分支'}")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
