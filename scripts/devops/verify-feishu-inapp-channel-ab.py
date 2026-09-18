#!/usr/bin/env python
"""应用内飞书告警渠道（`OpsAlertDispatcher`）的 A/B 对照验证。

判据（三条同时成立才算通过，否则非零退出）：

  A 组  渠道指向「按飞书契约返回非 0 业务码」的替身
        ⇒ alert-test 必须 delivered=false，且 detail 里带出平台业务码
  B 组  渠道指回真实飞书 webhook
        ⇒ alert-test 必须 delivered=true，且 detail 为空
  终态  渠道必须已复原成真实 webhook（A 组改过的值不能留）

为什么需要 A 组：飞书**被拒也回 HTTP 200**，只把原因写在响应体 `code` 里。
不制造一次"被拒"，就无法证明这个 delivered 标志不是恒真的橡皮图章。

前置：
  1) trade-service 的运行镜像里**确实含**飞书渠道。判断方法见
     docs/evidence/2026-09-18-feishu-inapp-channel/deploy-artifact-ab.txt
     （⚠️ 不能 `grep app.jar`，class 是压缩的，那是假判据）。
  2) 拒绝替身已起（命名必须与 --mock-url 一致，且与 trade-service 同网络）：

     docker run -d --name tmp-feishu-sink --network ai-cabinet_default \
       -v "$PWD/scripts/devops/feishu-sink-mock.py:/app/sink.py:ro" \
       -e SINK_PORT=18099 -e SINK_CODE=19024 \
       python:3.12-slim python /app/sink.py

  3) 有运营台 admin token。dev 下 `aicabinet.security.captcha-enabled=true`，
     登录取 token 需要图形验证码：

       GET /api/v2/auth/captcha  -> {captchaId, imageBase64}
       POST /api/v2/auth/admin-password-login {phoneNumber, password, captchaId, captchaCode}
       token 在响应体 **data.token**（不是顶层）

用法：
  python scripts/devops/verify-feishu-inapp-channel-ab.py --token <ADMIN_TOKEN>
  AI_CABINET_OPS_TOKEN=<TOKEN> python scripts/devops/verify-feishu-inapp-channel-ab.py
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.request

KEY = "ops.alert.feishu_webhook"
DESC = "运营告警：飞书自定义机器人 Webhook URL（留空不推送）"
DEFAULT_BASE = "http://localhost:18080"
DEFAULT_MOCK = "http://tmp-feishu-sink:18099/send"


def mask(url: str | None) -> str:
    """凭据打码：只留前缀，token 一律不外泄到日志/证据。"""
    if not url:
        return "<空>"
    return re.sub(r"(hook/).*", r"\1****-****", url)


class Client:
    def __init__(self, base: str, token: str) -> None:
        self.base = base.rstrip("/")
        self.token = token

    def call(self, path: str, method: str = "GET", body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        req.add_header("Authorization", "Bearer " + self.token)
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode())

    def set_webhook(self, value: str):
        return self.call(
            "/api/v2/ops/admin/system-configs",
            "PUT",
            {"configKey": KEY, "configValue": value, "description": DESC},
        )

    def get_webhook(self) -> str | None:
        rows = self.call("/api/v2/ops/admin/system-configs").get("data") or []
        return next((r.get("configValue") for r in rows if r.get("configKey") == KEY), None)

    def alert_test(self) -> dict:
        rows = self.call("/api/v2/ops/admin/system-configs/alert-test", "POST", {}).get("data") or []
        for row in rows:
            if row.get("channel") == "FEISHU":
                return row
        return {"channel": "FEISHU", "delivered": None, "detail": "(未返回 FEISHU 探测项)"}


def read_real_webhook(env_path: str) -> str:
    """从 infra/.env 读真实 webhook（该文件被 .gitignore 覆盖，是凭据的正规落点）。"""
    if not os.path.exists(env_path):
        sys.exit(f"[FAIL] 找不到 {env_path}；请用 --real-webhook 显式给值")
    text = open(env_path, encoding="utf-8", errors="replace").read()
    m = re.search(r"^FEISHU_WEBHOOK_URL=(.*)$", text, re.M)
    value = (m.group(1).strip().replace("\r", "") if m else "")
    if not value:
        sys.exit(f"[FAIL] {env_path} 里 FEISHU_WEBHOOK_URL 为空；无法作为 B 组真值")
    return value


def main() -> int:
    ap = argparse.ArgumentParser(description="应用内飞书告警渠道 A/B 对照")
    ap.add_argument("--base", default=os.environ.get("AI_CABINET_BASE", DEFAULT_BASE))
    ap.add_argument("--token", default=os.environ.get("AI_CABINET_OPS_TOKEN", ""))
    ap.add_argument("--mock-url", default=DEFAULT_MOCK, help="A 组用的拒绝替身地址")
    ap.add_argument("--real-webhook", default="", help="B 组真值；默认从 infra/.env 读")
    ap.add_argument("--env-file", default="infra/.env")
    args = ap.parse_args()

    if not args.token:
        sys.exit("[FAIL] 缺 admin token：用 --token 或环境变量 AI_CABINET_OPS_TOKEN")

    real = args.real_webhook or read_real_webhook(args.env_file)
    cli = Client(args.base, args.token)

    print("=== A 组：渠道指向拒绝替身（期望 delivered=false + 带业务码）===")
    print("    mock =", args.mock_url)
    cli.set_webhook(args.mock_url)
    a = cli.alert_test()
    print("    detail    =", a.get("detail"))
    print("    delivered =", a.get("delivered"))
    detail = a.get("detail") or ""
    a_ok = a.get("delivered") is False and re.search(r"\b(code|errcode)=\d+", detail) is not None
    print("    A 组符合预期:", a_ok)

    print()
    print("=== B 组：渠道指回真实飞书（期望 delivered=true + detail 空）===")
    print("    webhook =", mask(real))
    cli.set_webhook(real)
    b = cli.alert_test()
    print("    detail    =", repr(b.get("detail")))
    print("    delivered =", b.get("delivered"))
    b_ok = b.get("delivered") is True and not (b.get("detail") or "").strip()
    print("    B 组符合预期:", b_ok)

    print()
    print("=== 终态复核：渠道必须已复原成真实 webhook ===")
    current = cli.get_webhook()
    print("    当前值 =", mask(current))
    restored = current == real
    print("    已复原:", restored)

    ok = a_ok and b_ok and restored
    print()
    print("结论：A/B 双向判据 + 终态复原", "全部成立" if ok else "存在不成立项")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
