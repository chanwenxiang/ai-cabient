#!/usr/bin/env python3
"""P0-6 运行期追踪开关端到端验证（真实 dev 栈，无 mock）。

证明的东西（这是本脚本存在的唯一理由）：

  1. 运营台「功能开关」注册表端点真的把 **56 条** 配置项发给前端，且
     `ops.observability.tracing_enabled` 是**三态 SELECT**（不是 BOOLEAN）。
  2. `ops.observability.otlp_endpoint` 一写进系统配置，**不重启**服务就生效 ——
     应用侧日志出现 `OTLP 导出端点已生效`，Tempo 随即收到 trade-service 的 span。
  3. `ops.observability.tracing_enabled=false` 一写进去，**不重启**就停止外发 ——
     同一段流量在 Tempo 里查不到新 trace。
  4. 改回留空又能恢复 —— 三态闭合，不是「一关就再也开不了」。

反面（脚本故意去证伪的假绿）：
  * 「配了端点但 traces 仍是 0」= 端点/导出链路真坏了，脚本必须失败。
  * 「开关写了 false 但 traces 还在涨」= 开关没生效（比如仍被启动期条件卡着），脚本必须失败。

用法（在仓库根）：

    python scripts/devops/verify-observability-runtime-switch.py
    python scripts/devops/verify-observability-runtime-switch.py --fast   # 缩短等待

前置：
  * dev 栈已起（trade-service 127.0.0.1:18080）
  * 可观测接收端已起（`infra/observability.ps1 on` ⇒ Tempo 127.0.0.1:13200）
  * Redis 容器 `ai-cabinet-redis-1` 可 `docker exec`（取图形验证码用）

退出码：0 = 全部断言通过；1 = 有断言失败（会逐条打印期望/实测）。
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE_URL = "http://127.0.0.1:18080"
TEMPO_URL = "http://127.0.0.1:13200"
REDIS_CONTAINER = "ai-cabinet-redis-1"
OPS_PHONE = "13900000001"
OPS_PASSWORD = "123456"

KEY_TRACING_SWITCH = "ops.observability.tracing_enabled"
KEY_OTLP_ENDPOINT = "ops.observability.otlp_endpoint"
TEMPO_ENDPOINT_VALUE = "http://tempo:4318/v1/traces"

TRAFFIC_PATH = "/actuator/health"
TRAFFIC_BURST = 24
INGEST_WAIT_SECONDS = 16
SETTLE_SECONDS = 12


class Probe:
    """极简探针：只做 HTTP + docker exec，不引入第三方依赖。"""

    def __init__(self, base_url: str, tempo_url: str, fast: bool) -> None:
        self.base_url = base_url.rstrip("/")
        self.tempo_url = tempo_url.rstrip("/")
        self.settle = 4 if fast else SETTLE_SECONDS
        self.ingest_wait = 6 if fast else INGEST_WAIT_SECONDS
        self.token: str | None = None
        self.failures: list[str] = []

    # ── HTTP ────────────────────────────────────────────────────────────────

    def _request(self, method: str, path: str, body: dict | None = None, auth: bool = True) -> dict:
        url = f"{self.base_url}{path}"
        data = None
        headers = {"Content-Type": "application/json"}
        if auth:
            if self.token is None:
                self.login()
            headers["Authorization"] = f"Bearer {self.token}"
        if body is not None:
            data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def _http_status(self, path: str) -> int:
        url = f"{self.base_url}{path}"
        try:
            with urllib.request.urlopen(url, timeout=15) as resp:
                return resp.status
        except urllib.error.HTTPError as exc:
            return exc.code

    # ── 登录 ────────────────────────────────────────────────────────────────

    def login(self) -> None:
        cap = self._request("GET", "/api/v2/auth/captcha", auth=False)
        captcha_id = (cap.get("data") or {}).get("captchaId")
        if not captcha_id:
            raise RuntimeError(f"取图形验证码失败: {cap}")

        key = f"aicabinet:captcha:{captcha_id}"
        proc = subprocess.run(
            ["docker", "exec", REDIS_CONTAINER, "redis-cli", "GET", key],
            capture_output=True, text=True, timeout=20,
        )
        code = proc.stdout.strip()
        if not code:
            raise RuntimeError(
                f"从 Redis 取验证码为空（key={key}）: stderr={proc.stderr.strip()!r}"
            )

        login = self._request(
            "POST",
            "/api/v2/auth/admin-password-login",
            {
                "phoneNumber": OPS_PHONE,
                "password": OPS_PASSWORD,
                "captchaId": captcha_id,
                "captchaCode": code,
            },
            auth=False,
        )
        token = (login.get("data") or {}).get("token")
        if not token:
            raise RuntimeError(f"运营登录失败: {login}")
        self.token = token

    # ── 系统配置 ────────────────────────────────────────────────────────────

    def list_config(self) -> dict[str, str]:
        resp = self._request("GET", "/api/v2/ops/admin/system-configs")
        rows = resp.get("data") or []
        return {r["configKey"]: (r.get("configValue") or "") for r in rows}

    def put_config(self, key: str, value: str, description: str = "验证脚本写入") -> None:
        resp = self._request(
            "PUT",
            "/api/v2/ops/admin/system-configs",
            {"configKey": key, "configValue": value, "description": description},
        )
        if resp.get("code") != 0:
            raise RuntimeError(f"写配置 {key} 失败: {resp}")

    # ── 流量 / Tempo ────────────────────────────────────────────────────────

    def burst(self) -> int:
        ok = 0
        for _ in range(TRAFFIC_BURST):
            if self._http_status(TRAFFIC_PATH) == 200:
                ok += 1
        return ok

    def tempo_trace_count(self, start: int, end: int) -> int:
        query = urllib.parse.urlencode(
            {"tags": "service.name=trade-service", "start": start, "end": end, "limit": 100}
        )
        with urllib.request.urlopen(f"{self.tempo_url}/api/search?{query}", timeout=20) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
        return len(payload.get("traces") or [])

    def measure(self, label: str) -> tuple[int, int]:
        """造一段流量并测该窗口的 trace 数。返回 (traffic_ok, traces)。"""
        time.sleep(self.settle)
        start = int(time.time()) - 3
        http_ok = self.burst()
        time.sleep(self.ingest_wait)
        end = int(time.time()) + 3
        count = self.tempo_trace_count(start, end)
        print(f"    [{label}] 流量 {http_ok}/{TRAFFIC_BURST} 成功，窗口 [{start},{end}] traces = {count}")
        return http_ok, count

    # ── 断言 ────────────────────────────────────────────────────────────────

    def check(self, name: str, ok: bool, detail: str) -> None:
        mark = "✅" if ok else "❌"
        print(f"  {mark} {name} :: {detail}")
        if not ok:
            self.failures.append(f"{name}（{detail}）")

    def expect(self, name: str, actual, predicate, detail_fn) -> None:
        ok = predicate(actual)
        self.check(name, ok, detail_fn(actual))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fast", action="store_true", help="缩短等待（结论不完整，仅快速冒烟）")
    parser.add_argument("--base-url", default=BASE_URL)
    parser.add_argument("--tempo-url", default=TEMPO_URL)
    args = parser.parse_args()

    probe = Probe(args.base_url, args.tempo_url, args.fast)
    print(f"==> 探针目标 trade-service={args.base_url}  tempo={args.tempo_url}")
    if args.fast:
        print("    ⚠️ --fast 模式：等待被缩短，结论不完整（不作为验收证据）")

    print("\n==> Phase 0：运营登录 + 功能开关注册表端点")
    probe.login()
    catalog = probe._request("GET", "/api/v2/ops/admin/system-configs/feature-flags")
    flags = (catalog.get("data") or {}).get("flags") or []
    groups = (catalog.get("data") or {}).get("groups") or []
    probe.check("feature-flags 端点可用", catalog.get("code") == 0, f"code={catalog.get('code')}")
    probe.expect("注册表 flag 数量", len(flags), lambda n: n == 56, lambda n: f"实测 {n}，期望 56")
    probe.expect(
        "注册表分组数", len(groups), lambda n: n == 15, lambda n: f"实测 {n}，期望 15"
    )
    tracing = next((f for f in flags if f["key"] == KEY_TRACING_SWITCH), None)
    probe.check("追踪开关在注册表内", tracing is not None, f"key={KEY_TRACING_SWITCH}")
    if tracing:
        probe.check(
            "追踪开关是三态 SELECT",
            tracing["type"] == "SELECT" and {o["value"] for o in (tracing.get("options") or [])} == {"", "true", "false"},
            f"type={tracing['type']} options={[o['value'] for o in (tracing.get('options') or [])]}",
        )
    endpoint_flag = next((f for f in flags if f["key"] == KEY_OTLP_ENDPOINT), None)
    probe.check("OTLP 端点项在注册表内", endpoint_flag is not None, f"key={KEY_OTLP_ENDPOINT}")

    original = probe.list_config()
    origin_endpoint = original.get(KEY_OTLP_ENDPOINT, "")
    origin_switch = original.get(KEY_TRACING_SWITCH, "")
    print(f"    原始值 endpoint=[{origin_endpoint}] switch=[{origin_switch}]（结束后还原）")

    try:
        print("\n==> Phase 1：端点留空 ⇒ 基线应查不到 trace")
        probe.put_config(KEY_OTLP_ENDPOINT, "", "验证脚本：清空端点")
        probe.put_config(KEY_TRACING_SWITCH, "", "验证脚本：开关留空")
        _, baseline = probe.measure("基线 · 无端点")
        probe.expect(
            "无端点时不外发", baseline, lambda n: n == 0, lambda n: f"traces={n}，期望 0"
        )

        print("\n==> Phase 2：写入端点（不重启）⇒ trace 应出现")
        probe.put_config(KEY_OTLP_ENDPOINT, TEMPO_ENDPOINT_VALUE, "验证脚本：运行期端点")
        _, enabled = probe.measure("开启 · 跟随端点")
        probe.expect(
            "写完端点后开始外发", enabled, lambda n: n > 0, lambda n: f"traces={n}，期望 >0"
        )
        # 端点生效日志：窗口放宽到近 5 分钟，避免秒级窗口把日志切掉（会造成假红）
        logs = subprocess.run(
            ["docker", "logs", "--since", "5m", "ai-cabinet-trade-service-1"],
            capture_output=True, text=True, timeout=30,
        )
        combined = (logs.stdout or "") + (logs.stderr or "")
        probe.check(
            "端点生效日志出现（无需重启）",
            "OTLP 导出端点已生效" in combined,
            "日志含 'OTLP 导出端点已生效'",
        )

        print("\n==> Phase 3：tracing_enabled=false（不重启）⇒ 应停止外发")
        probe.put_config(KEY_TRACING_SWITCH, "false", "验证脚本：强制关闭")
        _, disabled = probe.measure("强制关闭")
        probe.expect(
            "关闭后不再外发", disabled, lambda n: n == 0, lambda n: f"traces={n}，期望 0"
        )

        print("\n==> Phase 4：改回留空（不重启）⇒ 应恢复外发（三态闭合）")
        probe.put_config(KEY_TRACING_SWITCH, "", "验证脚本：开关留空")
        _, restored = probe.measure("恢复 · 跟随端点")
        probe.expect(
            "恢复后重新外发", restored, lambda n: n > 0, lambda n: f"traces={n}，期望 >0"
        )

        print("\n==> 判别力自检（防假绿）")
        probe.check(
            "ON/OFF/ON 单调性成立",
            enabled > 0 and disabled == 0 and restored > 0,
            f"on={enabled} off={disabled} on={restored}",
        )
    finally:
        print("\n==> 还原原始配置")
        probe.put_config(KEY_TRACING_SWITCH, origin_switch, "还原（验证脚本）")
        probe.put_config(KEY_OTLP_ENDPOINT, origin_endpoint, "还原（验证脚本）")
        after = probe.list_config()
        probe.check(
            "配置已还原",
            after.get(KEY_TRACING_SWITCH, "") == origin_switch
            and after.get(KEY_OTLP_ENDPOINT, "") == origin_endpoint,
            f"endpoint=[{after.get(KEY_OTLP_ENDPOINT, '')}] switch=[{after.get(KEY_TRACING_SWITCH, '')}]",
        )

    print("\n" + "=" * 72)
    if probe.failures:
        print(f"结论：失败 {len(probe.failures)} 项")
        for item in probe.failures:
            print(f"  ✗ {item}")
        return 1
    suffix = "（--fast，非验收证据）" if args.fast else ""
    print(f"结论：全部断言通过 ✅{suffix}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
