#!/usr/bin/env python3
"""check-miniapp-privacy-declaration 门禁的「判据有效性」A/B 验证（2026-09-18）。

为什么必须有这个脚本
--------------------
「实跑通过 ≠ 判据有效」。只会打 OK 的门禁等于一条 `exit 0`；必须**真注入漂移**证明它
每条分支都会红。沿用同目录先例（`verify-edge-threading-gate-drift.py`、
`verify-checkin-gate-drift.py`）的形态：逐例注入 → 跑门禁 → 断言退出码与报错文案 →
还原 → 复跑必须回绿。

覆盖的分支（与 check-miniapp-privacy-declaration.mjs 的 fail() 一一对应）
------------------------------------------------------------------------
  1. 删掉 consumer-mp 的 `requiredPrivateInfos` 字段   → 「声明缺接口」
  2. `requiredPrivateInfos` 改成空数组 `[]`            → 空数组同样算未声明
  3. 删掉整个 `permission` 段                          → 「授权弹窗文案缺失」
  4. `permission.scope.userLocation.desc` 改成空串      → 空串同样算缺失
  5. `MIN_LOCATION_CALLS` 抬到 99                       → 「API 正则失效 ⇒ 恒绿」守卫
  6. `MIN_APPS` 抬到 99                                 → 「目录锚点失效」守卫
  7. 全部还原后                                         → 必须回绿（反向守卫，防「永久红」被当有效）

用法
----
    python scripts/devops/verify-miniapp-privacy-drift.py

退出码 0 = 全部符合预期；1 = 有分支未按预期表现（判据可疑）。任何情况下都会还原文件。
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from shutil import which

ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / "scripts" / "check-miniapp-privacy-declaration.mjs"
CONSUMER_MANIFEST = ROOT / "clients" / "consumer-mp" / "src" / "manifest.json"

DESC = "用于按当前位置为你推荐附近柜机"
REQUIRED_LINE = b'    "requiredPrivateInfos": ["getLocation"]\n'
PERMISSION_BLOCK = (
    b'    "permission": {\n'
    b'      "scope.userLocation": {\n'
    + f'        "desc": "{DESC}"\n'.encode("utf-8")
    + b"      }\n"
    b"    },\n"
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


def read(path: Path) -> bytes:
    return path.read_bytes()


def write(path: Path, data: bytes) -> None:
    """按字节写回：避免 Python 文本模式把 LF 改成 CRLF（本仓踩过这个坑）。"""
    path.write_bytes(data)


class Case:
    def __init__(self, name: str, expect_red: bool, marker: str, mutate) -> None:
        self.name = name
        self.expect_red = expect_red
        self.marker = marker
        self.mutate = mutate


def replace_tokens(path: Path, pairs: list[tuple[bytes, bytes]]):
    def _mutate() -> None:
        data = read(path)
        for old, new in pairs:
            if old not in data:
                raise AssertionError(
                    f"{path.name} 里找不到待替换片段 {old!r} —— 门禁锚点已漂移，请同步本脚本"
                )
            data = data.replace(old, new)
        write(path, data)

    return _mutate


CASES = [
    Case(
        "删掉 requiredPrivateInfos 字段",
        True,
        "未声明实际调用的隐私接口",
        replace_tokens(CONSUMER_MANIFEST, [(b"    },\n" + REQUIRED_LINE, b"    }\n")]),
    ),
    Case(
        "requiredPrivateInfos 改空数组",
        True,
        "未声明实际调用的隐私接口",
        replace_tokens(CONSUMER_MANIFEST, [(REQUIRED_LINE, b'    "requiredPrivateInfos": []\n')]),
    ),
    Case(
        "删掉 permission 段",
        True,
        "desc 缺失或为空",
        replace_tokens(CONSUMER_MANIFEST, [(PERMISSION_BLOCK, b"")]),
    ),
    Case(
        "desc 改空串",
        True,
        "desc 缺失或为空",
        replace_tokens(
            CONSUMER_MANIFEST,
            [(f'"desc": "{DESC}"'.encode("utf-8"), b'"desc": ""')],
        ),
    ),
    Case(
        "MIN_LOCATION_CALLS 抬到 99",
        True,
        "处「需声明」的隐私接口调用",
        replace_tokens(GATE, [(b"const MIN_LOCATION_CALLS = 1;", b"const MIN_LOCATION_CALLS = 99;")]),
    ),
    Case(
        "MIN_APPS 抬到 99",
        True,
        "含 src/manifest.json 的小程序",
        replace_tokens(GATE, [(b"const MIN_APPS = 2;", b"const MIN_APPS = 99;")]),
    ),
    Case("全部还原后回绿", False, "", lambda: None),
]

TARGETS = [CONSUMER_MANIFEST, GATE]


def main() -> int:
    originals = {path: read(path) for path in TARGETS}
    results: list[tuple[str, bool, str]] = []
    try:
        code, out = run_gate()
        print(f"[baseline] exit={code} {'OK' if code == 0 else 'RED（改造前基线就不绿，后续结论不可信）'}")

        for case in CASES:
            for path, data in originals.items():
                write(path, data)
            case.mutate()

            code, out = run_gate()
            went_red = code != 0
            marker_ok = (case.marker in out) if case.expect_red else True
            passed = (went_red == case.expect_red) and marker_ok

            detail = f"exit={code}"
            if case.expect_red and not marker_ok:
                detail += f"（报错文案里未出现 {case.marker!r}）"
            results.append((case.name, passed, detail))
            print(f"[{'PASS' if passed else 'FAIL'}] {case.name}: {detail}")
    finally:
        for path, data in originals.items():
            write(path, data)

    restored = all(read(path) == originals[path] for path in TARGETS)
    code, _ = run_gate()
    print(f"[restore] 字节级还原={'OK' if restored else '不一致'} 复跑 exit={code}")
    if not restored or code != 0:
        print("还原后未回绿 —— 仓库处于不一致状态，请 git diff 检查", file=sys.stderr)
        return 1

    failed = [name for name, ok, _ in results if not ok]
    print(f"\n汇总：{len(results) - len(failed)}/{len(results)} 例符合预期")
    if failed:
        print("未按预期表现：" + "、".join(failed), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
