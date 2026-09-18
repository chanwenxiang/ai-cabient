#!/usr/bin/env python3
"""把飞书机器人 webhook 接进监控告警链路 —— 一条命令完成「写入 + 重建 + 真实投递验证」。

为什么需要这个脚本（而不是"手动改 .env 再重启"）
------------------------------------------------
手动接飞书有四个经典静默失败，每一个看起来都像成功：

  1. **URL 形状写错**（粘了 Alertmanager/Grafana 地址、少 /hook/、token 被截断）
     ⇒ 桥照常启动、照常返回 5xx，但没人核对格式。
  2. **改了 `infra/.env` 却没重建容器** ⇒ 容器里还是旧值/空值。
  3. **重建了但 shell 环境变量盖住了 `.env`** ⇒ 值对了文件、错了进程
     （compose 优先级：进程环境 > .env 文件）。
  4. **飞书"收到但拒绝"**：飞书在关键词不匹配/签名不对时**仍返回 HTTP 200**，
     只在响应体里给 `code`（19024/19021/19022）。拿 200 当成功就是被骗。

本脚本对四件事逐一给判据：URL 形状校验 → 幂等写入 → 复核**容器内**取值 →
真实投递并读 `/last` 断言 `已送达`。任何一步不成立都以非零退出，绝不报"已对接"。

接口
----
    # 接入（交互最少：只要一个 URL）
    python scripts/devops/activate-feishu-webhook.py --url "https://open.feishu.cn/open-apis/bot/v2/hook/xxxx"

    # 机器人开了「签名校验」时
    python scripts/devops/activate-feishu-webhook.py --url "..." --secret "..."

    # 只体检当前状态，不改任何东西
    python scripts/devops/activate-feishu-webhook.py --check

    # 清空配置（回到 fail-closed 的「未配置」，投递返回 503）
    python scripts/devops/activate-feishu-webhook.py --clear

    # 仅写入不重建（调试用）
    python scripts/devops/activate-feishu-webhook.py --url "..." --no-recreate

退出码：0 = 已接通且**实测送达**；1 = 有步骤失败；2 = 用法/前置不满足。
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
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[2]
INFRA = ROOT / "infra"
ENV_FILE = INFRA / ".env"
RELAY_CONTAINER = "ai-cabinet-feishu-alert-relay-1"
RELAY_URL = os.getenv("FEISHU_RELAY_URL", "http://127.0.0.1:18098")

KEY_URL = "FEISHU_WEBHOOK_URL"
KEY_SECRET = "FEISHU_SIGN_SECRET"

# 飞书自定义机器人 webhook 的合法主机与路径前缀。
# 国际版 Lark 是 open.larksuite.com，域名不同但路径一致。
FEISHU_HOSTS = ("open.feishu.cn", "open.larksuite.com")
FEISHU_PATH_PREFIX = "/open-apis/bot/v2/hook/"


# --------------------------------------------------------------------------
# .env 读写（保留原编码与 CRLF，且幂等）
# --------------------------------------------------------------------------
def read_env_bytes() -> bytes:
    if not ENV_FILE.exists():
        sys.exit(f"[FAIL] 找不到 {ENV_FILE}（compose 用 --env-file 读它）")
    return ENV_FILE.read_bytes()


def text_and_eol(raw: bytes) -> tuple[str, str]:
    """解码并判定换行风格。

    ⚠️ 本仓库的 infra/.env 是 **CRLF**（82 行 = 82 个 CR + 82 个 LF）。
    用 LF 重写会把整个文件变成"全量改动"，让真正的改动淹没在 diff 里。
    """
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        # 宁可拒绝，也不要"顺手"重编码把别的行改坏。
        sys.exit("[FAIL] infra/.env 不是合法 UTF-8；本脚本拒绝改写以免破坏文件编码")
    crlf = text.count("\r\n")
    lf = text.count("\n") - crlf
    return text, ("\r\n" if crlf >= lf else "\n")


def upsert(text: str, eol: str, updates: dict[str, str]) -> tuple[str, list[str]]:
    """按行替换/追加 KEY=VALUE；同名键只保留一行（防重复键把后面的值顶掉）。"""
    lines = text.split(eol)
    trailing_empty = lines and lines[-1] == ""
    if trailing_empty:
        lines = lines[:-1]

    notes: list[str] = []
    for key, value in updates.items():
        pattern = re.compile(rf"^\s*{re.escape(key)}\s*=")
        hits = [i for i, ln in enumerate(lines) if pattern.match(ln)]
        newline = f"{key}={value}"
        if hits:
            first = hits[0]
            if lines[first].rstrip("\r") == newline:
                notes.append(f"{key} 已是目标值（未改动）")
            else:
                lines[first] = newline
                notes.append(f"{key} 已更新（第 {first + 1} 行）")
            for dup in hits[1:]:
                lines[dup] = None  # type: ignore[assignment]
                notes.append(f"{key} 删除重复键（第 {dup + 1} 行）")
        else:
            if not any(ln and ln.startswith("# --- 告警通知") for ln in lines):
                lines.append("")
                lines.append("# --- 告警通知（飞书；仅监控栈链路用，应用内四渠道走运营台 DB 配置）---")
            lines.append(newline)
            notes.append(f"{key} 已追加")

    cleaned = [ln for ln in lines if ln is not None]
    out = eol.join(cleaned)
    if trailing_empty or not out.endswith(eol):
        out += eol
    return out, notes


def masked(url: str) -> str:
    """webhook URL 本身是凭据 —— 输出里默认打码，只留主机与 token 尾部。"""
    if not url:
        return "(空)"
    p = urlparse(url)
    tail = p.path.rstrip("/").rsplit("/", 1)[-1]
    return f"{p.scheme}://{p.netloc}/…/hook/{'*' * 6}{tail[-4:]}" if tail else url


# --------------------------------------------------------------------------
# 校验
# --------------------------------------------------------------------------
def validate_url(url: str, allow_foreign: bool) -> tuple[bool, str]:
    if not url:
        return False, "URL 为空"
    p = urlparse(url)
    if allow_foreign:
        # 联调专用逃生门：只要求是个能解析的 http(s) 地址。
        # 生产路径永远走下面的严格分支 —— 这个分支只能由人**显式**加 flag 进入。
        if p.scheme not in ("http", "https") or not p.netloc:
            return False, f"联调地址无法解析：{url!r}"
        return True, f"联调模式：放行 {p.scheme}://{p.netloc}（这不是飞书，只可用于本地替身）"
    if p.scheme != "https":
        return False, f"必须用 https（当前 scheme={p.scheme or '空'}）——飞书不接受 http"
    if p.netloc not in FEISHU_HOSTS:
        return (
            False,
            f"主机 {p.netloc!r} 不是飞书域名（应为 {' 或 '.join(FEISHU_HOSTS)}）。"
            f"若这是本地替身在联调，请显式加 --allow-non-feishu",
        )
    if not p.path.startswith(FEISHU_PATH_PREFIX):
        return False, f"路径必须以 {FEISHU_PATH_PREFIX} 开头（当前 {p.path!r}）——常见于粘贴了 Alertmanager/Grafana 地址"
    token = p.path[len(FEISHU_PATH_PREFIX):].strip("/")
    if len(token) < 8:
        return False, f"webhook token 过短（{len(token)} 位），疑似被截断"
    if re.search(r"\s", url):
        return False, "URL 含空白字符，疑似复制时带入了换行/空格"
    return True, "形状合法"


# --------------------------------------------------------------------------
# docker / http 小工具
# --------------------------------------------------------------------------
def sh(*args: str, timeout: int = 180) -> tuple[int, str]:
    try:
        p = subprocess.run(args, capture_output=True, timeout=timeout)
    except FileNotFoundError:
        return 127, f"找不到可执行文件 {args[0]}"
    except subprocess.TimeoutExpired:
        return 124, f"命令超时：{' '.join(args)}"
    return p.returncode, ((p.stdout or b"") + (p.stderr or b"")).decode("utf-8", "replace")


def http(method: str, url: str, payload=None, timeout: float = 15) -> tuple[int, str]:
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001
        return 0, f"{type(exc).__name__}: {exc}"


def jget(url: str, timeout: float = 15):
    code, body = http("GET", url, timeout=timeout)
    if code != 200:
        return None
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        return None


def container_env(name: str, key: str) -> str | None:
    code, out = sh("docker", "inspect", name, "--format", "{{range .Config.Env}}{{println .}}{{end}}")
    if code != 0:
        return None
    for line in out.splitlines():
        if line.startswith(f"{key}="):
            return line[len(key) + 1:]
    return None


def wait_until(fn, seconds: float, interval: float = 1.0) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        if fn():
            return True
        time.sleep(interval)
    return False


# --------------------------------------------------------------------------
# 步骤
# --------------------------------------------------------------------------
def compose_recreate() -> tuple[bool, str]:
    files = ["-f", str(INFRA / "docker-compose.full.yml")]
    winports = INFRA / "docker-compose.win-ports.yml"
    if winports.exists():
        files += ["-f", str(winports)]
    # ⚠️ 必须把这两个键从子进程环境里**摘掉**：compose 的优先级是
    #    进程环境 > --env-file，残留的旧值会盖住我们刚写进 .env 的新值，
    #    于是"文件对了、容器还是旧的"，最难查的一类假成功。
    env = {k: v for k, v in os.environ.items() if k not in (KEY_URL, KEY_SECRET)}
    try:
        p = subprocess.run(
            ["docker", "compose", "--env-file", str(ENV_FILE), *files,
             "--profile", "alerting", "up", "-d", "--force-recreate", "feishu-alert-relay"],
            capture_output=True, env=env, timeout=300,
        )
    except subprocess.TimeoutExpired:
        return False, "docker compose 超时（300s）"
    out = ((p.stdout or b"") + (p.stderr or b"")).decode("utf-8", "replace")
    return p.returncode == 0, out


def deliver_test(alertname: str) -> tuple[bool, str]:
    """向 relay 投一条合成告警，再读 /last 取**桥对飞书的真实结论**。

    关键：断言的是 `/last.detail`（桥解析飞书响应体 `code` 后的结论），
    不是"relay 返回了 200"——飞书业务被拒时也会 HTTP 200。
    """
    payload = {
        "status": "firing",
        "alerts": [{
            "status": "firing",
            "labels": {"alertname": alertname, "severity": "info", "biz": "feishu-activation"},
            "annotations": {"summary": "飞书渠道接入自检：这条消息若出现在群里，说明链路已通。"},
        }],
    }
    code, body = http("POST", f"{RELAY_URL}/webhook", payload)
    if code == 0:
        return False, f"桥不可达（{RELAY_URL}）：{body[:200]}"
    last = jget(f"{RELAY_URL}/last") or {}
    detail = str(last.get("detail") or "")
    if code == 200 and "已送达" in detail:
        return True, detail
    return False, f"relay HTTP {code}，桥的结论={detail!r}（报文响应={body[:160]}）"


def main() -> int:
    ap = argparse.ArgumentParser(description="接入飞书告警 webhook 并实测投递")
    ap.add_argument("--url", help="飞书自定义机器人 webhook 地址")
    ap.add_argument("--secret", help="机器人「签名校验」的密钥（开了才需要）")
    ap.add_argument("--check", action="store_true", help="只体检当前配置，不改动任何东西")
    ap.add_argument("--clear", action="store_true", help="清空飞书配置（回到未配置 503）")
    ap.add_argument("--no-recreate", action="store_true", help="只写 .env，不重建容器")
    ap.add_argument("--no-test", action="store_true", help="跳过投递自检")
    ap.add_argument("--allow-non-feishu", action="store_true", help="放行非飞书主机（仅本地替身联调用）")
    args = ap.parse_args()

    if args.check:
        print("== 当前飞书告警渠道状态 ==")
        text, _ = text_and_eol(read_env_bytes())
        m = re.search(rf"^\s*{KEY_URL}\s*=(.*)$", text, re.M)
        key_in_file = m is not None
        file_val = (m.group(1).strip() if m else "")
        print(f"  infra/.env     : {masked(file_val)}" + ("" if key_in_file else "  (该键不存在)"))
        env_val = container_env(RELAY_CONTAINER, KEY_URL)
        print(f"  容器内实际值   : {masked(env_val or '')}"
              + ("" if env_val is not None else "  (容器不存在)"))
        health = jget(f"{RELAY_URL}/health")
        print(f"  /health        : {health if health is not None else '不可达'}")
        # 判据按「是否漂移」而不是「文件里有没有值」：
        # 键缺失而容器里却有值（例如被 shell 环境变量注入的临时值）同样是漂移，
        # 且正是"改了没生效/生效了没落盘"两类假成功的来源，必须显形。
        if env_val is not None and (not key_in_file or file_val != env_val):
            if not key_in_file:
                print("  [FAIL] infra/.env 里没有该键，但容器内却有值 ⇒ 配置没落盘，重建即丢")
            else:
                print("  [FAIL] .env 与容器不一致 ⇒ 改了 .env 但没重建容器")
            return 1
        print("  [OK] .env 与容器取值一致")
        return 0

    if args.clear:
        text, eol = text_and_eol(read_env_bytes())
        out, notes = upsert(text, eol, {KEY_URL: "", KEY_SECRET: ""})
        ENV_FILE.write_bytes(out.encode("utf-8"))
        for n in notes:
            print(f"  - {n}")
        print("== 已清空飞书配置（写入空值 = fail-closed 的「未配置」）==")
        if args.no_recreate:
            return 0
        ok, log = compose_recreate()
        print(("  [PASS] relay 已重建" if ok else f"  [FAIL] relay 重建失败:\n{log}"))
        if not ok:
            return 1
        wait_until(lambda: jget(f"{RELAY_URL}/health") is not None, 60)
        health = jget(f"{RELAY_URL}/health") or {}
        print(f"  /health: {health}")
        if health.get("configured") is not False:
            print("  [FAIL] 清空后 configured 仍为 true —— 进程环境变量在盖 .env？")
            return 1
        print("  [PASS] 已回到「未配置」：此后投递返回 503（配置缺失，不是送达成功）")
        return 0

    if not args.url:
        ap.error("需要 --url，或使用 --check / --clear")

    url = args.url.strip()
    secret = (args.secret or "").strip()
    ok, why = validate_url(url, args.allow_non_feishu)
    print(f"== 1/5 URL 形状校验 ==\n  [{'PASS' if ok else 'FAIL'}] {why}")
    if not ok:
        print("  → 拒绝写入，infra/.env 未被触碰")
        return 1
    print(f"  目标: {masked(url)}")
    if secret:
        print(f"  签名: 开（密钥 {len(secret)} 位）")
    else:
        print("  签名: 关（若机器人开了「签名校验」，飞书会拒收 code=19021）")

    print("== 2/5 写入 infra/.env（幂等，保留 CRLF）==")
    before = read_env_bytes()
    text, eol = text_and_eol(before)
    out, notes = upsert(text, eol, {KEY_URL: url, KEY_SECRET: secret})
    for n in notes:
        print(f"  - {n}")
    ENV_FILE.write_bytes(out.encode("utf-8"))
    after = read_env_bytes()
    m = re.search(rf"^\s*{KEY_URL}\s*=(.*)$", after.decode("utf-8"), re.M)
    if not m or m.group(1).strip() != url:
        print("  [FAIL] 回读校验失败：写入后读到的值与目标不一致")
        return 1
    print(f"  [PASS] 回读一致；文件 {len(before)} → {len(after)} 字节，换行仍为 {'CRLF' if eol == chr(13) + chr(10) else 'LF'}")

    if args.no_recreate:
        print("== 3/5 跳过重建（--no-recreate）==")
        print("  ⚠️ 未重建 ⇒ 容器内仍是旧值，**尚未生效**")
        return 0

    print("== 3/5 重建 relay 容器（--force-recreate）==")
    ok, log = compose_recreate()
    if not ok:
        print(f"  [FAIL] docker compose 返回非零：\n{log[-1500:]}")
        return 1
    if not wait_until(lambda: jget(f"{RELAY_URL}/health") is not None, 60):
        print("  [FAIL] 重建后 /health 60s 内不可达")
        return 1
    print("  [PASS] relay 已重建并起监听")

    print("== 4/5 复核容器内取值（防「改了 .env 没生效」）==")
    env_val = container_env(RELAY_CONTAINER, KEY_URL)
    if env_val != url:
        print(f"  [FAIL] 容器内 {KEY_URL}={masked(env_val or '')}，目标 {masked(url)}")
        print("         ⇒ 多半有同名进程环境变量在盖 .env（compose 优先级：进程环境 > .env）")
        return 1
    env_sec = container_env(RELAY_CONTAINER, KEY_SECRET) or ""
    print(f"  [PASS] 容器内取值 == 目标值；签名密钥{'已注入' if env_sec else '为空'}")
    health = jget(f"{RELAY_URL}/health") or {}
    if health.get("configured") is not True:
        print(f"  [FAIL] /health.configured 非 true：{health}")
        return 1

    if args.no_test:
        print("== 5/5 跳过投递自检（--no-test）==")
        print("  ⚠️ 未实测送达 ⇒ 只能说「已配置」，不能说「已接通」")
        return 0

    print("== 5/5 真实投递自检（飞书业务码必须为 0）==")
    alertname = f"FeishuActivationProbe{int(time.time())}"
    ok, detail = deliver_test(alertname)
    if ok:
        print(f"  [PASS] {detail}")
        print("\n== 结论：飞书告警渠道已接通，且实测送达 ==")
        print("  请到飞书群里确认是否收到那条自检消息（这是最后一道人工确认）。")
        return 0

    print(f"  [FAIL] {detail}")
    print("\n== 结论：**未接通**，不要把上面任何一步当成成功 ==")
    for code, hint in (
        ("19021", "签名校验未通过 → 补 --secret，或关掉机器人的「签名校验」"),
        ("19024", "关键词不匹配 → 消息正文须含机器人设定的关键词，或改用「签名校验」"),
        ("19022", "IP 不在白名单 → 在机器人安全设置里加白名单，或改为「签名校验」"),
        ("19001", "参数错误 → webhook 地址多半粘错/被截断"),
    ):
        if code in detail:
            print(f"  提示：飞书 code={code} —— {hint}")
    print("  ⚠️ 另注意：飞书对 4xx/5xx 也可能回 HTTP 200，成败只看响应体 code —— 本脚本已按此判定。")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
