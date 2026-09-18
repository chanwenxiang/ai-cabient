#!/usr/bin/env python3
"""飞书机器人**替身**（mock）—— 仅用于本地/CI 验证告警链路，绝不投递真实飞书。

为什么需要一个"替身"而不是拿 HTTP 200 当成功
--------------------------------------------
飞书的契约是：**成功与失败都返回 HTTP 200**，成败只看响应体的业务码 `code`。
所以任何"看到 200 就算送达"的验证都是**假绿**。本替身严格复刻这个契约，
让被测方（`feishu-relay.py`）必须真的去解析 `code` 才能判对。

接口
----
    POST /send    收到飞书报文，按 SINK_CODE 返回业务码；报文存入内存
    GET  /last    最近一条报文
    GET  /all     全部报文（判定"触发/恢复都送达"时用）
    GET  /count   条数
    GET  /reset   清空

环境变量
--------
    SINK_PORT   默认 18099
    SINK_CODE   默认 0（成功）；设为 19024 可模拟"关键词不匹配"被拒

用法
----
    # docker（推荐：放进 compose 网络，被 relay 以服务名访问）
    docker run -d --name tmp-feishu-sink --network ai-cabinet_default \
      -p 127.0.0.1:18099:18099 \
      -v "$PWD/scripts/devops/feishu-sink-mock.py:/app/sink.py:ro" \
      -e SINK_PORT=18099 -e SINK_CODE=0 \
      python:3.12-slim python /app/sink.py
"""

from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

PORT = int(os.getenv("SINK_PORT", "18099"))
CODE = int(os.getenv("SINK_CODE", "0"))

_lock = Lock()
_received: list = []


class Sink(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    timeout = 30

    def log_message(self, fmt, *args):
        print(f"[feishu-sink] {fmt % args}", flush=True)

    def _json(self, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/last":
            with _lock:
                self._json(_received[-1] if _received else {})
            return
        if self.path == "/all":
            with _lock:
                self._json({"count": len(_received), "messages": list(_received)})
            return
        if self.path == "/count":
            with _lock:
                self._json({"count": len(_received)})
            return
        if self.path == "/reset":
            with _lock:
                _received.clear()
            self._json({"count": 0})
            return
        self._json({"ok": True})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length > 0 else b""
        try:
            payload = json.loads(raw.decode("utf-8"))
        except Exception:
            payload = {"_raw": raw.decode("utf-8", "replace")}
        with _lock:
            _received.append(payload)
        msg = "success" if CODE == 0 else "Key Words Not Found"
        self._json({"code": CODE, "msg": msg})


if __name__ == "__main__":
    print(f"[feishu-sink] listening :{PORT}  将返回 code={CODE}", flush=True)
    # 与 feishu-relay.py 同理：HTTP/1.1 + keep-alive 必须配多线程，否则会被单个
    # 复用连接占死（实测单线程版在真实链路下会卡住不再 accept）。
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Sink)
    server.daemon_threads = True
    server.serve_forever()
