#!/usr/bin/env python3
"""check-edge-queue-threading 门禁的「判据有效性」A/B 验证（2026-09-18）。

为什么必须有这个脚本
--------------------
「实跑通过 ≠ 判据有效」。一个只会打 OK 的门禁和一条 `exit 0` 没有区别 —— 必须**真注入漂移**
证明它每条分支都会红。仓库里已有一份同类先例（`verify-checkin-gate-drift.py`，18 例），
本脚本沿用同一形态：逐例注入 → 跑门禁 → 断言退出码与报错文案 → 还原 → 复跑必须回绿。

覆盖的分支（与 check-edge-queue-threading.mjs 的 fail() 一一对应）
------------------------------------------------------------------
  1. `.commit()` 被换成 `.apply()`        → 「锁内同步落盘」前提失守
  2. 去掉 `@Synchronized`                  → 并发正确性前提失守
  3. Dispatcher 换成 `Dispatchers.Unconfined` → 「非主线程」依据解析不出
  4. Dispatcher 换成 `Dispatchers.Main`    → 落盘与阻塞上传上主线程
  5. 新增未声明的队列接触点                → 调用点清单漏项
  6. 接触点数量跌破阈值                    → 门禁自身失去判别力（解析锚点被重写）
  7. 全部还原后                            → 必须回绿（反向守卫；防「永久红」被当成有效）

第 8 条分支（清单条目已失效）不在此注入：它在门禁首次实跑时**已经被真实触发过**
（`OutboundMqttQueue.kt::size` 与 `OfflineUploadQueue.kt::enqueue` 两条），
即该分支的红色有实测记录，无需人造。

用法
----
    python scripts/devops/verify-edge-threading-gate-drift.py

退出码 0 = 全部符合预期；1 = 有分支未按预期表现（判据可疑）。任何情况下都会还原文件。
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from shutil import which

ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / "scripts" / "check-edge-queue-threading.mjs"
EDGE = ROOT / "edge" / "android-app" / "app" / "src" / "main" / "java" / "com" / "aicabinet" / "edge"
PREFS = EDGE / "queue" / "PrefsJsonQueue.kt"
SERVICE = EDGE / "service" / "CabinetService.kt"


def node_bin() -> str:
    found = which("node")
    if found:
        return found
    fallback = Path.home() / ".workbuddy" / "binaries" / "node" / "versions" / "22.22.2-3" / "node.exe"
    if fallback.exists():
        return str(fallback)
    print("找不到 node，无法运行门禁", file=sys.stderr)
    sys.exit(2)


NODE = node_bin()


def run_gate() -> tuple[int, str]:
    """跑门禁，返回 (退出码, stdout+stderr)。"""
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
    """构造一个「把 path 里某些字节序列替换掉」的 mutate 函数。"""

    def _mutate() -> None:
        data = read(path)
        for old, new in pairs:
            if old not in data:
                raise AssertionError(f"{path.name} 里找不到待替换片段 {old!r} —— 门禁锚点已漂移，请同步本脚本")
            data = data.replace(old, new)
        write(path, data)

    return _mutate


def append_probe() -> None:
    """往 CabinetService.kt 尾部追加一行「未声明的队列接触点」。"""
    data = read(SERVICE)
    write(SERVICE, data + b'\nfun driftProbe() { outboundQueue.enqueue("drift", ByteArray(0)) }\n')


CASES = [
    Case(
        "commit() → apply()",
        True,
        "找不到 `.commit()`",
        replace_tokens(PREFS, [(b".commit()", b".apply()")]),
    ),
    Case(
        "去掉 @Synchronized",
        True,
        "不再是 @Synchronized",
        replace_tokens(PREFS, [(b"@Synchronized", b"")]),
    ),
    Case(
        "Dispatcher → Unconfined",
        True,
        "解析不出 Dispatchers.Default|IO",
        replace_tokens(SERVICE, [(b"Dispatchers.Default", b"Dispatchers.Unconfined")]),
    ),
    Case(
        "Dispatcher → Main",
        True,
        "Dispatchers.Main",
        replace_tokens(SERVICE, [(b"Dispatchers.Default", b"Dispatchers.Main")]),
    ),
    Case(
        "新增未声明接触点",
        True,
        "未在本门禁清单中声明",
        append_probe,
    ),
    Case(
        "接触点数跌破阈值",
        True,
        "只解析出",
        replace_tokens(GATE, [(b"const MIN_CONTACT_POINTS = 8;", b"const MIN_CONTACT_POINTS = 99;")]),
    ),
    Case(
        "全部还原后回绿",
        False,
        "",
        lambda: None,
    ),
]

TARGETS = [PREFS, SERVICE, GATE]


def main() -> int:
    originals = {path: read(path) for path in TARGETS}
    results: list[tuple[str, bool, str]] = []
    try:
        code, out = run_gate()
        baseline_green = code == 0
        print(f"[baseline] exit={code} {'OK' if baseline_green else 'RED（改造前基线就不绿，后续结论不可信）'}")

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
