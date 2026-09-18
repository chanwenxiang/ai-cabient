#!/usr/bin/env python3
"""一次性补丁：统一消费者端单号展示口径（`shortBizNo` 截断 → `displayBizNo` 全号）。

背景（2026-09-18）
------------------
`packages/shared-uni/src/format.ts` 的 `shortBizNo(id, maxLen)` 会取**末尾** maxLen 位；
consumer-mp 有 6 处调用它，而订单详情页用的是 `displayBizNo`（全号）⇒ 同一单号在
「列表」与「详情」两处对不上，客服/运营按列表里的号在详情里搜不到。
admin（`DashboardView.vue:619-626`）与 merchant-mp（`orders.vue:475`）早已统一为全号，
本轮把 consumer-mp 补齐（同族缺陷的最后一处）。

同时补 `word-break: break-all`：原来号被程序截短，必然不换行；改成全号后若不给出
断行机会，长号会**撑破/溢出**容器（等于把「截断」换成「溢出」，缺陷没消失只是换了形态）。
`.order-tag` 例外：chip 保留 `text-overflow: ellipsis`，补 `white-space: nowrap`
让省略号真正生效（同一行的 `.order-id` 与详情页都给全号）。

用法
----
    python scripts/devops/patch-consumer-bizno-display.py            # 应用
    python scripts/devops/patch-consumer-bizno-display.py revert     # 还原（A/B 对照用）

幂等护栏：每条替换都要求**精确命中次数**；已应用过（命中 0 且新串已在）判 ALREADY，
不做二次改写，也不静默跳过 —— 任何 MISS 都会让退出码非 0。
"""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

ORDERS = "clients/consumer-mp/src/pages/orders/orders.vue"
RECHARGE = "clients/consumer-mp/src/pages/recharge/recharge.vue"
DISPUTE = "clients/consumer-mp/src/pages/dispute/detail.vue"
BALANCE = "clients/consumer-mp/src/pages/balance/balance.vue"
FORMAT_TS = "packages/shared-uni/src/format.ts"

DEPRECATION = """/**
 * @deprecated 不要用它展示单号（2026-09-18 起）。
 * 列表取末尾 N 位、详情页给全号 ⇒ 同一单号两处对不上，客服按列表号搜不到。
 * 消费者端 6 处调用已全部改为 `displayBizNo`（全号）；确需缩短请走 UI 侧的
 * `text-overflow: ellipsis`，不要在数据层永久丢掉字符。
 */
export function shortBizNo("""

# (文件, 旧串, 新串, 期望命中次数)
REPLACEMENTS: list[tuple[str, str, str, int]] = [
    # ── orders.vue：列表单号 + 支付流水号 chip ────────────────────────────────
    (ORDERS, "  shortBizNo,\n", "  displayBizNo,\n", 1),
    (ORDERS, "shortId(", "orderIdDisplay(", 2),
    (ORDERS, "return shortBizNo(id, 12, '暂无单号');", "return displayBizNo(id, '暂无单号');", 1),
    (ORDERS, "payTradeShort(o)", "payTradeDisplay(o)", 2),
    (ORDERS, "function payTradeShort(o: OrderSummary) {", "function payTradeDisplay(o: OrderSummary) {", 1),
    (ORDERS, "return shortBizNo(id, 10);", "return displayBizNo(id);", 1),
    (ORDERS, ".order-id {\n  display: block;\n  margin-top: 6rpx;\n",
     ".order-id {\n  display: block;\n  word-break: break-all;\n  margin-top: 6rpx;\n", 1),
    (ORDERS, ".order-tag {\n  font-size: var(--font-size-xs);\n",
     ".order-tag {\n  white-space: nowrap;\n  font-size: var(--font-size-xs);\n", 1),
    # ── recharge.vue：退款申请号 + 充值单号 ──────────────────────────────────
    (RECHARGE, "  shortBizNo,\n", "  displayBizNo,\n", 1),
    (RECHARGE, "shortBizNo(r.requestNo)", "displayBizNo(r.requestNo)", 1),
    (RECHARGE, "shortBizNo(r.orderId)", "displayBizNo(r.orderId)", 1),
    (RECHARGE, ".refund-meta {\n  display: block;\n",
     ".refund-meta {\n  display: block;\n  word-break: break-all;\n", 1),
    (RECHARGE,
     ".record-meta {\n  display: flex;\n  align-items: center;\n  gap: 12rpx;\n"
     "  margin-top: 6rpx;\n  flex-wrap: wrap;\n}\n",
     ".record-meta {\n  display: flex;\n  align-items: center;\n  gap: 12rpx;\n"
     "  margin-top: 6rpx;\n  flex-wrap: wrap;\n}\n"
     ".record-id {\n  word-break: break-all;\n}\n", 1),
    # ── dispute/detail.vue：争议单里的会话号 ────────────────────────────────
    (DISPUTE, "  shortBizNo,\n", "  displayBizNo,\n", 1),
    (DISPUTE, "shortId(", "idDisplay(", 2),
    (DISPUTE, "return shortBizNo(id, 12, '暂无');", "return displayBizNo(id, '暂无');", 1),
    (DISPUTE, ".info-value {\n  font-size: var(--font-size-caption);\n",
     ".info-value {\n  font-size: var(--font-size-caption);\n  word-break: break-all;\n", 1),
    # ── balance.vue：余额流水单号 ───────────────────────────────────────────
    (BALANCE,
     "import { formatDateTimeShort, fmtMoney, shortBizNo } from '@aicabinet/shared-uni/format';",
     "import { formatDateTimeShort, fmtMoney, displayBizNo } from '@aicabinet/shared-uni/format';", 1),
    (BALANCE, "shortBizNo(item.businessId)", "displayBizNo(item.businessId)", 1),
    (BALANCE, ".log-meta {\n  display: block;\n  margin-top: 6rpx;\n",
     ".log-meta {\n  display: block;\n  word-break: break-all;\n  margin-top: 6rpx;\n", 1),
    # ── shared helper 标废弃（留函数体，防外部消费者被硬删）─────────────────
    (FORMAT_TS, "/** 列表短号：纯数字，超长取末尾 */\nexport function shortBizNo(",
     DEPRECATION, 1),
]


def digest(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest()[:12]


def main() -> int:
    revert = len(sys.argv) > 1 and sys.argv[1].lower() == "revert"

    touched: dict[str, list[tuple[str, str, str]]] = {}
    for rel, old, new, expected in REPLACEMENTS:
        if revert:
            old, new = new, old
            # 还原时也要防「新串」被写坏：命中次数按 old 计
        touched.setdefault(rel, []).append((old, new, str(expected)))

    failures: list[str] = []
    already = 0
    applied = 0

    for rel, edits in touched.items():
        path = ROOT / rel
        if not path.exists():
            failures.append(f"{rel}: 文件不存在")
            continue
        # 🔴 必须按字节读写：Windows 下 `Path.read_text/write_text` 会做 newline 转换，
        # 把仓库要求的 LF 悄悄写成 CRLF（2026-09-18 实测踩到，5 个文件被污染）。
        before = path.read_bytes().decode("utf-8")
        text = before
        for old, new, expected in edits:
            n = text.count(old)
            if n == 0:
                if new in text or new.replace("\n", "") in text.replace("\n", ""):
                    already += 1
                    print(f"  ALREADY  {rel}  <-  {old.splitlines()[0][:56]!r}")
                    continue
                failures.append(f"{rel}: MISS  {old.splitlines()[0][:70]!r} (期望 {expected} 次)")
                continue
            if expected != "*" and n != int(expected):
                failures.append(f"{rel}: COUNT {old.splitlines()[0][:56]!r} 命中 {n} 次，期望 {expected}")
                continue
            text = text.replace(old, new)
            applied += 1
            print(f"  OK       {rel}  <-  {old.splitlines()[0][:56]!r}")
        if text != before:
            path.write_bytes(text.encode("utf-8"))
            print(f"  WRITE    {rel}  md5 {digest(before)} -> {digest(text)}")

    print()
    print(f"applied={applied} already={already} failed={len(failures)} mode={'revert' if revert else 'apply'}")
    for f in failures:
        print(f"  FAIL {f}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
