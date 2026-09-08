# -*- coding: utf-8 -*-
"""Generate docs/BUSINESS_FULL_TEST_MATRIX.md from source of truth."""
from __future__ import annotations

import json
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "BUSINESS_FULL_TEST_MATRIX.md"
CACHE = Path(__file__).resolve().parent / "_btn_scan_cache.json"
BTN = json.loads(CACHE.read_text(encoding="utf-8"))


def parse_menu() -> list[dict]:
    text = (ROOT / "clients/admin-vue/src/config/menu.ts").read_text(encoding="utf-8")
    # only BASE_NAV block items: path, title, group, optional perm
    items = []
    for m in re.finditer(
        r"\{\s*path:\s*'([^']+)'\s*,\s*title:\s*'([^']+)'\s*,\s*group:\s*'([^']+)'"
        r"(?:\s*,\s*perm:\s*'([^']+)')?",
        text,
    ):
        items.append(
            {
                "path": m.group(1),
                "title": m.group(2),
                "group": m.group(3),
                "perm": m.group(4) or "（无独立 perm / 登录即可）",
            }
        )
    # dedupe by path keep first
    seen = set()
    out = []
    for it in items:
        if it["path"] in seen:
            continue
        seen.add(it["path"])
        out.append(it)
    return out


def parse_router_titles() -> dict[str, str]:
    text = (ROOT / "clients/admin-vue/src/router/index.ts").read_text(encoding="utf-8")
    mapping = {}
    # path then later meta title within same object — split by objects heuristically
    for block in re.finditer(
        r"\{\s*path:\s*'([^']+)'\s*,\s*name:\s*'[^']+'\s*,\s*component:[\s\S]*?meta:\s*\{([^}]*)\}",
        text,
    ):
        path = block.group(1)
        meta = block.group(2)
        tm = re.search(r"title:\s*'([^']+)'", meta)
        if tm:
            mapping["/" + path if not path.startswith("/") else path] = tm.group(1)
    return mapping


def view_for_path(path: str) -> str | None:
    """Best-effort map menu path -> views file from router."""
    text = (ROOT / "clients/admin-vue/src/router/index.ts").read_text(encoding="utf-8")
    slug = path.lstrip("/")
    m = re.search(
        rf"path:\s*'{re.escape(slug)}'[\s\S]*?import\('@/views/([^']+)'\)",
        text,
    )
    if m:
        return m.group(1)
    return None


def buttons_for_view(view_rel: str | None) -> list[str]:
    if not view_rel:
        return []
    # normalize
    key = view_rel if view_rel.endswith(".vue") else view_rel
    admin = BTN.get("admin", {})
    if key in admin:
        return flatten_labels(admin[key])
    # try without leading
    for k, v in admin.items():
        if k.endswith(key) or key.endswith(k):
            return flatten_labels(v)
    return []


def flatten_labels(raw: list[str]) -> list[str]:
    out = []
    seen = set()
    for item in raw:
        # scanner sometimes glued multiple buttons; split on 2+ spaces or known patterns
        parts = re.split(r"\s{2,}|(?<=[\u4e00-\u9fff]) (?=[\u4e00-\u9fff])", item)
        if len(parts) == 1 and " " in item and len(item) > 8:
            # try split all spaces for short Chinese tokens
            parts = item.split()
        for p in parts:
            p = p.strip()
            p = re.sub(r"\{\{.*?\}\}", "", p).strip()
            p = re.sub(r"[()%]+$", "", p).strip()
            if not p or not re.search(r"[\u4e00-\u9fff]", p):
                continue
            if len(p) > 24:
                continue
            if p in seen:
                continue
            seen.add(p)
            out.append(p)
    return out[:25]


def parse_pages_json(path: Path) -> tuple[list[dict], list[dict]]:
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    pages = []
    for p in data.get("pages", []):
        pages.append(
            {
                "path": p["path"],
                "title": p.get("style", {}).get("navigationBarTitleText", ""),
            }
        )
    tabs = data.get("tabBar", {}).get("list", [])
    return pages, tabs


def mp_buttons(kind: str, page_path: str) -> list[str]:
    # pages/home/home -> home/home.vue or home/home.uvue
    rel = page_path.replace("pages/", "", 1)
    candidates = [
        f"{rel}.vue",
        f"{rel}.uvue",
        rel.split("/")[-1] + "/" + rel.split("/")[-1] + ".vue",
    ]
    bag = BTN.get(kind, {})
    for c in candidates:
        if c in bag:
            return flatten_labels(bag[c])
    # fuzzy
    leaf = rel.split("/")[-1]
    for k, v in bag.items():
        if leaf in k:
            return flatten_labels(v)
    return []


def md_escape(s: str) -> str:
    return s.replace("|", "\\|")


def main() -> None:
    menu = parse_menu()
    by_group: dict[str, list] = defaultdict(list)
    for it in menu:
        by_group[it["group"]].append(it)

    group_order = [
        "概览",
        "交易履约",
        "设备商品",
        "履约仓储",
        "财务商户",
        "增长风控",
        "系统",
    ]

    m_pages, m_tabs = parse_pages_json(ROOT / "clients/merchant-mp/src/pages.json")
    c_pages, c_tabs = parse_pages_json(ROOT / "clients/consumer-mp/src/pages.json")

    lines: list[str] = []
    lines.append("# AI Cabinet · 全业务节点与按钮功能测试矩阵")
    lines.append("")
    lines.append("> **性质**：测试清单 / 验收矩阵（非已执行 UAT 报告）。")
    lines.append("> **真源**：下列路径均从仓库源码抽取，禁止凭记忆增删页面。")
    lines.append(">")
    lines.append("> | 端 | 真源文件 |")
    lines.append("> |----|----------|")
    lines.append(
        "> | 运营后台菜单 | `clients/admin-vue/src/config/menu.ts`（`NAV_ITEMS` / `BASE_NAV`） |"
    )
    lines.append(
        "> | 运营后台路由 | `clients/admin-vue/src/router/index.ts`（`bizChildren`） |"
    )
    lines.append(
        "> | 运营按钮文案 | `clients/admin-vue/src/views/**/*.vue` 中 `<el-button>` |"
    )
    lines.append(
        "> | 商户端页面 | `clients/merchant-mp/src/pages.json` |"
    )
    lines.append(
        "> | 消费者端页面 | `clients/consumer-mp/src/pages.json` |"
    )
    lines.append(
        "> | 小程序按钮文案 | 各端 `src/pages/**` 模板内可点击中文（启发式扫描，以页面为准） |"
    )
    lines.append("")
    lines.append("版本：1.0 · 生成日期：2026-09-08")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## 0. 怎么用这份文档")
    lines.append("")
    lines.append("1. **逐页打开**：URL / 路由与「菜单 title」一致才算进入正确节点。")
    lines.append("2. **逐按钮点**：表中「源码按钮」列来自页面模板；若页面还有动态按钮，以实机为准并回写本文。")
    lines.append("3. **验收维度**（每按钮）：可点 → loading/防重 → 成功/失败中文提示 → 列表或详情数据变化。")
    lines.append(
        "4. **执行工具**：有 UI 改动或正式验收时，优先 Playwright MCP/CLI；本文本身不宣称任何用例已通过。"
    )
    lines.append("5. **与旧文档关系**：抽样场景仍可参考 [`BROWSER_MIN_UAT.md`](BROWSER_MIN_UAT.md)、[`BROWSER_FULL_UAT_PLAN.md`](BROWSER_FULL_UAT_PLAN.md)；**全量节点覆盖以本文为准**。")
    lines.append("")
    lines.append("### 0.1 环境入口")
    lines.append("")
    lines.append("| 端 | URL | 演示账号 |")
    lines.append("|----|-----|----------|")
    lines.append("| 运营后台 | `http://localhost/admin/index.html` | `13900000001` / `123456` |")
    lines.append("| 消费者 H5 | `http://localhost:5173`（以本地 vite 为准） | 见 `DEMO_ACCOUNTS.md` |")
    lines.append("| 商户 H5 | `http://localhost:5175` | 见 `DEMO_ACCOUNTS.md` |")
    lines.append("")
    lines.append("### 0.2 用例状态列（执行时填写）")
    lines.append("")
    lines.append("`PASS` / `FAIL` / `BLOCK` / `SKIP` / `N/A`")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## 1. 运营后台（admin-vue）")
    lines.append("")
    lines.append(
        f"菜单项合计 **{len(menu)}**（含个人中心；识别演示仅在 `ENABLE_TEST_TOOLS` 开启时出现）。"
    )
    lines.append("")

    for g in group_order:
        items = by_group.get(g, [])
        if not items:
            continue
        lines.append(f"### 1.{group_order.index(g) + 1} {g}")
        lines.append("")
        lines.append("| # | 菜单 | 路径 | 权限码 | 源码视图 | 源码按钮（抽样） | 建议验收要点 | 状态 |")
        lines.append("|---|------|------|--------|----------|------------------|--------------|------|")
        for i, it in enumerate(items, 1):
            view = view_for_path(it["path"])
            btns = buttons_for_view(view) if view else []
            btn_s = "、".join(btns[:12]) if btns else "（模板未扫到 el-button 或按钮为动态/插槽）"
            tips = acceptance_tip(it["path"], it["title"])
            lines.append(
                f"| {i} | {md_escape(it['title'])} | `{it['path']}` | `{it['perm']}` | "
                f"`{view or '—'}` | {md_escape(btn_s)} | {md_escape(tips)} |  |"
            )
        lines.append("")

    # Extra routes not in menu
    lines.append("### 1.8 路由存在但非侧栏菜单（仍需测）")
    lines.append("")
    lines.append("| 路径 | 说明 | 建议验收 | 状态 |")
    lines.append("|------|------|----------|------|")
    lines.append("| `/login` | 登录页 | 正确账号进首页；错误提示中文；重置密码入口 |  |")
    lines.append("| `/print` | 打印单据 | 有单据参数时可打印/预览 |  |")
    lines.append("| `/devices/:id` | 设备详情（动态） | 从设备列表进入；货道/补货/复制链接等 |  |")
    lines.append("| `/forbidden` | 无权访问 | 无权限菜单跳转落此页；可回工作台 |  |")
    lines.append("| `/recognition-demo` | 识别演示（测试开关） | 仅 `ENABLE_TEST_TOOLS` |  |")
    lines.append("")

    lines.append("---")
    lines.append("")
    lines.append("## 2. 商户端（merchant-mp）")
    lines.append("")
    lines.append("真源：`clients/merchant-mp/src/pages.json`。")
    lines.append("")
    lines.append("### 2.1 TabBar")
    lines.append("")
    lines.append("| Tab 文案 | pagePath | 状态 |")
    lines.append("|----------|----------|------|")
    for t in m_tabs:
        lines.append(f"| {t.get('text')} | `{t.get('pagePath')}` |  |")
    lines.append("")
    lines.append("### 2.2 全页面矩阵")
    lines.append("")
    lines.append("| # | 标题 | 页面 path | 源码按钮（抽样） | 建议验收要点 | 状态 |")
    lines.append("|---|------|-----------|------------------|--------------|------|")
    for i, p in enumerate(m_pages, 1):
        btns = mp_buttons("merchant", p["path"])
        btn_s = "、".join(btns[:12]) if btns else "（启发式未扫到；打开页面核对）"
        tips = merchant_tip(p["path"], p["title"])
        lines.append(
            f"| {i} | {md_escape(p['title'])} | `{p['path']}` | {md_escape(btn_s)} | {md_escape(tips)} |  |"
        )
    lines.append("")

    lines.append("---")
    lines.append("")
    lines.append("## 3. 消费者端（consumer-mp）")
    lines.append("")
    lines.append("真源：`clients/consumer-mp/src/pages.json`。")
    lines.append("")
    lines.append("### 3.1 TabBar")
    lines.append("")
    lines.append("| Tab 文案 | pagePath | 状态 |")
    lines.append("|----------|----------|------|")
    for t in c_tabs:
        lines.append(f"| {t.get('text')} | `{t.get('pagePath')}` |  |")
    lines.append("")
    lines.append("### 3.2 全页面矩阵")
    lines.append("")
    lines.append("| # | 标题 | 页面 path | 源码按钮（抽样） | 建议验收要点 | 状态 |")
    lines.append("|---|------|-----------|------------------|--------------|------|")
    for i, p in enumerate(c_pages, 1):
        btns = mp_buttons("consumer", p["path"])
        btn_s = "、".join(btns[:12]) if btns else "（启发式未扫到；打开页面核对）"
        tips = consumer_tip(p["path"], p["title"])
        lines.append(
            f"| {i} | {md_escape(p['title'])} | `{p['path']}` | {md_escape(btn_s)} | {md_escape(tips)} |  |"
        )
    lines.append("")

    lines.append("---")
    lines.append("")
    lines.append("## 4. 跨端主链路（业务节点串联）")
    lines.append("")
    lines.append("下列链路覆盖「开门柜」核心资金与履约，执行时记录 `sessionId` / `orderId` / `ticketId`。")
    lines.append("")
    lines.append("| # | 链路 | 关键节点（端→页面） | 关键按钮/动作 | 通过标准 | 状态 |")
    lines.append("|---|------|---------------------|--------------|----------|------|")
    lines.append(
        "| 1 | 扫码开门→购物→结算 | 消费者 `index` → 柜机 → 关门结算 → `result`/`orders` | 扫码/输入柜号、开门、查看账单 | 订单生成；金额可读；视频可进 |  |"
    )
    lines.append(
        "| 2 | 争议发起→运营处理 | 消费者 `dispute/detail` 或订单详情 → 运营 `/disputes` `/exceptions` | 提交争议；运营调整/免单/结案 | 三端状态一致；退款幂等 |  |"
    )
    lines.append(
        "| 3 | 补货履约 | 运营补货调度 → 商户 `replenishment`/`request` → 设备货道 | 规划路线、接单、补货开门、实盘 | 库存账面变化；任务完结 |  |"
    )
    lines.append(
        "| 4 | 分账与提现 | 运营商户/分账 → 商户 `wallet`/`splits` → 运营提现审核 | 申请提现、通过并打款、驳回 | 钱包余额与流水一致 |  |"
    )
    lines.append(
        "| 5 | 营销发券→核销 | 运营优惠券/活动 → 消费者 `coupons`/`marketing` → 下单 | 发券、领券、下单抵扣 | 核销记录与 ROI 可查 |  |"
    )
    lines.append(
        "| 6 | 设备运维 | 消费者 `report` → 运营维修工单 → 商户待办 | 报修、指派、完成 | 工单状态闭环 |  |"
    )
    lines.append(
        "| 7 | 消息与公告 | 运营公告/站内信 → 两端 messages/announcements | 发布、发送、已读 | 目标端可见对应文案 |  |"
    )
    lines.append("")

    lines.append("---")
    lines.append("")
    lines.append("## 5. 维护约定")
    lines.append("")
    lines.append("1. **增删页面**：先改 `menu.ts` / `router` / `pages.json`，再改本矩阵（或重跑下方脚本）。")
    lines.append(
        "2. **重新生成**：`python scripts/gen-btn-scan.py` → `python scripts/gen-business-test-matrix.py`（缓存 `scripts/_btn_scan_cache.json` 可删、勿当业务真源）。"
    )
    lines.append("3. **权限**：运营侧以 `perm` 为准；用无权限账号抽测「无权访问」。")
    lines.append("4. **Pass 专项**：金钱/争议/MQTT/库存/钱包见 `docs/pass-notes/PASS_3*.md`，本矩阵负责「全页面+按钮」覆盖，专项负责深分支。")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## 6. 统计（生成时）")
    lines.append("")
    lines.append(f"| 维度 | 数量 |")
    lines.append(f"|------|------|")
    lines.append(f"| 运营菜单项 | {len(menu)} |")
    lines.append(f"| 商户 pages.json 页 | {len(m_pages)} |")
    lines.append(f"| 消费者 pages.json 页 | {len(c_pages)} |")
    lines.append(f"| 跨端主链路 | 7 |")
    lines.append("")

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("wrote", OUT, "lines", len(lines))


def acceptance_tip(path: str, title: str) -> str:
    tips = {
        "/dashboard": "卡片数字加载；快捷入口可跳转",
        "/disputes": "筛选；详情调整商品；结案后状态变化",
        "/exceptions": "异常单处理；免单/落账二次确认",
        "/sessions": "会话列表；关联订单/视频",
        "/orders": "订单筛选；详情金额与状态",
        "/devices": "新建/创建；进详情",
        "/skus": "新建商品；导入",
        "/replenishment": "规划路线；任务状态",
        "/merchant-withdraw": "通过并打款/驳回；防重复",
        "/reconciliation": "执行对账有结果",
        "/coupons": "新建/发券/停用",
        "/risk": "加入黑名单生效",
        "/operators": "角色分配后菜单变化",
        "/login": "登录成功进首页",
    }
    if path in tips:
        return tips[path]
    if "finance" in path or path in {"/recharges", "/invoices", "/users"}:
        return "列表加载；关键写操作二次确认"
    if path.startswith("/ad") or path in {"/promotions", "/points-redeem", "/member-levels"}:
        return "新建/保存/启停后列表刷新"
    return f"打开「{title}」；列表或表单可用；关键写操作有中文反馈"


def merchant_tip(path: str, title: str) -> str:
    if "wallet" in path:
        return "余额/流水展示；提现申请与结果"
    if "replenish" in path or "request" in path:
        return "任务列表；接单/完成；与运营侧一致"
    if "dispute" in path:
        return "争议列表；处理动作权限正确"
    if "login" in path:
        return "登录/退出；错误提示中文"
    if "device" in path:
        return "柜机列表/详情；定价入口"
    return f"打开「{title}」；下拉刷新（若启用）；空态中文"


def consumer_tip(path: str, title: str) -> str:
    if "index" in path:
        return "扫码/输柜号开门主路径；失败提示"
    if "order" in path:
        return "订单列表/详情；视频/争议入口"
    if "recharge" in path:
        return "充值档位；支付结果回跳"
    if "coupon" in path or "marketing" in path:
        return "券列表；活动可点"
    if "dispute" in path:
        return "提交材料；状态回显"
    if "login" in path or "verify" in path:
        return "登录/开通支付流程可完成或明确阻塞原因"
    return f"打开「{title}」；返回与 Tab 正常"


if __name__ == "__main__":
    main()
