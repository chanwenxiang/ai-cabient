#!/usr/bin/env python3
"""P0-1 端侧识别结果直报 · 真实栈端到端验证。

验证对象：POST /internal/v1/vision/edge-results
（路径取自路线图 P0-1 的既定契约，见 docs/COMPETITOR_BENCHMARK_AND_ROADMAP_2026-09-17.md）

前置（脚本会自检并在不满足时明确报错，而不是给你一个「绿」）：
  1. trade-service 健康；
  2. **异步识别开启**（`-Daicabinet.vision-async.enabled=true`）——
     同步模式下关门即调 vision-service 并立刻结算，会话不会停在 RECOGNIZING，
     端侧直报就没有受理窗口。脚本会先关门看是否停在 RECOGNIZING，不满足即中止。

断言链（每条都打印实测值，不打印「应为」）：
  A 开门 → SHOPPING
  B 关门 → RECOGNIZING（等端侧结果，不是自动结算）
  C 端侧直报 → accepted=true / outcome=PROCESSED / sessionState=COMPLETED
  D 会话 COMPLETED + 订单 PAID + 金额=商品价 + 余额精确扣减
  E 负对照：重复上报（幂等，不重复扣款/不重复建单/不重复落库）
  F 负对照：缺 modelVersion→400、quantity<=0→400、会话不存在→404、无密钥→401、modelVersion 超列宽→400
  G 识别结果落库：recognition_result 出现该会话 1 行且字段与上报一致（items 键为 quantity）
  H 全表不变量：无「同会话多行」

用法：
  python scripts/devops/verify-vision-result-ingest.py
"""
from __future__ import annotations

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = "http://127.0.0.1:18080"
INTERNAL_KEY = "dev-internal-key-change-me"
DEVICE_ID = "330449777078"
PHONE = "13800138000"
PASSWORD = "123456"
SKU = "SKU-DEMO-001"
MODEL_VERSION = "QUECTEL-EDGE-1.5.2"
PG = ["docker", "exec", "ai-cabinet-postgres-1", "psql", "-U", "aicabinet", "-d", "aicabinet", "-tA", "-c"]

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
    _, body = api("POST", "/api/v2/auth/password-login",
                  {"phoneNumber": PHONE, "password": PASSWORD})
    return (body.get("data") or {}).get("token", "")


def balance(token: str) -> int:
    _, body = api("GET", "/api/v2/account", headers={"Authorization": f"Bearer {token}"})
    return int((body.get("data") or {}).get("balanceCents") or 0)


def session_state(token: str, sid: str) -> dict:
    _, body = api("GET", f"/api/v2/sessions/{sid}", headers={"Authorization": f"Bearer {token}"})
    return body.get("data") or {}


def ingest(payload: dict) -> tuple[int, dict]:
    return api("POST", "/internal/v1/vision/edge-results", payload,
               {"X-Internal-Api-Key": INTERNAL_KEY})


def wait_state(token: str, sid: str, want: str, seconds: int = 25) -> str:
    deadline = time.time() + seconds
    state = ""
    while time.time() < deadline:
        state = session_state(token, sid).get("state", "")
        if state == want:
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
    print(f"   登录成功 token长度={len(token)}")

    _, dev = api("GET", f"/api/v2/devices/{DEVICE_ID}/status",
                 headers={"Authorization": f"Bearer {token}"})
    d = dev.get("data") or {}
    print(f"   设备 online={d.get('online')} available={d.get('available')}")
    if not d.get("available"):
        psql(f"UPDATE shopping_session SET state='CANCELLED' WHERE device_id='{DEVICE_ID}' "
             f"AND state IN ('CREATED','OPENING','SHOPPING','WAITING_UPLOAD','RECOGNIZING');")
        time.sleep(1)

    balance_before = balance(token)
    print(f"   余额(初始) = {balance_before}")
    if balance_before < 1000:
        print("!! 余额不足 1000 分，无法完成扣款验证")
        return 2

    # ---- A/B 开门 -> 关门 -> RECOGNIZING ----
    print("\n== A/B 开门 → 关门 ==")
    _, created = api("POST", "/api/v2/sessions",
                     {"deviceId": DEVICE_ID, "idempotencyKey": f"p01-{int(time.time())}"},
                     {"Authorization": f"Bearer {token}"})
    sid = (created.get("data") or {}).get("sessionId", "")
    if not sid:
        print(f"!! 创建会话失败：{created}")
        return 2
    print(f"   sessionId={sid}")
    check(wait_state(token, sid, "SHOPPING") == "SHOPPING", "A 开门后进入 SHOPPING")

    ts = int(time.time() * 1000)
    api("POST", "/internal/v1/sessions/door-event",
        {"sessionId": sid, "deviceId": DEVICE_ID, "doorState": "CLOSED",
         "timestamp": ts, "uploadStatus": "UPLOADED"},
        {"X-Internal-Api-Key": INTERNAL_KEY})
    state = wait_state(token, sid, "RECOGNIZING", seconds=10)
    if state != "RECOGNIZING":
        print(f"\n!! 关门后状态 = {state}，未停在 RECOGNIZING。")
        print("   多半是「异步识别」没开：本端点需要会话停在识别态才有受理窗口。")
        print("   用 -Daicabinet.vision-async.enabled=true 重启 trade-service 后重跑。")
        return 2
    check(True, "B 关门后停在 RECOGNIZING（异步识别已生效，等待端侧结果）")

    # ---- C/D 端侧直报 -> 结算 ----
    print("\n== C/D 端侧直报识别结果 ==")
    payload = {
        "sessionId": sid,
        "taskId": f"EDGE-{sid}",
        "traceId": "edge-trace-verify",
        "items": [{"skuId": SKU, "quantity": 1, "confidence": 0.96}],
        "overallConfidence": 0.96,
        "needReview": False,
        "modelVersion": MODEL_VERSION,
        "detectedClasses": ["demo-item"],
        "provider": "QUECTEL",
    }
    code, body = ingest(payload)
    receipt = body.get("data") or {}
    print(f"   回执 = {json.dumps(receipt, ensure_ascii=False)}")
    check(code == 200 and receipt.get("accepted") is True, f"C1 HTTP={code} accepted=true")
    check(receipt.get("outcome") == "PROCESSED", f"C2 outcome={receipt.get('outcome')}")

    final = wait_state(token, sid, "COMPLETED", seconds=20)
    check(final == "COMPLETED", f"D1 会话终态={final}")

    order_id = psql(f"SELECT order_id FROM cabinet_order WHERE session_id='{sid}';")
    order_status = psql(f"SELECT status FROM cabinet_order WHERE session_id='{sid}';")
    amount = psql(f"SELECT total_amount_cents FROM cabinet_order WHERE session_id='{sid}';")
    coupon = psql(f"SELECT coalesce(coupon_discount_cents,0) FROM cabinet_order WHERE order_id='{order_id}';")
    line = psql("SELECT sku_id || '|' || quantity || '|' || unit_price_cents || '|' "
                "|| coalesce(confidence::text,'') || '|' || line_amount_cents "
                f"FROM cabinet_order_line WHERE order_id='{order_id}';")
    print(f"   orderId={order_id} status={order_status} 总额={amount} 优惠={coupon}")
    print(f"   订单行(sku|数量|单价|置信度|行金额) = {line}")
    check(bool(order_id) and order_status == "PAID", f"D2 订单已生成且 PAID（{order_status}）")

    # D3 才是本项的核心：端侧上报的商品与置信度必须真的落进订单行（而不是只走了个过场）
    parts = line.split("|") if line else []
    check(len(parts) == 5 and parts[0] == SKU and parts[1] == "1" and parts[3].startswith("0.96"),
          f"D3 订单行内容 = 端侧上报内容（sku={SKU} 数量=1 置信度=0.96）")

    # D4 账目自洽：总额 + 优惠抵扣 = 行金额合计（消费者可能有优惠券，故不与标价直接比）
    line_amount = int(parts[4]) if len(parts) == 5 else 0
    check(int(amount) + int(coupon) == line_amount,
          f"D4 账目自洽：总额{amount} + 优惠{coupon} = 行金额{line_amount}")

    balance_after = balance(token)
    expected = balance_before - int(amount)
    print(f"   余额 {balance_before} → {balance_after}（应为 {expected}）")
    check(balance_after == expected, f"D5 余额精确扣减 {balance_before}-{amount}={expected}")

    # ---- G 识别结果落库（recognition_result）----
    # 该表自 V1 建表起长期无人写入（0 行），本批补齐；此处验证「端侧直报也被记账」。
    print("\n== G 识别结果落库 ==")
    rr_count = psql(f"SELECT count(*) FROM recognition_result WHERE session_id='{sid}';")
    check(rr_count == "1", f"G1 该会话识别结果行数={rr_count}（应为 1）")
    rr = psql("SELECT task_id || '|' || fusion_mode || '|' || coalesce(model_version,'') || '|' || "
              "coalesce(need_review::text,'') || '|' || coalesce(overall_confidence::text,'') || '|' || "
              f"items::text FROM recognition_result WHERE session_id='{sid}';")
    print(f"   落库行(taskId|融合模式|模型版本|需复核|整体置信度|items) = {rr}")
    g = rr.split("|") if rr else []
    check(len(g) == 6 and g[0] == f"EDGE-{sid}", f"G2 taskId={g[0] if g else ''}（应为 EDGE-{sid}）")
    check(len(g) == 6 and g[1] == "VISION", f"G3 融合模式={g[1] if len(g) == 6 else ''}（应为 VISION）")
    check(len(g) == 6 and g[2] == MODEL_VERSION, f"G4 模型版本={g[2] if len(g) == 6 else ''}（应为 {MODEL_VERSION}）")
    check(len(g) == 6 and g[3] == "false", f"G5 需复核={g[3] if len(g) == 6 else ''}（应为 false）")
    # items 的键必须是 quantity（与 Kafka 报文一致）；V222 demo seed 用的是 qty，跟着它写消费者会取不到数量
    check(len(g) == 6 and '"quantity"' in g[5] and '"qty"' not in g[5] and SKU in g[5],
          f"G6 items 含 sku={SKU} 且键为 quantity（非 qty）")

    # ---- E 幂等 ----
    print("\n== E 负对照：重复上报 ==")
    code2, body2 = ingest(payload)
    r2 = body2.get("data") or {}
    check(code2 == 200 and r2.get("outcome") == "ALREADY_HANDLED",
          f"E1 二次上报 outcome={r2.get('outcome')}（不重复采纳）")
    check(balance(token) == balance_after, "E2 二次上报后余额不变（未重复扣款）")
    check(psql(f"SELECT count(*) FROM cabinet_order WHERE session_id='{sid}';") == "1",
          "E3 该会话订单数仍为 1（未重复建单）")
    check(psql(f"SELECT count(*) FROM recognition_result WHERE session_id='{sid}';") == "1",
          "E4 该会话识别结果仍为 1 行（未重复落库）")

    # ---- F 入口 fail-closed ----
    print("\n== F 负对照：入口校验 ==")
    no_mv = {"sessionId": sid, "items": [{"skuId": SKU, "quantity": 1, "confidence": 0.9}],
             "needReview": False}
    code3, _ = ingest(no_mv)
    check(code3 == 400, f"F1 缺 modelVersion → HTTP {code3}（400）")

    bad_qty = {"sessionId": sid, "items": [{"skuId": SKU, "quantity": 0, "confidence": 0.9}],
               "needReview": False, "modelVersion": MODEL_VERSION}
    code4, _ = ingest(bad_qty)
    check(code4 == 400, f"F2 quantity=0 → HTTP {code4}（400）")

    missing = {"sessionId": "NO-SUCH-SESSION-000", "items": [],
               "needReview": True, "modelVersion": MODEL_VERSION}
    code5, _ = ingest(missing)
    check(code5 == 404, f"F3 会话不存在 → HTTP {code5}（404）")

    req = urllib.request.Request(BASE + "/internal/v1/vision/edge-results",
                                data=b"{}", method="POST")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            no_key = resp.status
    except urllib.error.HTTPError as e:
        no_key = e.code
    check(no_key == 401, f"F4 无内部密钥 → HTTP {no_key}（401）")

    # F5：model_version 超列宽必须在入口拒绝。该列由 V279 放宽到 64；若入口放行，超长值会在写库时被
    # PostgreSQL 拒收，进而被 fail-hard 放大成「结算一起回滚」——把「平台存不下」变成业务失败。
    too_long = {"sessionId": sid, "items": [{"skuId": SKU, "quantity": 1, "confidence": 0.9}],
                "needReview": False, "modelVersion": "X" * 65}
    code6, _ = ingest(too_long)
    check(code6 == 400, f"F5 modelVersion=65 字符（列宽 64）→ HTTP {code6}（400）")

    # ---- H 全表不变量：会话维度唯一 ----
    dup = psql("SELECT count(*) FROM (SELECT session_id FROM recognition_result "
               "GROUP BY session_id HAVING count(*) > 1) t;")
    check(dup == "0", f"H1 全表无「同会话多行」（重复会话数={dup}）")

    failed = [label for ok, label in RESULTS if not ok]
    print(f"\n== 汇总：{len(RESULTS) - len(failed)}/{len(RESULTS)} 通过 ==")
    for label in failed:
        print(f"   FAIL {label}")
    print("端到端验证", "通过" if not failed else "失败")
    print(f"\n本次会话 {sid}：余额 {balance_before} → {balance(token)}，订单 {order_id}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
