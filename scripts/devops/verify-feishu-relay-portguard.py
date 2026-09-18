#!/usr/bin/env python3
"""RelayProc 端口预检的负向对照（2026-09-18）。

为什么要有它
------------
`verify-feishu-relay-e2e.py` 的 Phase A 有四个用例各自起一个桥进程。它们**曾经共用同一个
宿主端口**，于是前一个进程刚 terminate、端口还没真正释放时，后一个实例就会失败成 `relay=0`。

那种红的危害不是"偶发"，而是**红得指错地方**：看起来像 `feishu-relay.py` 坏了，
实际是测试框架自己的竞态。修法是两条：① 逐用例独立端口；② 起桥前显式等端口空、停桥后等端口释放。

但"加了一行 if"本身不是证据 —— 必须证明它**真会在竞态发生时拦住**，否则就是恒真守卫。
本脚本就是那一枪：先占住端口，再起第二个实例，断言它被拦下、且报错文案把归因指向测试框架。

用法
----
    python scripts/devops/verify-feishu-relay-portguard.py

退出码 0 = 预检按预期生效；1 = 预检失效（第二个实例竟然起来了）或环境不干净。
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
E2E = ROOT / "scripts" / "devops" / "verify-feishu-relay-e2e.py"

# 专供本对照使用，避开正式用例的端口（18091–18095 / 18097–18099）。
PORT = 18088


def load_e2e():
    spec = importlib.util.spec_from_file_location("feishu_relay_e2e", E2E)
    if spec is None or spec.loader is None:
        print(f"无法加载 {E2E}", file=sys.stderr)
        sys.exit(2)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    m = load_e2e()
    sink = m.start_sink()
    verdict = 0
    try:
        if m.port_in_use(PORT):
            print(f"[FAIL] 对照开始前 {PORT} 就已被占用，环境不干净，结论无效")
            return 1

        first = m.RelayProc({"FEISHU_WEBHOOK_URL": ""}, PORT)
        print(f"[ok] 第一个实例已起：port_in_use={m.port_in_use(PORT)} url={first.url}")

        try:
            second = m.RelayProc({"FEISHU_WEBHOOK_URL": ""}, PORT)
            print("[FAIL] 第二个实例竟然也起来了 ⇒ 端口预检没生效（守卫恒真/失效）")
            second.stop()
            verdict = 1
        except RuntimeError as exc:
            message = str(exc)
            ok = "仍被占用" in message and "测试框架" in message
            print(f"[{'PASS' if ok else 'FAIL'}] 第二个实例被拦住：{message[:150]}")
            if not ok:
                verdict = 1

        first.stop()
        freed = not m.port_in_use(PORT)
        print(f"[{'PASS' if freed else 'FAIL'}] stop() 后端口已释放：port_in_use={m.port_in_use(PORT)}")
        if not freed:
            verdict = 1
    finally:
        sink.shutdown()
    return verdict


if __name__ == "__main__":
    sys.exit(main())
