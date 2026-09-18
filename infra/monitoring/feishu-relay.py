#!/usr/bin/env python3
"""飞书告警转换桥 —— 把 Alertmanager / Grafana 的 webhook JSON 翻成飞书机器人报文。

为什么必须有这个组件
--------------------
**Alertmanager 与 Grafana 都没有原生飞书 receiver**
（Alertmanager 有 `wechat`/`webhook` 等、Grafana 有 `dingding`/`wecom`/`webhook`，唯独没有飞书）。
而两家的 `webhook` 类型发的是**它们自己的 JSON**，直接填飞书 webhook 地址会被飞书
以 `code=9499 Bad Request` 拒收。所以中间必须有一个转换层。

设计要点（与本项目「信号不能骗读者」的纪律一致）
----------------------------------------------
1. **不把 HTTP 200 当成功**：飞书在业务被拒时仍返回 200，只在响应体里给 `code`。
   本桥解析 `code`，非 0 时**返回 502 并把 code/msg 透传**，让 Alertmanager/Grafana
   把这次通知标成失败，而不是假装送出去了。
2. **未配置就明说**：`FEISHU_WEBHOOK_URL` 为空时返回 **503**（不是 200），
   避免"桥在跑、消息全丢"的静默失效。
3. 单条消息按飞书 20 KB 上限截断，并在末尾标注已截断。

接口
----
    POST /webhook   Alertmanager 或 Grafana 的告警 JSON
    GET  /health    健康检查（含 configured 标志）
    GET  /last      最近一次实际发出的飞书报文（排障用）

环境变量
--------
    FEISHU_WEBHOOK_URL    飞书自定义机器人 webhook（留空=未配置，投递返回 503）
    FEISHU_SIGN_SECRET    可选；填写后按飞书「签名校验」加上 timestamp/sign
    PORT                  默认 8098
    FEISHU_TIMEOUT_SEC    默认 8
    RELAY_DRY_RUN=1       只渲染、不真发（本地联调用）
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

PORT = int(os.getenv("PORT", "8098"))
FEISHU_WEBHOOK_URL = os.getenv("FEISHU_WEBHOOK_URL", "").strip()
FEISHU_SIGN_SECRET = os.getenv("FEISHU_SIGN_SECRET", "").strip()
FEISHU_TIMEOUT_SEC = float(os.getenv("FEISHU_TIMEOUT_SEC", "8"))
DRY_RUN = os.getenv("RELAY_DRY_RUN", "").strip() not in ("", "0", "false", "False")

# 飞书限制请求体 <= 20 KB，留出 JSON 包装与转义的余量。
MAX_TEXT_CHARS = 18_000

_lock = Lock()
_last: dict[str, object] = {}


def feishu_sign(secret: str, timestamp: int) -> str:
    """飞书签名：key = 'timestamp\\nsecret'、被签消息为空串，HmacSHA256 后 Base64。"""
    string_to_sign = f"{timestamp}\n{secret}"
    digest = hmac.new(string_to_sign.encode("utf-8"), b"", hashlib.sha256).digest()
    return base64.b64encode(digest).decode("ascii")


def build_feishu_body(text: str) -> dict[str, object]:
    body: dict[str, object] = {}
    if FEISHU_SIGN_SECRET:
        ts = int(time.time())
        body["timestamp"] = str(ts)
        body["sign"] = feishu_sign(FEISHU_SIGN_SECRET, ts)
    # ⚠️ 飞书是下划线 msg_type；钉钉/企微才是 msgtype。
    body["msg_type"] = "text"
    body["content"] = {"text": text}
    return body


def render_alert_text(payload: dict[str, object]) -> str:
    """把 Alertmanager / Grafana 的 webhook JSON 渲染成一段纯文本。

    两家报文结构高度相似：都有 status、alerts[]（每项含 labels/annotations），
    因此同一套渲染可以同时服务两条链路。
    """
    raw_alerts = payload.get("alerts")
    alerts = raw_alerts if isinstance(raw_alerts, list) else []
    status = str(payload.get("status") or "").strip().lower()
    header = str(payload.get("title") or "").strip()

    if not header:
        if status == "resolved":
            header = "告警恢复"
        elif status == "firing":
            header = "告警触发"
        else:
            header = "告警通知"

    lines = [f"【{header}】共 {len(alerts)} 条"]

    for item in alerts:
        if not isinstance(item, dict):
            continue
        labels = item.get("labels") if isinstance(item.get("labels"), dict) else {}
        annotations = item.get("annotations") if isinstance(item.get("annotations"), dict) else {}
        name = str(labels.get("alertname") or "unknown")
        severity = str(labels.get("severity") or "").strip()
        item_status = str(item.get("status") or status or "").strip().lower()
        summary = str(annotations.get("summary") or annotations.get("description") or "").strip()

        flag = "恢复" if item_status == "resolved" else "触发"
        head = f"- [{flag}] {name}"
        if severity:
            head += f"（{severity}）"
        lines.append(head)
        if summary:
            lines.append(f"  {summary}")

    if len(lines) == 1:
        # 没有 alerts[]（例如测试投递），退化成用 title/message 渲染。
        message = str(payload.get("message") or "").strip()
        lines.append(message or "（报文里没有 alerts 字段）")

    text = "\n".join(lines)
    if len(text) > MAX_TEXT_CHARS:
        text = text[:MAX_TEXT_CHARS] + "\n…（内容过长已截断）"
    return text


def forward_to_feishu(text: str) -> tuple[int, str]:
    """投递到飞书；返回 (HTTP 状态码, 说明)。非 2xx 表示**没有送达**。"""
    if not FEISHU_WEBHOOK_URL:
        return 503, "FEISHU_WEBHOOK_URL 未配置：桥无法投递（这是配置缺失，不是送达成功）"

    body = build_feishu_body(text)
    if DRY_RUN:
        return 200, "DRY_RUN：已渲染但未发送"

    request = urllib.request.Request(
        FEISHU_WEBHOOK_URL,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=FEISHU_TIMEOUT_SEC) as resp:
            raw = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        return 502, f"飞书 HTTP {exc.code}：{detail[:300]}"
    except Exception as exc:  # noqa: BLE001 - 网络层任何异常都算未送达
        return 502, f"飞书请求失败：{exc}"

    try:
        parsed = json.loads(raw or "{}")
    except json.JSONDecodeError:
        return 502, f"飞书返回体不是 JSON：{raw[:200]}"

    code = parsed.get("code")
    if isinstance(code, bool) or not isinstance(code, int):
        return 502, f"飞书返回体缺少业务码 code：{raw[:200]}"
    if code != 0:
        return 502, f"飞书拒绝投递 code={code} msg={parsed.get('msg')}"
    return 200, "已送达"


class RelayHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    # keep-alive 下空闲连接不能永久占住线程（否则并发/复用连接会被饿死）
    timeout = 30

    def log_message(self, fmt: str, *args) -> None:
        print(f"[feishu-relay] {self.address_string()} - {fmt % args}", flush=True)

    def _respond(self, status: int, body: bytes, content_type: str = "text/plain; charset=utf-8") -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _json(self, status: int, obj: object) -> None:
        self._respond(status, json.dumps(obj, ensure_ascii=False).encode("utf-8"), "application/json")

    def do_GET(self) -> None:
        if self.path == "/health":
            self._json(200, {"ok": True, "configured": bool(FEISHU_WEBHOOK_URL), "dryRun": DRY_RUN})
            return
        if self.path == "/last":
            with _lock:
                self._json(200, dict(_last))
            return
        self._respond(404, b"not found")

    def do_POST(self) -> None:
        if self.path not in ("/webhook", "/"):
            self._respond(404, b"not found")
            return

        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length > 0 else b""
        try:
            payload = json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._respond(400, b"invalid json")
            return
        if not isinstance(payload, dict):
            self._respond(400, b"payload must be a JSON object")
            return

        text = render_alert_text(payload)
        status, detail = forward_to_feishu(text)
        # ⚠️ 必须声明 global：少了它，这里的 `_last = ...` 只是给 do_POST 造了个**局部变量**，
        # 模块级 `_last` 永远是 {} ⇒ `GET /last` 恒为空、排障接口形同虚设
        # （2026-09-18 由 verify-monitoring-stack-e2e.py 的失败路径判据查出）。
        global _last
        with _lock:
            _last = {"text": text, "status": status, "detail": detail, "at": int(time.time())}
        print(f"[feishu-relay] forward status={status} detail={detail}", flush=True)
        self._json(status, {"delivered": status == 200, "detail": detail})


def main() -> None:
    # ⚠️ 必须是 **Threading**HTTPServer，不能是单线程 HTTPServer。
    # 本桥用 HTTP/1.1（默认 keep-alive），而 Alertmanager 是 Go 客户端、会**复用连接**：
    # 单线程 HTTPServer 处理完第一个请求后会阻塞在"等同一连接的下一个请求"上，
    # 于是 server 不再 accept 新连接 ⇒ 只有第一条告警发得出去，之后全部静默超时。
    # （2026-09-18 实测：单线程版本在真实 alertmanager 下 1 个线程、连接全部 timeout。）
    server = ThreadingHTTPServer(("0.0.0.0", PORT), RelayHandler)
    server.daemon_threads = True
    state = "已配置" if FEISHU_WEBHOOK_URL else "未配置（FEISHU_WEBHOOK_URL 为空，投递将返回 503）"
    print(
        f"feishu-alert-relay listening on :{PORT}  POST /webhook  health=/health  last=/last\n"
        f"  飞书 webhook：{state}  签名：{'开' if FEISHU_SIGN_SECRET else '关'}  dryRun={DRY_RUN}",
        flush=True,
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
