#!/usr/bin/env python3
"""连接饿死 A/B 对照：单线程 `HTTPServer` vs `ThreadingHTTPServer`。

为什么需要这个对照
------------------
`feishu-relay.py` 用 HTTP/1.1（keep-alive），而 Alertmanager 是 **Go 客户端、会复用连接**。
单线程 `HTTPServer` 处理完一个请求后会阻塞在「等同一连接的下一个请求」上，**不再 accept 新连接**
⇒ 桥被一条空闲连接占死，表现为「第一条告警发得出去，之后全部静默超时」。

⚠️ **复现条件很反直觉，写错就测不出来**：
    同一连接复用发 N 次请求 —— **两种版本都过**（单线程是在循环里处理同一连接的）。
    必须：**连接 A 完成一次请求后保持打开**，再用**新连接 B** 请求 ⇒ 单线程版 B 超时。
（第一版对照就是这么写错的：只用了一个连接，两组都绿，差点得出"判据无区分力"的错误结论。）

两个被测实例都放进 compose 网络（`ai-cabinet_default`），但只依赖该网络存在，不依赖监控栈在跑。

用法
----
    python scripts/devops/verify-relay-starvation-ab.py

退出码：0 = 对照符合预期（单线程被饿死 + 多线程正常）；1 = 不符合预期；2 = 环境不足。
"""

from __future__ import annotations

import http.client
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RELAY = ROOT / "infra" / "monitoring" / "feishu-relay.py"

NETWORK = "ai-cabinet_default"
IMAGE = "python:3.12-slim"
PORT_SINGLE = 18097
PORT_MULTI = 18096
C_SINGLE = "tmp-relay-single"
C_MULTI = "tmp-relay-multi"

TAG = "[relay-starvation-ab]"


def sh(*args: str, timeout: int = 120):
    p = subprocess.run(args, capture_output=True, timeout=timeout)
    return p.returncode, ((p.stdout or b"") + (p.stderr or b"")).decode("utf-8", "replace")


def probe(port: int, label: str) -> bool | None:
    """连接 A 完成一次请求后保持打开，再用新连接 B 请求。返回 B 是否成功。"""
    print(f"--- {label} (port {port}) ---", flush=True)
    try:
        a = http.client.HTTPConnection("127.0.0.1", port, timeout=6)
        a.request("GET", "/health")
        a.getresponse().read()
    except Exception as e:  # noqa: BLE001
        print(f"  连接 A 失败：{type(e).__name__}: {e}", flush=True)
        return None
    print(f"  A：已发一次请求并**保持打开**（模拟 Alertmanager 连接池里的空闲连接）", flush=True)

    try:
        b = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        b.request("GET", "/health")
        r = b.getresponse()
        r.read()
        print(f"  B：HTTP={r.status}  ✅ 新连接未被饿死", flush=True)
        ok = True
    except Exception as e:  # noqa: BLE001
        print(f"  B：❌ {type(e).__name__} —— 被 A 的 keep-alive 饿死", flush=True)
        ok = False
    finally:
        try:
            b.close()
        except Exception:  # noqa: BLE001
            pass
        a.close()
    return ok


def main() -> int:
    print(f"{TAG} 连接饿死 A/B 对照", flush=True)

    code, out = sh("docker", "version", "--format", "{{.Server.Version}}")
    if code != 0:
        print(f"{TAG} docker 不可用：{out.strip()[:160]}", flush=True)
        return 2
    code, _ = sh("docker", "network", "inspect", NETWORK)
    if code != 0:
        print(f"{TAG} 网络 {NETWORK} 不存在。先起过业务栈（docker-up.ps1）再跑本对照。", flush=True)
        return 2

    if not RELAY.exists():
        print(f"{TAG} 找不到 {RELAY}", flush=True)
        return 2
    src = RELAY.read_text(encoding="utf-8")
    single_src = src.replace("ThreadingHTTPServer", "HTTPServer")
    if single_src == src:
        print(f"{TAG} 源码里没有 ThreadingHTTPServer：对照前提不成立（说明修复已被回退？）", flush=True)
        return 1

    tmpdir = tempfile.mkdtemp(prefix="relay-ab-")
    single_path = Path(tmpdir) / "relay-single.py"
    single_path.write_text(single_src, encoding="utf-8")
    win = str(single_path).replace("\\", "/")

    for name in (C_SINGLE, C_MULTI):
        sh("docker", "rm", "-f", name)

    def run(name: str, vol: str, port: int):
        return sh(
            "docker", "run", "-d", "--name", name, "--network", NETWORK,
            "-p", f"127.0.0.1:{port}:8098",
            "-v", vol,
            "-e", "PORT=8098", "-e", "FEISHU_WEBHOOK_URL=",
            IMAGE, "python", "/app/relay.py",
        )

    code, out = run(C_SINGLE, f"{win}:/app/relay.py:ro", PORT_SINGLE)
    if code != 0:
        print(f"{TAG} 起单线程实例失败：{out.strip()[:200]}", flush=True)
        return 2
    code, out = run(C_MULTI, f"{str(RELAY).replace(chr(92), '/')}:/app/relay.py:ro", PORT_MULTI)
    if code != 0:
        print(f"{TAG} 起多线程实例失败：{out.strip()[:200]}", flush=True)
        return 2

    time.sleep(4)
    single = probe(PORT_SINGLE, "对照组：单线程 HTTPServer（修复前）")
    multi = probe(PORT_MULTI, "修复后：ThreadingHTTPServer")

    for name in (C_SINGLE, C_MULTI):
        sh("docker", "rm", "-f", name)
    shutil.rmtree(tmpdir, ignore_errors=True)

    print(flush=True)
    print(f"  单线程被饿死（预期 True）: {single is False}", flush=True)
    print(f"  多线程正常   （预期 True）: {multi is True}", flush=True)
    decisive = (single is False) and (multi is True)
    print(f"{TAG} 判据有区分力: {decisive}", flush=True)
    if decisive:
        return 0
    print(f"{TAG} ❌ 对照不符合预期 —— 要么修复被回退，要么复现方式写错了", flush=True)
    return 1


if __name__ == "__main__":
    sys.exit(main())
