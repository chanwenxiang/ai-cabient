# -*- coding: utf-8 -*-
"""Full admin-vue scan: every route + every el-table with columns."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VIEWS = ROOT / "clients/admin-vue/src/views"
ROUTER = ROOT / "clients/admin-vue/src/router/index.ts"
OUT = ROOT / "docs/ADMIN_TABLE_INVENTORY.md"


def parse_routes() -> list[dict]:
    text = ROUTER.read_text(encoding="utf-8")
    routes = []
    # Split biz children objects roughly by "path:"
    for m in re.finditer(
        r"\{\s*path:\s*'([^']+)',\s*name:\s*'([^']+)',\s*"
        r"component:\s*\(\)\s*=>\s*import\('([^']+)'\),\s*"
        r"meta:\s*\{([^}]*)\}",
        text,
        re.S,
    ):
        path, name, imp, meta = m.group(1), m.group(2), m.group(3), m.group(4)
        title_m = re.search(r"title:\s*'([^']+)'", meta)
        group_m = re.search(r"group:\s*'([^']+)'", meta)
        comp = imp.replace("@/views/", "")
        if not comp.endswith(".vue"):
            comp += ".vue"
        routes.append(
            {
                "path": "/" + path.lstrip("/"),
                "name": name,
                "component": comp,
                "title": title_m.group(1) if title_m else name,
                "group": group_m.group(1) if group_m else "未分组",
            }
        )
    return routes


def find_el_table_blocks(text: str) -> list[str]:
    """Return each <el-table>...</el-table> block (not el-table-column)."""
    blocks = []
    pattern = re.compile(r"<el-table(?=[\s>])")
    pos = 0
    while True:
        m = pattern.search(text, pos)
        if not m:
            break
        start = m.start()
        i = m.end()
        depth = 1
        while depth > 0 and i < len(text):
            next_table = pattern.search(text, i)
            next_close = text.find("</el-table>", i)
            if next_close < 0:
                break
            if next_table and next_table.start() < next_close:
                depth += 1
                i = next_table.end()
            else:
                depth -= 1
                if depth == 0:
                    blocks.append(text[start : next_close + len("</el-table>")])
                    pos = next_close + len("</el-table>")
                    break
                i = next_close + len("</el-table>")
        else:
            pos = start + 1
    return blocks


def parse_columns(block: str) -> list[dict]:
    cols = []
    for m in re.finditer(r"<el-table-column\b([\s\S]*?)(/?)>", block):
        attrs = m.group(1)
        # truncate runaway
        if len(attrs) > 1200:
            attrs = attrs[:1200]
        typ = re.search(r'\btype="([^"]+)"', attrs)
        lab = re.search(r'\blabel="([^"]*)"', attrs)
        prop = re.search(r'\bprop="([^"]*)"', attrs)
        if typ and typ.group(1) in ("selection", "index", "expand"):
            cols.append(
                {
                    "kind": typ.group(1),
                    "label": lab.group(1) if lab else typ.group(1),
                    "prop": "",
                }
            )
            continue
        if not lab:
            # slot-only columns sometimes only have label in nested template — skip
            continue
        cols.append(
            {
                "kind": "col",
                "label": lab.group(1),
                "prop": prop.group(1) if prop else "",
            }
        )
    # dedupe consecutive identical
    out = []
    for c in cols:
        if out and out[-1]["label"] == c["label"] and out[-1]["prop"] == c["prop"]:
            continue
        out.append(c)
    return out


def scan_view(rel: str) -> dict:
    path = VIEWS / rel
    if not path.exists():
        return {"rel": rel, "exists": False, "tables": [], "desc_labels": []}
    text = path.read_text(encoding="utf-8", errors="ignore")
    tables = []
    for bi, block in enumerate(find_el_table_blocks(text), 1):
        head = block[: block.find(">") + 1] if ">" in block else block[:300]
        data_m = re.search(r':data="([^"]+)"', head)
        testid = re.search(r'data-testid="([^"]+)"', head)
        v_if = re.search(r'v-if="([^"]+)"', head)
        tables.append(
            {
                "index": bi,
                "data": data_m.group(1) if data_m else "",
                "testid": testid.group(1) if testid else "",
                "vif": v_if.group(1) if v_if else "",
                "cols": parse_columns(block),
            }
        )
    desc_items = re.findall(
        r'<el-descriptions-item\b[^>]*\blabel="([^"]+)"', text
    )
    # also multiline descriptions-item
    desc_items += re.findall(
        r"<el-descriptions-item\b[\s\S]*?\blabel=\"([^\"]+)\"", text
    )
    # dedupe preserve order
    seen = set()
    desc_u = []
    for d in desc_items:
        if d not in seen:
            seen.add(d)
            desc_u.append(d)
    return {
        "rel": rel,
        "exists": True,
        "tables": tables,
        "desc_labels": desc_u,
        "size": len(text),
    }


def all_view_files() -> list[str]:
    return sorted(
        str(p.relative_to(VIEWS)).replace("\\", "/") for p in VIEWS.rglob("*.vue")
    )


def main() -> None:
    routes = parse_routes()
    views = all_view_files()
    routed = {r["component"].replace("\\", "/") for r in routes}
    view_scans = {rel: scan_view(rel) for rel in views}

    total_tables = sum(len(v["tables"]) for v in view_scans.values())
    total_cols = sum(
        len([c for c in t["cols"] if c["kind"] == "col"])
        for v in view_scans.values()
        for t in v["tables"]
    )

    lines: list[str] = []
    lines.append("# 运营后台全页面 × 全数据表格盘点")
    lines.append("")
    lines.append(
        "> 扫描：`clients/admin-vue` 全部业务路由 + `views/**/*.vue` 中每一个 `<el-table>` 及其列。"
    )
    lines.append("> 复现：`python scripts/gen-admin-table-inventory.py`")
    lines.append("")
    lines.append("## 0. 总览")
    lines.append("")
    lines.append(f"| 指标 | 数量 |")
    lines.append(f"|------|------|")
    lines.append(f"| 业务路由页 | {len(routes)} |")
    lines.append(f"| views 文件 | {len(views)} |")
    lines.append(
        f"| 含表格的视图 | {sum(1 for v in view_scans.values() if v['tables'])} |"
    )
    lines.append(f"| el-table 实例总数 | {total_tables} |")
    lines.append(f"| 数据列总数（不含 selection/index） | {total_cols} |")
    lines.append("")

    orphan = [v for v in views if v not in routed]
    lines.append("### 视图存在但未挂 router 懒加载")
    lines.append("")
    for v in orphan:
        lines.append(f"- `{v}`（表 {len(view_scans[v]['tables'])}）")
    if not orphan:
        lines.append("- （无）")
    lines.append("")

    # Quick index table
    lines.append("## 1. 路由总表（一页一行）")
    lines.append("")
    lines.append("| 分组 | 路径 | 标题 | 组件 | 表数 | 列合计 |")
    lines.append("|------|------|------|------|------|--------|")
    for r in routes:
        scan = view_scans.get(r["component"]) or scan_view(r["component"])
        nt = len(scan.get("tables") or [])
        nc = sum(
            len([c for c in t["cols"] if c["kind"] == "col"])
            for t in scan.get("tables") or []
        )
        lines.append(
            f"| {r['group']} | `{r['path']}` | {r['title']} | `{r['component']}` | {nt} | {nc} |"
        )
    lines.append("")

    # Detail by group
    lines.append("## 2. 按路由展开：每一张表的每一列")
    lines.append("")
    by_group: dict[str, list] = {}
    for r in routes:
        by_group.setdefault(r["group"], []).append(r)

    for group in by_group:
        lines.append(f"### {group}")
        lines.append("")
        for r in by_group[group]:
            scan = view_scans.get(r["component"]) or scan_view(r["component"])
            lines.append(f"#### `{r['path']}` · {r['title']}")
            lines.append("")
            lines.append(f"组件：`views/{r['component']}`")
            lines.append("")
            if not scan.get("exists"):
                lines.append("**文件缺失**")
                lines.append("")
                continue
            if not scan["tables"]:
                if scan["desc_labels"]:
                    lines.append(
                        "无 el-table。详情描述项："
                        + "、".join(f"`{x}`" for x in scan["desc_labels"])
                    )
                else:
                    lines.append("无 el-table（图表 / 表单 / 卡片页）。")
                lines.append("")
                continue
            for t in scan["tables"]:
                meta = []
                if t["data"]:
                    meta.append(f"`:data=\"{t['data']}\"`")
                if t["testid"]:
                    meta.append(f"testid=`{t['testid']}`")
                if t["vif"]:
                    meta.append(f"v-if=`{t['vif']}`")
                lines.append(
                    f"**表 {t['index']}**"
                    + (f" — {' · '.join(meta)}" if meta else "")
                )
                lines.append("")
                real = [c for c in t["cols"] if c["kind"] == "col"]
                special = [c for c in t["cols"] if c["kind"] != "col"]
                if special:
                    lines.append(
                        "特殊列："
                        + "、".join(f"{c['kind']}" for c in special)
                    )
                    lines.append("")
                if not real:
                    lines.append("_无 label 数据列（仅 selection 或动态列）_")
                    lines.append("")
                    continue
                lines.append("| # | prop | label |")
                lines.append("|---|------|-------|")
                for i, c in enumerate(real, 1):
                    lines.append(
                        f"| {i} | `{c['prop'] or '—'}` | {c['label']} |"
                    )
                lines.append("")
            if scan["desc_labels"]:
                lines.append(
                    "同页 el-descriptions："
                    + "、".join(f"`{x}`" for x in scan["desc_labels"])
                )
                lines.append("")

    # Multi-table
    lines.append("## 3. 单页多表（≥2）")
    lines.append("")
    for rel, scan in view_scans.items():
        if len(scan["tables"]) >= 2:
            bits = []
            for t in scan["tables"]:
                n = len([c for c in t["cols"] if c["kind"] == "col"])
                bits.append(f"#{t['index']} `{t['data'] or '?'}`({n}列)")
            lines.append(f"- `{rel}`：{'；'.join(bits)}")
    lines.append("")

    # Thin
    lines.append("## 4. 列偏少（数据列 ≤ 4）")
    lines.append("")
    lines.append("| 视图 | 表 | data | 列 |")
    lines.append("|------|----|------|----|")
    for rel, scan in view_scans.items():
        for t in scan["tables"]:
            real = [c for c in t["cols"] if c["kind"] == "col"]
            if 0 < len(real) <= 4:
                lines.append(
                    f"| `{rel}` | #{t['index']} | `{t['data'] or '—'}` | "
                    + "、".join(c["label"] for c in real)
                    + " |"
                )
    lines.append("")

    # Zero table routed pages
    lines.append("## 5. 已挂路由但无表格的页面")
    lines.append("")
    for r in routes:
        scan = view_scans.get(r["component"]) or {}
        if scan.get("exists") and not scan.get("tables"):
            lines.append(f"- `{r['path']}` {r['title']} → `{r['component']}`")
    lines.append("")

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(
        f"Wrote {OUT}\n"
        f"routes={len(routes)} views={len(views)} tables={total_tables} cols={total_cols}"
    )


if __name__ == "__main__":
    main()
