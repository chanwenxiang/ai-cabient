#!/usr/bin/env python3
"""识别结果落库（recognition_result）· 默认同步模式端到端验证。

背景：`recognition_result` 自 `V1__init_schema.sql` 建表起长期无运行期写入者（全仓 java/xml 零引用、
线上 0 行）。本批在结算侧的识别收敛处（`SettlementRecognitionService#processRecognitionResultUnlocked`）
补上唯一写入点，一处覆盖同步关门 / 异步回调 / 开发上传三条路径。

为什么本脚本必须跑「默认栈」而**不注入任何 -D 覆盖**：
`aicabinet.vision-async.enabled` 在 `application.yml` 里硬编码 false，且 dev/staging/prod 三个 profile
均无覆盖 ⇒ 实际发布口径走的就是**同步关门**路径。只在异步下验证落库，等于验证了一个发布配置里
根本不会启用的通道（异步通道另见 `verify-vision-result-ingest.py`，它需要显式开 -D）。

断言链（每条都打印实测值）：
  A 门外：会话尚在 SHOPPING 时不落库（不该写的不会被写）
  B 关门后无需外部输入即离开识别态（证明确实是同步模式，异步模式会停住等待端侧结果）
  C 该会话 recognition_result 恰 1 行，且字段满足列约束（items 键为 quantity，非 qty）
  D 不变量：全表无「同会话多行」

用法：
  python scripts/devops/verify-recognition-result-persistence.py
"""
from __future__ import annotations

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:18080"
INTERNAL_KEY = "dev-internal-key-change-me"
DEVICE_ID = "330449777078"
PHONE = "13800138000"
PASSWORD = "123456"
PG = ["docker", "exec", "ai-cabinet-postgres-1", "psql", "-U", "aicabinet", "-d", "aicabinet", "-tA", "-c"]
TERMINAL = {"COMPLETED", "DISPUTED", "FAILED", "CANCELLED"}

RESULTS: list[tuple[bool, str]] = []


def check(ok: bool, label: str) -> bool:
    RESULTS.append((ok, label))
    print(f"   [{'PASS' if ok else 'FAIL'}] {label}", flush=True)
    return ok


def api(method: str, path: str, body: dict | None = None, headers: dict | None = None) -> tuple[int, dict]:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw or "{}")
        except json.JSONDecodeError:
            return e.code, {"raw": raw}


def psql(sql: str) -> str:
    out = subprocess.run(PG + [sql], capture_output=True, text=True, timeout=30)
    return out.stdout.strip()


def login() -> str:
    _, body = api("POST", "/api/v2/auth/password-login", {"phoneNumber": PHONE, "password": PASSWORD})
    return (body.get("data") or {}).get("token", "")


def session_state(token: str, sid: str) -> str:
    _, body = api("GET", f"/api/v2/sessions/{sid}", headers={"Authorization": f"Bearer {token}"})
    return (body.get("data") or {}).get("state", "")


def wait_left_recognizing(token: str, sid: str, seconds: int = 30) -> str:
    """同步模式下关门即结算；异步模式会停在 RECOGNIZING 直到端侧上报。"""
    deadline = time.time() + seconds
    state = ""
    while time.time() < deadline:
        state = session_state(token, sid)
        if state in TERMINAL:
            return state
        time.sleep(1)
    return state


def main() -> int:
    print("== 前置检查 ==")
    try:
        status, _ = api("GET", "/actuator/health")
    except Exception as exc:  # noqa: BLE001
        print(f"!! trade-service 不可达：{exc}")
        return 2
    if status != 200:
        print(f"!! trade-service health={status}")
        return 2
    print(f"   health={status}")

    token = login()
    if not token:
        print("!! 消费者登录失败")
        return 2

    _, dev = api("GET", f"/api/v2/devices/{DEVICE_ID}/status", headers={"Authorization": f"Bearer {token}"})
    d = dev.get("data") or {}
    print(f"   设备 online={d.get('online')} available={d.get('available')}")
    if not d.get("available"):
        psql(f"UPDATE shopping_session SET state='CANCELLED' WHERE device_id='{DEVICE_ID}' "
             f"AND state IN ('CREATED','OPENING','SHOPPING','WAITING_UPLOAD','RECOGNIZING');")
        time.sleep(1)

    # ---- A 开门（尚不关门）----
    print("\n== A 开门 → SHOPPING（此时不应有识别结果）==")
    _, created = api("POST", "/api/v2/sessions",
                     {"deviceId": DEVICE_ID, "idempotencyKey": f"rrp-{int(time.time())}"},
                     {"Authorization": f"Bearer {token}"})
    sid = (created.get("data") or {}).get("sessionId", "")
    if not sid:
        print(f"!! 创建会话失败：{created}")
        return 2
    print(f"   sessionId={sid}")
    time.sleep(2)
    state = session_state(token, sid)
    print(f"   当前状态={state}")
    pending = psql(f"SELECT count(*) FROM recognition_result WHERE session_id='{sid}';")
    check(pending == "0", f"A1 会话未结算时识别结果行数={pending}（应为 0，不该写的不会被写）")

    # ---- B 关门 → 同步结算 ----
    print("\n== B 关门 ==")
    ts = int(time.time() * 1000)
    api("POST", "/internal/v1/sessions/door-event",
        {"sessionId": sid, "deviceId": DEVICE_ID, "doorState": "CLOSED",
         "timestamp": ts, "uploadStatus": "UPLOADED"},
        {"X-Internal-Api-Key": INTERNAL_KEY})
    final = wait_left_recognizing(token, sid)
    if final == "RECOGNIZING":
        print(f"\n!! 关门后停在 RECOGNIZING：当前栈处于异步识别模式。")
        print("   本脚本针对**默认同步栈**（aicabinet.vision-async.enabled=false）。")
        print("   异步通道请用 verify-vision-result-ingest.py（它需要显式 -D 开启）。")
        psql(f"UPDATE shopping_session SET state='CANCELLED' WHERE session_id='{sid}';")
        return 2
    check(final in TERMINAL, f"B1 关门后无需外部输入即离开识别态，终态={final}（同步模式成立）")

    # ---- C 落库 ----
    print("\n== C 识别结果落库 ==")
    cnt = psql(f"SELECT count(*) FROM recognition_result WHERE session_id='{sid}';")
    check(cnt == "1", f"C1 该会话识别结果行数={cnt}（应为 1）")
    row = psql("SELECT task_id || '|' || fusion_mode || '|' || coalesce(model_version,'') || '|' || "
               "coalesce(need_review::text,'') || '|' || coalesce(overall_confidence::text,'') || '|' || "
               f"items::text FROM recognition_result WHERE session_id='{sid}';")
    print(f"   落库行(taskId|融合模式|模型版本|需复核|整体置信度|items) = {row}")
    f = row.split("|") if row else []
    check(len(f) == 6 and bool(f[0]), f"C2 taskId 非空 = {f[0] if f else ''}")
    check(len(f) == 6 and f[1] == "VISION", f"C3 融合模式 = {f[1] if len(f) == 6 else ''}（应为 VISION）")
    check(len(f) == 6 and bool(f[2]), f"C4 modelVersion 非空 = {f[2] if len(f) == 6 else ''}")
    check(len(f) == 6 and f[3] in ("true", "false"), f"C5 needReview 为布尔 = {f[3] if len(f) == 6 else ''}")
    check(len(f) == 6 and '"quantity"' in f[5] and '"qty"' not in f[5],
          "C6 items 键为 quantity（非 qty）")
    check(len(f) == 6 and bool(f[5]) and f[5] != "[]", f"C7 items 非空 = {f[5] if len(f) == 6 else ''}")

    # ---- D 全表不变量 ----
    print("\n== D 全表不变量 ==")
    dup = psql("SELECT count(*) FROM (SELECT session_id FROM recognition_result "
               "GROUP BY session_id HAVING count(*) > 1) t;")
    check(dup == "0", f"D1 无「同会话多行」（重复会话数={dup}）")

    failed = [label for ok, label in RESULTS if not ok]
    print(f"\n== 汇总：{len(RESULTS) - len(failed)}/{len(RESULTS)} 通过 ==")
    for label in failed:
        print(f"   FAIL {label}")
    print("端到端验证", "通过" if not failed else "失败")
    print(f"\n本次会话 {sid}：终态 {final}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
