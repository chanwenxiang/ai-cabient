#!/usr/bin/env python3
"""Minimal SMS webhook receiver for staging / Step 5 validation.

trade-service POSTs:
  - 验证码: {"phoneNumber":"138...","code":"123456"}
  - 通知短信: {"phoneNumber":"138...","message":"..."}
"""

from __future__ import annotations

import json
import os
import signal
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from threading import Lock

PORT = int(os.getenv("PORT", "8099"))
LOG_FILE = os.getenv("SMS_LOG_FILE", "/tmp/sms-codes.log")

_lock = Lock()
_last: dict[str, str] = {}


class SmsWebhookHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        print(f"[sms-mock] {self.address_string()} - {fmt % args}")

    def do_GET(self) -> None:
        if self.path == "/health":
            self._respond(200, b"ok")
            return
        if self.path == "/last":
            with _lock:
                body = json.dumps(_last, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self._respond(404, b"not found")

    def do_POST(self) -> None:
        global _last
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length > 0 else b"{}"
        try:
            data = json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._respond(400, b"invalid json")
            return
        phone = str(data.get("phoneNumber", "")).strip()
        code = str(data.get("code", "")).strip()
        message = str(data.get("message", "")).strip()
        if not phone:
            self._respond(400, b"missing phoneNumber")
            return
        payload = message if message else code
        if not payload:
            self._respond(400, b"missing code or message")
            return
        line = f"{phone} -> {payload}\n"
        print(line.strip())
        try:
            with open(LOG_FILE, "a", encoding="utf-8") as f:
                f.write(line)
        except OSError as exc:
            print(f"warn: cannot write log file: {exc}")
        with _lock:
            _last = {"phoneNumber": phone, "code": code, "message": message}
        self._respond(204, b"")

    def _respond(self, status: int, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)


def main() -> None:
    server = HTTPServer(("0.0.0.0", PORT), SmsWebhookHandler)

    # 优雅停机：容器里本进程是 PID 1，而内核会**忽略 PID 1 对未安装 handler 的信号**，
    # 所以不装 handler 时 compose 的 stop_grace_period 一秒都用不上 —— 进程只会等到宽限
    # 耗尽被 SIGKILL（exit 137）。修法与 infra/monitoring/feishu-relay.py 同一处。
    def _graceful(signum: int, _frame: object) -> None:
        print(f"sms-webhook-mock 收到信号 {signum}，停止接受新请求…", flush=True)
        # shutdown() 会阻塞等待 serve_forever() 返回，而本 handler 就跑在 serve_forever 的
        # 主线程里 —— 直接调用会自等死锁，必须换线程执行。
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, _graceful)
    signal.signal(signal.SIGINT, _graceful)
    print(f"sms-webhook-mock listening on :{PORT}  health=/health  last=/last")
    try:
        server.serve_forever()
    finally:
        server.server_close()
    print("sms-webhook-mock 已优雅退出", flush=True)


if __name__ == "__main__":
    main()
