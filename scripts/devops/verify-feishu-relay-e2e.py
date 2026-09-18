#!/usr/bin/env python3
"""飞书告警链路的端到端验证（2026-09-18）。

为什么要有这个脚本
------------------
「告警链路修好了」如果只靠"我写了配置文件"来宣称，就是典型的**未验证状态**。
本项目纪律：**「代码已改」≠「已生效」**，外部组件消费配置时必须回答
「**那组件到底能不能吃**」。所以这里真的把容器起起来：

  Phase A（无 docker，本地进程）
    A1  正常投递：Alertmanager 报文 → 桥 → 飞书（本地 sink）收到 `msg_type`/`content`
    A2  未配置时**返回 503**（不是 200）——防「桥在跑、消息全丢」的静默失效
    A3  飞书业务被拒（code!=0）时桥**返回 502 并透传 code** —— 本次修复的核心
    A4  配置签名密钥后，报文带 timestamp/sign，且 sign 与**独立实现**一致
    A5  超长正文按飞书 20 KB 上限截断
    A6  status=resolved 渲染成「告警恢复」—— 证明 `send_resolved: true` 的另一半真在

  Phase B（docker，真实组件）
    B1  `amtool check-config` 吃下**仓库里那份** alertmanager.yml
        （⚠️ 必须 `--entrypoint amtool`：镜像 entrypoint 是 alertmanager 本身）
    B1b 反向守卫：故意写坏的配置**必须被拒** —— 没有它 B1 可能是恒真判据
    B2/B3 用**同一份**配置起 alertmanager（relay 挂同一网络、服务名别名对得上）
    B4  向 Alertmanager API 投一条告警，断言它能一路走到飞书 sink
        ⇒ 证明「prometheus 规则 → alertmanager → 桥 → 飞书」除 prometheus 外全通

用法
----
    python scripts/devops/verify-feishu-relay-e2e.py            # A + B（B 需要 docker）
    python scripts/devops/verify-feishu-relay-e2e.py --no-docker  # 只跑 A，退出码 2 并标注未覆盖 B

退出码：0 = 全部通过；1 = 有用例失败；2 = 环境不足（如缺 docker／跳过 B），结论不完整。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RELAY = ROOT / "infra" / "monitoring" / "feishu-relay.py"
AM_CONFIG = ROOT / "infra" / "monitoring" / "alertmanager.yml"
AM_IMAGE = "prom/alertmanager:v0.28.1"
PY_IMAGE = "python:3.12-slim"

SINK_PORT = 18099
DOCKER_RELAY_PORT = 18097
AM_PORT = 19093
NETWORK = "aicab-feishu-relay-verify"

# ⚠️ 每个 Phase A 用例用**各自独立**的宿主端口，不要共用一个。
# 09-18 实测：A2/A3/A4/A6 串行复用同一端口时，前一个 RelayProc 刚 terminate、
# 端口尚未真正释放，后一个实例就会 bind 到已被占用的端口 ⇒ 它的 POST 打到**别人**
# 或直接连接失败，表现为 `relay=0`。
# 那类失败的真正危害不是"偶发红"，而是**红得指错了地方**（看起来像桥坏了，
# 其实是测试框架的端口竞态）。所以这里既拆端口，又在起停时显式等端口空/占。
RELAY_PORT_A1 = 18091
RELAY_PORT_A2 = 18092
RELAY_PORT_A3 = 18093
RELAY_PORT_A4 = 18094
RELAY_PORT_A6 = 18095

RELAY_CONTAINER = "aicab-feishu-relay-verify"
AM_CONTAINER = "aicab-alertmanager-verify"

# ---------------------------------------------------------------- 本地飞书 sink

_received: list[dict] = []
_sink_code = 0  # 改成非 0 即模拟飞书「业务被拒但 HTTP 200」
_sink_lock = threading.Lock()


class _Sink(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    timeout = 30

    def log_message(self, *args) -> None:  # 静音
        pass

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length else b""
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except Exception:  # noqa: BLE001
            parsed = {"_raw": raw.decode("utf-8", "replace")}
        with _sink_lock:
            _received.append(parsed)
        body = json.dumps(
            {"code": _sink_code, "msg": "success" if _sink_code == 0 else "Key Words Not Found"}
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")


def start_sink() -> ThreadingHTTPServer:
    # 必须多线程：HTTP/1.1 是 keep-alive，单线程 HTTPServer 会被一个复用连接占死。
    server = ThreadingHTTPServer(("0.0.0.0", SINK_PORT), _Sink)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def sink_bodies() -> list[dict]:
    with _sink_lock:
        return list(_received)


def sink_reset() -> None:
    with _sink_lock:
        _received.clear()


# ---------------------------------------------------------------- 工具

def http_post(url: str, obj, timeout: float = 10.0) -> tuple[int, str]:
    request = urllib.request.Request(
        url,
        data=json.dumps(obj, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001
        return 0, f"<连接失败 {exc}>"


def http_get(url: str, timeout: float = 3.0) -> tuple[int, str]:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except Exception:  # noqa: BLE001
        return 0, ""


def port_in_use(port: int) -> bool:
    """端口上是否真有监听者（connect 成功即为占用）。

    用来把「端口竞态」和「桥本身坏了」区分开：前者是测试框架问题，
    不应伪装成后者那样的 `relay=0` 去误导排查方向。
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(0.5)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def wait_until(predicate, seconds: float, step: float = 0.5) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(step)
    return predicate()


def feishu_sign(secret: str, timestamp: int) -> str:
    """独立实现：key = 'ts\\nsecret'、被签消息为空串。"""
    digest = hmac.new(f"{timestamp}\n{secret}".encode("utf-8"), b"", hashlib.sha256).digest()
    return base64.b64encode(digest).decode("ascii")


def alertmanager_payload(alertname: str, summary: str, status: str = "firing") -> dict:
    return {
        "version": "4",
        "status": status,
        "receiver": "feishu",
        "groupLabels": {"alertname": alertname},
        "commonLabels": {"alertname": alertname, "severity": "critical"},
        "commonAnnotations": {},
        "alerts": [
            {
                "status": status,
                "labels": {"alertname": alertname, "severity": "critical"},
                "annotations": {"summary": summary},
                "startsAt": "2026-09-18T10:00:00Z",
            }
        ],
    }


# ---------------------------------------------------------------- Phase A

class RelayProc:
    """逐个用例起一个桥进程；**端口由调用方指定且各不相同**（见 RELAY_PORT_* 注释）。"""

    def __init__(self, env: dict, port: int) -> None:
        self.port = port
        if not wait_until(lambda: not port_in_use(port), 10, step=0.2):
            raise RuntimeError(
                f"起桥前端口 {port} 仍被占用：上一个用例的进程没退干净。"
                "⚠️ 这是**测试框架的竞态**，不是桥的缺陷，别据此改 feishu-relay.py。"
            )
        full_env = {**os.environ, "PORT": str(port), **env}
        self.proc = subprocess.Popen(
            [sys.executable, str(RELAY)],
            env=full_env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if not wait_until(lambda: http_get(f"http://127.0.0.1:{port}/health")[0] == 200, 15):
            out = self.stop()
            raise RuntimeError(f"桥进程未能在 15s 内就绪（port={port}）。进程输出：{out.strip()[-400:]}")

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}/webhook"

    def stop(self) -> str:
        """终止进程并**等端口真正释放**，返回其输出（失败时用于归因）。"""
        out = ""
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                out, _ = self.proc.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                out, _ = self.proc.communicate(timeout=5)
        else:
            try:
                out, _ = self.proc.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                pass
        wait_until(lambda: not port_in_use(self.port), 10, step=0.2)
        return out or ""


def phase_a(results: list[tuple[str, bool, str]]) -> None:
    global _sink_code
    sink_url = f"http://127.0.0.1:{SINK_PORT}/send"

    # --- A1 正常投递 ---
    sink_reset()
    _sink_code = 0
    relay = RelayProc({"FEISHU_WEBHOOK_URL": sink_url}, RELAY_PORT_A1)
    try:
        code, body = http_post(relay.url, alertmanager_payload("DeviceAllOffline", "全部设备离线"))
        got = sink_bodies()
        ok = code == 200 and len(got) == 1
        detail = f"relay={code}"
        if ok:
            payload = got[0]
            text = (payload.get("content") or {}).get("text", "")
            ok = (
                payload.get("msg_type") == "text"
                and "msgtype" not in payload
                and "DeviceAllOffline" in text
                and "全部设备离线" in text
            )
            detail = f"relay={code} msg_type={payload.get('msg_type')} text首行={text.splitlines()[0] if text else ''!r}"
        results.append(("A1 正常投递 → 飞书报文形状正确", ok, detail))

        # --- A5 超长截断（复用 A1 的桥进程，同一端口） ---
        sink_reset()
        huge = alertmanager_payload("Huge", "x" * 40000)
        code, _ = http_post(relay.url, huge)
        got = sink_bodies()
        text = ((got[0].get("content") or {}).get("text", "") if got else "")
        ok = code == 200 and len(text) <= 18000 + 32 and "已截断" in text
        results.append(("A5 超长正文按 20KB 上限截断", ok, f"relay={code} 正文长度={len(text)}"))
    finally:
        relay.stop()

    # --- A2 未配置 → 503 ---
    relay = RelayProc({"FEISHU_WEBHOOK_URL": ""}, RELAY_PORT_A2)
    try:
        code, body = http_post(relay.url, alertmanager_payload("Probe", "未配置"))
        results.append(
            ("A2 未配置 FEISHU_WEBHOOK_URL 时返回 503（不是 200）", code == 503, f"relay={code} body={body[:80]}")
        )
    finally:
        relay.stop()

    # --- A3 飞书业务被拒 → 502 且透传 code ---
    sink_reset()
    _sink_code = 19024
    relay = RelayProc({"FEISHU_WEBHOOK_URL": sink_url}, RELAY_PORT_A3)
    try:
        code, body = http_post(relay.url, alertmanager_payload("Probe", "被拒"))
        ok = code == 502 and "19024" in body
        results.append(("A3 飞书 code!=0 → 桥返回 502 并透传业务码", ok, f"relay={code} body={body[:90]}"))
    finally:
        relay.stop()
        _sink_code = 0

    # --- A4 签名 ---
    sink_reset()
    relay = RelayProc({"FEISHU_WEBHOOK_URL": sink_url, "FEISHU_SIGN_SECRET": "s3cr3t"}, RELAY_PORT_A4)
    try:
        code, _ = http_post(relay.url, alertmanager_payload("Probe", "签名"))
        got = sink_bodies()
        ok = False
        detail = f"relay={code}（0 = 连不上，见 RelayProc 的端口预检）"
        if got:
            payload = got[0]
            ts = payload.get("timestamp")
            sign = payload.get("sign")
            ok = bool(ts) and sign == feishu_sign("s3cr3t", int(ts))
            detail = f"relay={code} timestamp={ts} sign一致性={bool(ok)}"
        results.append(("A4 签名与独立实现一致", ok, detail))
    finally:
        relay.stop()

    # --- A6 resolved 报文渲染成「恢复」 ---
    # alertmanager.yml 里写了 send_resolved: true（"群里只看到炸了看不到好了"是常见投诉），
    # 但那只在**配置里**声明；这一例证明桥真的把 status=resolved 渲染成恢复语义，
    # 且同时**没有**把 resolved 误判成触发（避免标题恒为「告警触发」的假绿）。
    sink_reset()
    relay = RelayProc({"FEISHU_WEBHOOK_URL": sink_url}, RELAY_PORT_A6)
    try:
        code, _ = http_post(relay.url, alertmanager_payload("DeviceAllOffline", "全部设备已恢复", status="resolved"))
        got = sink_bodies()
        ok = False
        detail = f"relay={code}"
        if got:
            text = (got[0].get("content") or {}).get("text", "")
            first = text.splitlines()[0] if text else ""
            ok = code == 200 and "告警恢复" in first and "[恢复]" in text and "[触发]" not in text
            detail = f"relay={code} 首行={first!r}"
        results.append(("A6 status=resolved 渲染为「告警恢复」（send_resolved 的另一半）", ok, detail))
    finally:
        relay.stop()


# ---------------------------------------------------------------- Phase B

def docker(*args: str, timeout: float = 120.0) -> tuple[int, str]:
    proc = subprocess.run(
        ["docker", *args], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def cleanup() -> None:
    for name in (RELAY_CONTAINER, AM_CONTAINER):
        docker("rm", "-f", name, timeout=60)
    docker("network", "rm", NETWORK, timeout=60)


def phase_b(results: list[tuple[str, bool, str]]) -> None:
    sink_url = f"http://host.docker.internal:{SINK_PORT}/send"
    sink_reset()
    cleanup()

    # --- B1 amtool check-config 吃下仓库里那份配置 ---
    # ⚠️ 必须加 `--entrypoint amtool`：`prom/alertmanager` 镜像的 entrypoint 就是
    # `alertmanager` 二进制，直接写 `... IMAGE amtool check-config ...` 会把 amtool
    # 当成 alertmanager 的参数 ⇒ 报 "unexpected amtool" 且 exit 1。
    # 那是**调用姿势错**，不是配置坏（判据假红）—— 09-18 实跑踩到。
    code, out = docker(
        "run", "--rm", "--entrypoint", "amtool",
        "-v", f"{AM_CONFIG}:/cfg/alertmanager.yml:ro",
        AM_IMAGE, "check-config", "/cfg/alertmanager.yml",
    )
    results.append(
        ("B1 amtool 能解析仓库里的 alertmanager.yml", code == 0 and "FAILED" not in out, f"exit={code} {out.strip()[:160]}")
    )

    # --- B1b 反向守卫：坏配置必须被拒 ---
    # 没有这一例，B1 就可能是**恒真判据**（万一 amtool 无论配置对错都 exit 0）。
    # 用一个「route 指向不存在的 receiver」的配置证明 check-config 真的会红。
    broken_dir = Path(tempfile.mkdtemp(prefix="aicab-am-broken-"))
    try:
        (broken_dir / "broken.yml").write_text(
            "route:\n  receiver: nowhere\nreceivers:\n  - name: feishu\n    webhook_configs:\n      - url: http://127.0.0.1:1/x\n",
            encoding="utf-8",
        )
        bad_code, bad_out = docker(
            "run", "--rm", "--entrypoint", "amtool",
            "-v", f"{broken_dir}:/cfg:ro",
            AM_IMAGE, "check-config", "/cfg/broken.yml",
        )
        results.append(
            (
                "B1b 反向守卫：坏配置必须被拒（防 B1 恒真）",
                bad_code != 0 and "FAILED" in bad_out,
                f"exit={bad_code} {bad_out.strip()[:120]}",
            )
        )
    finally:
        shutil.rmtree(broken_dir, ignore_errors=True)

    code, out = docker("network", "create", NETWORK)
    if code != 0:
        results.append(("B2 建立验证网络", False, out.strip()[:160]))
        return

    # relay 容器：服务名别名要与 alertmanager.yml 里的 URL 一致
    code, out = docker(
        "run", "-d", "--name", RELAY_CONTAINER,
        "--network", NETWORK, "--network-alias", "feishu-alert-relay",
        "-p", f"{DOCKER_RELAY_PORT}:8098",
        "-e", f"FEISHU_WEBHOOK_URL={sink_url}",
        "-e", "PORT=8098",
        "-v", f"{RELAY}:/app/feishu-relay.py:ro",
        PY_IMAGE, "python", "/app/feishu-relay.py",
    )
    if code != 0:
        results.append(("B2 启动 relay 容器", False, out.strip()[:160]))
        return

    ready = wait_until(
        lambda: http_get(f"http://127.0.0.1:{DOCKER_RELAY_PORT}/health")[1].find('"configured": true') >= 0, 25
    )
    results.append(("B2 relay 容器就绪且已配置飞书地址", ready, f"health={http_get(f'http://127.0.0.1:{DOCKER_RELAY_PORT}/health')[1][:100]}"))

    code, out = docker(
        "run", "-d", "--name", AM_CONTAINER,
        "--network", NETWORK,
        "-p", f"{AM_PORT}:9093",
        "-v", f"{AM_CONFIG}:/etc/alertmanager/alertmanager.yml:ro",
        AM_IMAGE, "--config.file=/etc/alertmanager/alertmanager.yml",
    )
    if code != 0:
        results.append(("B3 启动 alertmanager 容器（用同一份配置）", False, out.strip()[:160]))
        return

    am_ready = wait_until(lambda: http_get(f"http://127.0.0.1:{AM_PORT}/api/v2/status")[0] == 200, 45)
    results.append(("B3 alertmanager 就绪（配置被接受）", am_ready, f"status={http_get(f'http://127.0.0.1:{AM_PORT}/api/v2/status')[0]}"))
    if not am_ready:
        _, logs = docker("logs", "--tail", "20", AM_CONTAINER)
        results.append(("B3 失败时的容器日志", False, logs.strip()[-400:]))
        return

    # 投一条告警，等它走完 group_wait 并由 relay 投到 sink
    code, out = http_post(
        f"http://127.0.0.1:{AM_PORT}/api/v2/alerts",
        [
            {
                "labels": {"alertname": "RelayE2EProbe", "severity": "critical"},
                "annotations": {"summary": "alertmanager → 桥 → 飞书 端到端探针"},
                "startsAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            }
        ],
    )
    results.append(("B4 向 Alertmanager API 投递告警", code in (200, 202), f"api={code}"))

    got = wait_until(lambda: len(sink_bodies()) > 0, 40, step=1.0)
    bodies = sink_bodies()
    ok = got and any("RelayE2EProbe" in ((b.get("content") or {}).get("text") or "") for b in bodies)
    detail = f"sink 收到 {len(bodies)} 条"
    if bodies:
        text = (bodies[0].get("content") or {}).get("text", "")
        detail += f" msg_type={bodies[0].get('msg_type')} text={text.splitlines()[0] if text else ''!r}"
    results.append(("B4 端到端：alertmanager → 桥 → 飞书报文", ok, detail))
    if not ok:
        _, logs = docker("logs", "--tail", "20", AM_CONTAINER)
        results.append(("B4 失败时的 alertmanager 日志", False, logs.strip()[-400:]))


# ---------------------------------------------------------------- main

def main() -> int:
    no_docker = "--no-docker" in sys.argv
    for path in (RELAY, AM_CONFIG):
        if not path.exists():
            print(f"缺少 {path}", file=sys.stderr)
            return 2

    sink = start_sink()
    results: list[tuple[str, bool, str]] = []
    docker_used = False
    try:
        print("[Phase A] 桥本身的投递与失败语义")
        phase_a(results)

        if no_docker:
            print("\n[Phase B] 已按 --no-docker 跳过（结论不完整）")
        elif shutil.which("docker") is None:
            print("\n[Phase B] 未执行：找不到 docker（结论不完整）", file=sys.stderr)
        else:
            print("\n[Phase B] 真实 alertmanager 是否吃得下配置、能否一路走到飞书")
            docker_used = True
            phase_b(results)
    finally:
        if docker_used:
            cleanup()
        sink.shutdown()

    print()
    width = max(len(n) for n, _, _ in results)
    passed = 0
    for name, ok, detail in results:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name.ljust(width)}  {detail}")
        passed += 1 if ok else 0
    print(f"\n{passed}/{len(results)} 用例通过")

    if passed != len(results):
        return 1
    if no_docker or not docker_used:
        print("⚠️  Phase B 未覆盖：本次只证明桥本身，未证明 alertmanager 能吃这份配置。")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
