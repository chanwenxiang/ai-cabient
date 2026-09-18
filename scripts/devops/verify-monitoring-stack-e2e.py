#!/usr/bin/env python3
"""监控栈告警链路端到端验证（真实 docker 栈）。

链路：Alertmanager → feishu-alert-relay → 飞书（本地替身 sink）

本脚本与其他三个验证的分工
--------------------------
    应用内四渠道     scripts/check-ops-alert-channels.mjs + verify-ops-alert-channels-drift.py
    桥本身（无栈）   scripts/devops/verify-feishu-relay-e2e.py
    本脚本            **真实 compose 栈**：prometheus 是否连上 alertmanager、
                     仓库那份 alertmanager.yml 是否真被吃下、告警能否一路走到飞书报文

关键判据（不只是"能跑通"）
--------------------------
  • prometheus 侧 `activeAlertmanagers` 必须含 alertmanager:9093
      ⇒ 证明 prometheus-full.yml 的 alerting 段真的生效，不只是"文件里有这行"
  • prometheus 必须加载到规则（条数 == alert-rules.yml 里的 alert 条目数）
  • 投递成功的判据是**飞书报文形状**（`msg_type` 下划线、无 `msgtype` 字面量），
    不是"relay 返回了 200"
  • **失败必须显形**：把 sink 停掉后投递，relay 的 `/last` 必须是 502 而不是 200
      ⇒ 这是"信号不骗人"的核心。拿 200 冒充送达的桥在这里会露馅

前提
----
监控栈已在运行：
    cd infra && docker compose --env-file .env \\
      -f docker-compose.full.yml -f docker-compose.win-ports.yml \\
      --profile alerting up -d prometheus grafana feishu-alert-relay alertmanager

用法
----
    python scripts/devops/verify-monitoring-stack-e2e.py            # 完整（含恢复验证，约 7 分钟）
    python scripts/devops/verify-monitoring-stack-e2e.py --fast     # 跳过恢复验证（省 ~5 分钟）
    python scripts/devops/verify-monitoring-stack-e2e.py --keep-sink # 保留 sink 容器

退出码：0 = 全部通过；1 = 有用例失败；2 = 环境不足（结论不完整）。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INFRA = ROOT / "infra"
SINK_SCRIPT = ROOT / "scripts" / "devops" / "feishu-sink-mock.py"

RELAY_CONTAINER = "ai-cabinet-feishu-alert-relay-1"
AM_CONTAINER = "ai-cabinet-alertmanager-1"
PROM_CONTAINER = "ai-cabinet-prometheus-1"
SINK_CONTAINER = "tmp-feishu-sink"
NETWORK = "ai-cabinet_default"

RELAY_URL = "http://127.0.0.1:18098"
AM_URL = "http://127.0.0.1:19093"
PROM_URL = "http://127.0.0.1:9090"
SINK_URL = "http://127.0.0.1:18099"
SINK_PORT = 18099
SINK_SERVICE = "tmp-feishu-sink:18099"

TAG = "[monitoring-e2e]"


def sh(*args: str, timeout: int = 120) -> tuple[int, str]:
    """跑一条命令。Windows 下产物可能是 GBK，按字节宽松解码。"""
    try:
        p = subprocess.run(args, capture_output=True, timeout=timeout)
    except FileNotFoundError:
        return 127, f"命令不存在：{args[0]}"
    except subprocess.TimeoutExpired:
        return 124, f"超时：{' '.join(args[:3])}"
    out = (p.stdout or b"") + (p.stderr or b"")
    for enc in ("utf-8", "gbk"):
        try:
            return p.returncode, out.decode(enc)
        except UnicodeDecodeError:
            continue
    return p.returncode, out.decode("utf-8", "replace")


def http(method: str, url: str, body=None, timeout: float = 8.0):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        url, data=data, method=method,
        headers={"Content-Type": "application/json"} if data else {},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return 0, str(e)


def jget(url: str):
    code, raw = http("GET", url)
    if code != 200:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return None


def wait_until(fn, seconds: float) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        if fn():
            return True
        time.sleep(1)
    return fn()


def container_running(name: str) -> bool:
    code, out = sh("docker", "inspect", "-f", "{{.State.Running}}", name)
    return code == 0 and out.strip() == "true"


def timing(name: str, default: float) -> float:
    """从 alertmanager.yml 读 group_wait / group_interval（秒），配置改了脚本仍适配。"""
    try:
        text = (INFRA / "monitoring" / "alertmanager.yml").read_text(encoding="utf-8")
    except OSError:
        return default
    m = re.search(rf"^\s*{name}:\s*(\d+)([sm])\s*$", text, re.M)
    if not m:
        return default
    return float(m.group(1)) * (60 if m.group(2) == "m" else 1)


def compose_up_relay(webhook_url: str) -> tuple[int, str]:
    files = ["-f", str(INFRA / "docker-compose.full.yml")]
    winports = INFRA / "docker-compose.win-ports.yml"
    if winports.exists():
        files += ["-f", str(winports)]
    env = {**os.environ, "FEISHU_WEBHOOK_URL": webhook_url}
    p = subprocess.run(
        ["docker", "compose", "--env-file", str(INFRA / ".env"), *files,
         "--profile", "alerting", "up", "-d", "feishu-alert-relay"],
        capture_output=True, env=env, timeout=180,
    )
    return p.returncode, ((p.stdout or b"") + (p.stderr or b"")).decode("utf-8", "replace")


def start_sink() -> tuple[bool, str]:
    sh("docker", "rm", "-f", SINK_CONTAINER)
    script_win = str(SINK_SCRIPT).replace("\\", "/")
    code, out = sh(
        "docker", "run", "-d", "--name", SINK_CONTAINER, "--network", NETWORK,
        "-p", f"127.0.0.1:{SINK_PORT}:{SINK_PORT}",
        "-v", f"{script_win}:/app/sink.py:ro",
        "-e", f"SINK_PORT={SINK_PORT}", "-e", "SINK_CODE=0",
        "python:3.12-slim", "python", "/app/sink.py",
    )
    if code != 0:
        return False, out.strip()[:200]
    if not wait_until(lambda: container_running(SINK_CONTAINER), 20):
        return False, "sink 容器未进入运行状态"
    return wait_until(lambda: jget(f"{SINK_URL}/count") is not None, 20), ""


def stop_sink() -> None:
    sh("docker", "stop", SINK_CONTAINER, timeout=60)
    time.sleep(2)


def inject(name: str, ends: bool = False) -> int:
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    alert = {
        "labels": {"alertname": name, "severity": "critical", "biz": "e2e"},
        "annotations": {"summary": "端到端验证：监控栈 → 飞书"},
        "startsAt": now,
    }
    if ends:
        alert["endsAt"] = now
    code, _ = http("POST", f"{AM_URL}/api/v2/alerts", [alert])
    return code


def sink_messages() -> list:
    d = jget(f"{SINK_URL}/all") or {}
    return d.get("messages", [])


def texts_of(msgs: list) -> list:
    return [((m.get("content") or {}).get("text") or "") for m in msgs]


def item(label: str, ok: bool, detail: str = "") -> tuple:
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}" + (f"   {detail}" if detail else ""), flush=True)
    return (label, ok, detail)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fast", action="store_true", help="跳过恢复(resolved)验证")
    ap.add_argument("--keep-sink", action="store_true", help="验证后保留 sink 容器")
    args = ap.parse_args()

    results: list = []
    print(f"{TAG} 监控栈告警链路端到端验证", flush=True)
    print(f"{TAG} 仓库根：{ROOT}\n", flush=True)

    # ---------- Phase 0 环境 ----------
    print("== Phase 0：环境 ==", flush=True)
    code, out = sh("docker", "version", "--format", "{{.Server.Version}}")
    if code != 0:
        print(f"{TAG} docker 不可用：{out.strip()[:160]}")
        return 2
    print(f"  docker server {out.strip()}", flush=True)

    for name in (RELAY_CONTAINER, AM_CONTAINER, PROM_CONTAINER):
        if not container_running(name):
            print(f"{TAG} 容器 {name} 未运行。请先起监控栈：", flush=True)
            print(f"    cd infra && docker compose --env-file .env \\", flush=True)
            print(f"      -f docker-compose.full.yml -f docker-compose.win-ports.yml \\", flush=True)
            print(f"      --profile alerting up -d prometheus grafana feishu-alert-relay alertmanager", flush=True)
            return 2
    print(f"  三个监控容器均在运行", flush=True)

    # ---------- Phase 1 健康与接线 ----------
    print("\n== Phase 1：健康与接线 ==", flush=True)
    d = jget(f"{RELAY_URL}/health") or {}
    results.append(item("relay /health 可达", bool(d), str(d)))

    d = jget(f"{AM_URL}/api/v2/status") or {}
    results.append(item("alertmanager 就绪", d.get("cluster", {}).get("status") == "ready",
                        f"cluster={d.get('cluster', {}).get('status')}"))

    rules = jget(f"{PROM_URL}/api/v1/rules?type=alert") or {}
    groups = (rules.get("data") or {}).get("groups") or []
    n_rules = sum(len(g.get("rules", [])) for g in groups)
    # 规则文件的真值来自 alert_rules.yml，不写死 22
    try:
        rule_text = (INFRA / "prometheus" / "alert_rules.yml").read_text(encoding="utf-8")
        expect_rules = len(re.findall(r"^\s*-\s*alert:", rule_text, re.M))
    except OSError:
        expect_rules = n_rules
    results.append(item(
        "prometheus 加载到全部告警规则",
        n_rules == expect_rules and n_rules > 0,
        f"加载 {n_rules} 条 / 文件中 {expect_rules} 条（{len(groups)} 组）",
    ))

    ams = (jget(f"{PROM_URL}/api/v1/alertmanagers") or {}).get("data", {})
    active = [a.get("url", "") for a in (ams.get("activeAlertmanagers") or [])]
    results.append(item(
        "prometheus 已连上 alertmanager",
        any("alertmanager:9093" in u for u in active),
        f"active={active or '无'}  ← 证明 prometheus-full.yml 的 alerting 段真的生效",
    ))

    # ---------- Phase 2 sink 与 relay 指向 ----------
    print("\n== Phase 2：飞书替身与 relay 指向 ==", flush=True)
    ok, detail = start_sink()
    results.append(item("飞书替身 sink 就绪", ok, detail))

    rc, out = compose_up_relay(f"http://{SINK_SERVICE}/send")
    time.sleep(3)
    d = jget(f"{RELAY_URL}/health") or {}
    results.append(item("relay 已注入飞书地址（configured）", d.get("configured") is True,
                        f"compose rc={rc}；health={d}" if not d.get("configured") else f"dryRun={d.get('dryRun')}"))
    code, out = sh("docker", "exec", RELAY_CONTAINER, "python", "-c",
                   f"import urllib.request;urllib.request.urlopen('http://{SINK_SERVICE}/count',timeout=5);print('ok')")
    results.append(item("relay 容器能解析到 sink（服务名）", code == 0 and "ok" in out, out.strip()[:120]))

    # ---------- Phase 3 清空 alertmanager ----------
    print("\n== Phase 3：清空 alertmanager 状态 ==", flush=True)
    sh("docker", "restart", AM_CONTAINER, timeout=90)
    wait_until(lambda: http("GET", f"{AM_URL}/-/healthy")[0] == 200, 40)
    left = jget(f"{AM_URL}/api/v2/alerts")
    results.append(item("alertmanager 已清空（无历史告警干扰）", left == [], f"残留 {len(left or [])} 条"))

    wait_g = timing("group_wait", 10.0) + 12
    wait_i = timing("group_interval", 300.0) + 25

    # ---------- Phase 4 成功路径 ----------
    print(f"\n== Phase 4：成功路径（触发） ==", flush=True)
    http("GET", f"{SINK_URL}/reset")
    name_fire = f"E2EProbe{time.strftime('%H%M%S')}"
    print(f"  注入告警 {name_fire}，等待 group_wait≈{timing('group_wait', 10.0):.0f}s …", flush=True)
    if inject(name_fire) != 200:
        results.append(item("告警注入 alertmanager", False, "POST /api/v2/alerts 非 200"))
    else:
        got = wait_until(lambda: any(name_fire in t for t in texts_of(sink_messages())), wait_g)
        msgs = [m for m in sink_messages() if name_fire in ((m.get("content") or {}).get("text") or "")]
        if not got or not msgs:
            results.append(item("触发通知送达飞书", False, f"sink 未收到含 {name_fire} 的报文"))
        else:
            m = msgs[0]
            t = (m.get("content") or {}).get("text", "")
            shape_ok = m.get("msg_type") == "text" and "msgtype" not in m
            sem_ok = "[触发]" in t and name_fire in t
            results.append(item("触发通知送达飞书", True, f"首行={t.splitlines()[0]!r}"))
            results.append(item("飞书报文形状正确（msg_type 下划线）", shape_ok,
                                f"msg_type={m.get('msg_type')!r} 含msgtype={('msgtype' in m)}"))
            results.append(item("触发语义正确（[触发] + alertname）", sem_ok, ""))

    # ---------- Phase 5 失败必须显形 ----------
    print(f"\n== Phase 5：失败必须显形（sink 不可达 → 502，不许假绿） ==", flush=True)
    stop_sink()
    name_fail = f"E2EProbeFail{time.strftime('%H%M%S')}"
    print(f"  停掉 sink 后注入 {name_fail}，等待 group_wait…", flush=True)
    inject(name_fail)
    def relay_reports_failure() -> bool:
        d = jget(f"{RELAY_URL}/last") or {}
        return d.get("status") == 502 and name_fail in str(d.get("text", ""))
    ok = wait_until(relay_reports_failure, wait_g)
    d = jget(f"{RELAY_URL}/last") or {}
    results.append(item(
        "sink 不可达时 relay 返回 502（不拿 200 冒充送达）",
        ok, f"relay /last status={d.get('status')} detail={str(d.get('detail'))[:90]}",
    ))
    ok, detail = start_sink()
    results.append(item("sink 恢复", ok, detail))

    # ---------- Phase 6 恢复路径 ----------
    if args.fast:
        print("\n== Phase 6：恢复路径 —— 已按 --fast 跳过（结论不完整） ==", flush=True)
    else:
        print(f"\n== Phase 6：恢复路径（send_resolved） ==", flush=True)
        http("GET", f"{SINK_URL}/reset")
        name_res = f"E2EProbeRes{time.strftime('%H%M%S')}"
        inject(name_res)
        wait_until(lambda: any(name_res in t for t in texts_of(sink_messages())), wait_g)
        print(f"  置为 recovered，等待 group_interval≈{timing('group_interval', 300.0):.0f}s …", flush=True)
        inject(name_res, ends=True)
        got = wait_until(
            lambda: any(("[恢复]" in t) and (name_res in t) for t in texts_of(sink_messages())),
            wait_i,
        )
        hit = [t for t in texts_of(sink_messages()) if "[恢复]" in t and name_res in t]
        results.append(item("恢复通知送达（send_resolved: true 的另一半）", got,
                            f"首行={hit[0].splitlines()[0]!r}" if hit else "未收到 [恢复] 报文"))

    # ---------- 清理 ----------
    if args.keep_sink:
        print(f"\n{TAG} --keep-sink：保留 {SINK_CONTAINER}（排障用，记得手动 docker rm -f）", flush=True)
    else:
        sh("docker", "rm", "-f", SINK_CONTAINER)
        print(f"\n{TAG} 已清理 {SINK_CONTAINER}", flush=True)
        print(f"{TAG} 注意：relay 仍指向已删除的 sink；如需再验证，重跑本脚本即可（会重建）。", flush=True)

    # ---------- 汇总 ----------
    failed = [r for r in results if not r[1]]
    print(f"\n{TAG} {len(results) - len(failed)}/{len(results)} 用例通过", flush=True)
    if failed:
        for label, _, detail in failed:
            print(f"  FAIL: {label}  {detail}", flush=True)
        return 1
    if args.fast:
        print(f"{TAG} ⚠️  --fast：恢复路径未验证，结论不完整", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
