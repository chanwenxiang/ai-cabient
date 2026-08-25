# -*- coding: utf-8 -*-
"""Scan three clients for table/list field inventory."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def admin_inventory() -> list[tuple[str, list[str]]]:
    views = ROOT / "clients/admin-vue/src/views"
    out = []
    for p in sorted(views.rglob("*.vue")):
        text = p.read_text(encoding="utf-8", errors="ignore")
        labels: list[str] = []
        for m in re.finditer(
            r"<el-table-column\b([^>]*)>",
            text,
            flags=re.I,
        ):
            attrs = m.group(1)
            lab = re.search(r'\blabel="([^"]+)"', attrs)
            prop = re.search(r'\bprop="([^"]+)"', attrs)
            if not lab:
                continue
            label = lab.group(1)
            if prop:
                labels.append(f"{prop.group(1)}:{label}")
            else:
                labels.append(label)
        # dedupe by label
        seen = set()
        uniq = []
        for x in labels:
            lab = x.split(":")[-1]
            if lab not in seen:
                seen.add(lab)
                uniq.append(x)
        rel = str(p.relative_to(views)).replace("\\", "/")
        out.append((rel, uniq))
    return out


def mp_inventory(pages_dir: Path) -> list[tuple[str, list[str], list[str]]]:
    out = []
    for p in sorted(pages_dir.rglob("*.vue")):
        text = p.read_text(encoding="utf-8", errors="ignore")
        labels = re.findall(
            r">([\u4e00-\u9fff][\u4e00-\u9fffA-Za-z0-9/（）()·\-]{0,24})<",
            text,
        )
        labels += re.findall(
            r'(?:label|title|placeholder)="([\u4e00-\u9fff][^"]{0,24})"',
            text,
        )
        fields = re.findall(
            r"(?:row|item|order|device|task|alert|msg|it|d|o|r|m)\.([a-zA-Z][a-zA-Z0-9_]*)",
            text,
        )
        fields += re.findall(
            r"\{\{\s*[a-zA-Z_][\w]*\.([a-zA-Z][a-zA-Z0-9_]*)",
            text,
        )
        skip = {
            "value",
            "length",
            "map",
            "filter",
            "trim",
            "toFixed",
            "join",
            "split",
            "push",
            "includes",
        }
        ulab, s1 = [], set()
        for l in labels:
            if l not in s1 and not l.startswith("el-"):
                s1.add(l)
                ulab.append(l)
        ufields, s2 = [], set()
        for f in fields:
            if f not in s2 and f not in skip:
                s2.add(f)
                ufields.append(f)
        rel = str(p.relative_to(pages_dir)).replace("\\", "/")
        out.append((rel, ulab, ufields))
    return out


def dto_fields(name: str) -> list[str]:
    src = (ROOT / "packages/shared-types/src/index.ts").read_text(encoding="utf-8")
    # rough: interface Name { ... }
    m = re.search(rf"export interface {re.escape(name)}\s*\{{([^}}]+)\}}", src, re.S)
    if not m:
        return []
    body = m.group(1)
    return re.findall(r"^\s*([a-zA-Z][a-zA-Z0-9_]*)\??:", body, re.M)


def main() -> None:
    out_path = ROOT / "docs/FIELD_GAP_INVENTORY.md"
    lines: list[str] = []
    lines.append("# 三端字段差距清单（全页面/表格盘点）")
    lines.append("")
    lines.append("> 自动扫描生成：对比「页面已展示字段」与「shared-types / 常见竞品必填列」。")
    lines.append("> 优先级：P0 严重影响对账/履约 · P1 日常运营 · P2 增强体验。")
    lines.append("")
    lines.append("## 0. 总览")
    lines.append("")

    admin = admin_inventory()
    merch = mp_inventory(ROOT / "clients/merchant-mp/src/pages")
    cons = mp_inventory(ROOT / "clients/consumer-mp/src/pages")
    lines.append(f"- 运营后台 views：{len(admin)} 个")
    lines.append(f"- 商户端 pages：{len(merch)} 个")
    lines.append(f"- 消费端 pages：{len(cons)} 个")
    lines.append("")

    # High-priority gap analysis using DTOs
    critical = [
        (
            "订单",
            "OrderDetailDto",
            ["订单号", "状态", "金额", "设备", "支付渠道", "下单时间", "退款", "优惠券", "会员价", "货道", "支付时间", "分账"],
        ),
        (
            "设备",
            "DeviceInfo",
            ["设备号", "名称", "在线", "商户", "地址", "停售", "温度", "线路", "经纬度", "固件", "库存健康"],
        ),
        (
            "会话",
            "SessionDto",
            ["会话号", "用户", "设备", "状态", "开门时间", "渠道", "预授权", "视频"],
        ),
        (
            "争议",
            "DisputeTicketDto",
            ["工单号", "订单", "状态", "原因", "金额", "视频", "处理结果", "创建时间"],
        ),
        (
            "附近柜机",
            "NearbyDeviceDto",
            ["名称", "距离", "地址", "在线", "营业"],
        ),
    ]

    lines.append("## 1. 高优先差距（跨端共性，先修）")
    lines.append("")
    lines.append("| 域 | P0 建议补齐 | 说明 |")
    lines.append("|----|-------------|------|")
    lines.append(
        "| 订单列表/详情 | 支付渠道、支付时间、优惠/会员价、退款额、货道、分账状态、外部支付单号 | 三端对账与售后刚需 |"
    )
    lines.append(
        "| 设备列表/详情 | 线路 routeCode、经纬度、温度/温控、固件版本、停售原因、缺货 SKU 数、商户名 | 运营调度与商户端柜机页 |"
    )
    lines.append(
        "| 开门会话 | 入口渠道、预授权/免密标记、视频状态、识别耗时、失败原因 | 履约排障 |"
    )
    lines.append(
        "| 争议/售后 | 视频入口、申请金额 vs 退款金额、处理人、时限 SLA | 商户端+运营端+C端 |"
    )
    lines.append(
        "| 补货任务 | 备货单行明细、实盘/补后数量、拍照凭证、线路、截止时间 | 商户补货体验 |"
    )
    lines.append(
        "| 钱包/提现/分账 | 可用/冻结、手续费、到账渠道、失败原因、外部单号 | 财务信任 |"
    )
    lines.append(
        "| 会员/券/积分 | 券门槛、有效期、适用柜、积分过期、等级权益明细 | C 端转化 |"
    )
    lines.append("")

    lines.append("## 2. 运营后台（admin-vue）— 全页面表格列")
    lines.append("")
    thin = []
    for rel, cols in admin:
        n = len(cols)
        lines.append(f"### `{rel}` （列数 {n}）")
        if cols:
            lines.append("")
            lines.append("| # | 列 |")
            lines.append("|---|----|")
            for i, c in enumerate(cols, 1):
                lines.append(f"| {i} | {c} |")
        else:
            lines.append("")
            lines.append("_无 el-table-column（卡片/图表/表单页）_")
            thin.append(rel)
        lines.append("")
        if n and n < 5 and any(
            k in rel.lower()
            for k in ("order", "device", "dispute", "session", "merchant", "finance", "user")
        ):
            thin.append(rel)

    lines.append("### 后台疑似偏薄页（需优先对照 DTO）")
    lines.append("")
    for t in sorted(set(thin)):
        lines.append(f"- `{t}`")
    lines.append("")

    # DTO vs critical UI hints
    lines.append("## 3. shared-types 关键 DTO 字段（后端已有、前端常漏展）")
    lines.append("")
    for title, dto, expect in critical:
        fields = dto_fields(dto)
        lines.append(f"### {title} — `{dto}`")
        lines.append("")
        if fields:
            lines.append(f"DTO 字段（{len(fields)}）：`" + "`, `".join(fields) + "`")
        else:
            lines.append("_shared-types 未找到该 interface（可能仅在 Java DTO）_")
        lines.append("")
        lines.append("竞品/体验期望列：" + "、".join(expect))
        lines.append("")

    lines.append("## 4. 商户端（merchant-mp）— 全页面")
    lines.append("")
    for rel, labs, fields in merch:
        lines.append(f"### `pages/{rel}`")
        lines.append("")
        lines.append("- UI 文案：" + ("；".join(labs[:40]) if labs else "_少_"))
        lines.append("- 绑定字段：" + (", ".join(fields[:40]) if fields else "_少_"))
        # heuristic gaps
        gaps = []
        low = rel.lower()
        if "order" in low:
            gaps.append("支付渠道/退款额/优惠明细/货道")
        if "device" in low:
            gaps.append("温度/线路/停售原因/缺货数/地址完整度")
        if "dispute" in low:
            gaps.append("视频/退款金额拆分/处理时限")
        if "replenish" in low or "request" in low:
            gaps.append("行级数量/批次效期/拍照状态")
        if "wallet" in low or "settlement" in low or "split" in low:
            gaps.append("冻结余额/手续费/外部单号/失败原因")
        if "business" in low:
            gaps.append("毛利/客单/缺货损失/同比")
        if gaps:
            lines.append("- **建议补齐**：" + "；".join(gaps))
        lines.append("")

    lines.append("## 5. 消费端（consumer-mp）— 全页面")
    lines.append("")
    for rel, labs, fields in cons:
        lines.append(f"### `pages/{rel}`")
        lines.append("")
        lines.append("- UI 文案：" + ("；".join(labs[:40]) if labs else "_少_"))
        lines.append("- 绑定字段：" + (", ".join(fields[:40]) if fields else "_少_"))
        gaps = []
        low = rel.lower()
        if "order" in low:
            gaps.append("支付渠道；优惠/会员价；退款进度；货道；开票入口")
        if "member" in low or "points" in low or "coupon" in low:
            gaps.append("有效期；门槛；适用柜范围；过期提醒")
        if "nearby" in low:
            gaps.append("营业状态；库存摘要；导航距离单位")
        if "dispute" in low:
            gaps.append("审核进度时间线；退款到账渠道")
        if "message" in low:
            gaps.append("未读类型拆分；跳转深链完整参数")
        if gaps:
            lines.append("- **建议补齐**：" + "；".join(gaps))
        lines.append("")

    lines.append("## 6. 推荐落地顺序（下一波实现）")
    lines.append("")
    lines.append("1. **P0 订单三端对齐**：列表+详情统一列集（金额拆分、支付、退款、优惠）")
    lines.append("2. **P0 设备三端对齐**：列表补 routeCode/在线/停售/地址；详情补温度与货道库存摘要")
    lines.append("3. **P1 争议三端**：视频+金额+状态时间线")
    lines.append("4. **P1 商户补货/要货**：行明细与凭证")
    lines.append("5. **P1 财务钱包**：冻结/手续费/外部单号")
    lines.append("6. **P2 C 端会员券积分**：门槛与有效期显性化")
    lines.append("7. **P2 后台其余报表页**：按本清单「列数偏少」页逐项加列")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("生成方式：`scripts/gen-field-gap-inventory.py`")
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote {out_path} ({out_path.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
