# -*- coding: utf-8 -*-
"""Full admin-vue scan: every route + every table (el-table / CrudTable) with columns.

表格宿主有两种：直接写的 <el-table>，以及业务页里包一层列表状态的 <CrudTable :table="x">
（列仍是父组件插槽里的 <el-table-column>）。表格实体大量下放在 components/ 下的 tab /
dialog / 卡片里，所以路由页的表格按 import 图下钻收集，不只看 views 自身。
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "clients/admin-vue/src"
VIEWS = SRC / "views"
COMPONENTS = SRC / "components"
ROUTER = SRC / "router/index.ts"
OUT = ROOT / "docs/ADMIN_TABLE_INVENTORY.md"

# 列表基础设施：列由父页插槽提供，本身不是某一页的表格
INFRA = {"components/CrudTable.vue"}
TABLE_TAG = re.compile(r"<(el-table|CrudTable)(?=[\s>])")
IMPORT_VUE = re.compile(r"(?:\bfrom\s*|import\()\s*'([^']+\.vue)'")


# ---------- routes ----------

def parse_routes() -> tuple[list[dict], list[dict]]:
    text = ROUTER.read_text(encoding="utf-8")
    # 路由对象里常有独占一行的 // 注释（例如「V327（E7 缺口 #9）：…」），
    # 不先摘掉会把紧跟其后的 path/name/component/meta 整条漏掉。
    text = re.sub(r"(?m)^[ \t]*//.*$", "", text)
    routes = []
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
        comp = imp.replace("@/views/", "").replace("@/", "")
        if not comp.startswith("views/"):
            comp = "views/" + comp
        routes.append(
            {
                "path": "/" + path.lstrip("/"),
                "name": name,
                "component": comp,
                "title": title_m.group(1) if title_m else name,
                "group": group_m.group(1) if group_m else "未分组",
            }
        )
    redirects = []
    for m in re.finditer(
        r"\{\s*path:\s*'([^']+)',\s*name:\s*'([^']+)',\s*redirect:", text
    ):
        redirects.append({"path": "/" + m.group(1).lstrip("/"), "name": m.group(2)})
    return routes, redirects


# ---------- table blocks ----------

def find_table_spans(text: str) -> list[tuple[str, int, int]]:
    """[(tag, start, end)] for every <el-table> / <CrudTable>, nested ones included.

    展开行里的子表是真的另一张表（列集不同），不能因为嵌在父表的 <template> 里就丢掉；
    父表解析列时要先把子表区间挖掉，否则子表列会被算进父表。
    """
    spans: list[tuple[str, int, int]] = []
    for m in TABLE_TAG.finditer(text):
        tag = m.group(1)
        closer = f"</{tag}>"
        depth = 1
        i = m.end()
        while i < len(text):
            nxt_close = text.find(closer, i)
            if nxt_close < 0:
                break
            nxt_open = TABLE_TAG.search(text, i, nxt_close)
            if nxt_open and nxt_open.group(1) == tag:
                depth += 1
                i = nxt_open.end()
                continue
            depth -= 1
            i = nxt_close + len(closer)
            if depth == 0:
                spans.append((tag, m.start(), i))
                break
    return spans


def own_block(text: str, span: tuple[str, int, int], spans: list) -> str:
    """Block text with nested table spans blanked out."""
    _, start, end = span
    cuts = [(s, e) for _, s, e in spans if start < s and e <= end]
    pieces = []
    pos = start
    for s, e in sorted(cuts):
        pieces.append(text[pos:s])
        pos = e
    pieces.append(text[pos:end])
    return "".join(pieces)


def parse_columns(block: str) -> list[dict]:
    cols = []
    for m in re.finditer(r"<el-table-column\b([\s\S]*?)(/?)>", block):
        attrs = m.group(1)[:1200]
        typ = re.search(r'\btype="([^"]+)"', attrs)
        lab = re.search(r'\blabel="([^"]*)"', attrs)
        prop = re.search(r'\bprop="([^"]*)"', attrs)
        if typ and typ.group(1) in ("selection", "index", "expand"):
            cols.append(
                {"kind": typ.group(1), "label": lab.group(1) if lab else typ.group(1), "prop": ""}
            )
            continue
        if not lab:
            continue
        cols.append(
            {
                "kind": "col",
                "label": lab.group(1),
                "prop": prop.group(1) if prop else "",
            }
        )
    out = []
    for c in cols:
        if out and out[-1]["label"] == c["label"] and out[-1]["prop"] == c["prop"]:
            continue
        out.append(c)
    return out


# ---------- files & imports ----------

def all_files(sub: str) -> list[str]:
    base = SRC / sub
    return sorted(
        f"{sub}/{p.relative_to(base).as_posix()}" for p in base.rglob("*.vue")
    )


def scan_file(src: str) -> dict:
    path = SRC / src
    if not path.exists():
        return {"src": src, "exists": False, "tables": [], "desc_labels": []}
    text = path.read_text(encoding="utf-8", errors="ignore")
    tables = []
    spans = find_table_spans(text)
    for bi, span in enumerate(spans, 1):
        tag, _, _ = span
        block = own_block(text, span, spans)
        head = block[: block.find(">") + 1] if ">" in block else block[:300]
        data_m = re.search(r':data="([^"]+)"', head)
        table_m = re.search(r':table="([^"]+)"', head)
        testid = re.search(r'data-testid="([^"]+)"', head)
        v_if = re.search(r'v-if="([^"]+)"', head)
        _, span_start, span_end = span
        nested = any(
            s < span_start and span_end <= e for _, s, e in spans
        )
        tables.append(
            {
                "index": bi,
                "host": "CrudTable" if tag == "CrudTable" else "el-table",
                "data": (table_m or data_m).group(1) if (table_m or data_m) else "",
                "has_data_attr": bool(data_m),
                "nested": nested,
                "testid": testid.group(1) if testid else "",
                "vif": v_if.group(1) if v_if else "",
                "cols": parse_columns(block),
            }
        )
    desc_items = re.findall(r'<el-descriptions-item\b[\s\S]*?\blabel="([^"]+)"', text)
    seen = set()
    desc_u = []
    for d in desc_items:
        if d not in seen:
            seen.add(d)
            desc_u.append(d)
    return {
        "src": src,
        "exists": True,
        "tables": tables,
        "desc_labels": desc_u,
        "size": len(text),
    }


def imports_of(src: str) -> list[str]:
    path = SRC / src
    if not path.exists():
        return []
    text = path.read_text(encoding="utf-8", errors="ignore")
    out = []
    for m in IMPORT_VUE.finditer(text):
        spec = m.group(1)
        if spec.startswith("@/"):
            target = spec[2:]
        elif spec.startswith("."):
            target = (
                (path.parent / spec).resolve().relative_to(SRC.resolve()).as_posix()
            )
        else:
            continue
        out.append(target)
    return out


_TEXT: dict[str, str] = {}


def file_text(src: str) -> str:
    if src not in _TEXT:
        path = SRC / src
        _TEXT[src] = path.read_text(encoding="utf-8", errors="ignore") if path.exists() else ""
    return _TEXT[src]


def tag_to_src(src: str) -> dict[str, str]:
    """组件标签名 → 文件，静态 import 与 defineAsyncComponent 都收。"""
    text = file_text(src)
    out: dict[str, str] = {}
    for m in re.finditer(r"import\s+(\w+)\s+from\s+'([^']+\.vue)'", text):
        out[m.group(1)] = resolve_spec(src, m.group(2))
    for m in re.finditer(
        r"const\s+(\w+)\s*=\s*defineAsyncComponent\(\s*\(\)\s*=>\s*import\('([^']+\.vue)'\)",
        text,
    ):
        out[m.group(1)] = resolve_spec(src, m.group(2))
    return {k: v for k, v in out.items() if v}


def resolve_spec(src: str, spec: str) -> str:
    if spec.startswith("@/"):
        return spec[2:]
    if spec.startswith("."):
        base = (SRC / src).parent
        return (base / spec).resolve().relative_to(SRC.resolve()).as_posix()
    return ""


def pane_gates(src: str) -> dict[str, str]:
    """组件 → 包住它的 <el-tab-pane name="x">。外壳页靠这个按 :only 子集收窄。"""
    text = file_text(src)
    tags = tag_to_src(src)
    gates: dict[str, str] = {}
    for m in re.finditer(r"<el-tab-pane\b[\s\S]*?</el-tab-pane>", text):
        block = m.group(0)
        name_m = re.search(r'\bname="([^"]+)"', block[: block.find(">") + 1])
        if not name_m:
            continue
        for tag in re.findall(r"<([A-Z]\w*)", block):
            target = tags.get(tag)
            if target:
                gates.setdefault(target, name_m.group(1))
    return gates


def child_only(src: str) -> dict[str, list[str]]:
    """页面上的 `<Shell :only="['a','b']" />` → 该子组件只渲染这些 tab。"""
    text = file_text(src)
    tags = tag_to_src(src)
    out: dict[str, list[str]] = {}
    for m in re.finditer(r"<(\w+)[^>]*:only=\"\[([^\]]*)\]\"", text):
        target = tags.get(m.group(1))
        if not target:
            continue
        names = re.findall(r"'([^']+)'", m.group(2))
        out[target] = names
    return out


def reachable(start: str, scans: dict) -> list[str]:
    order: list[str] = []
    queue: list[tuple[str, list[str] | None]] = [(start, None)]
    seen = set()
    while queue:
        cur, only = queue.pop(0)
        if cur in seen or cur in INFRA or cur not in scans:
            continue
        seen.add(cur)
        order.append(cur)
        gates = pane_gates(cur)
        scoped = child_only(cur)
        for imp in imports_of(cur):
            gate = gates.get(imp)
            if gate is not None and only is not None and gate not in only:
                continue
            queue.append((imp, scoped.get(imp)))
    return order


def page_tables(page_src: str, scans: dict) -> list[dict]:
    tables = []
    for src in reachable(page_src, scans):
        for t in scans[src]["tables"]:
            t = dict(t)
            t["src"] = src
            tables.append(t)
    return tables


def count_cols(tables: list[dict]) -> int:
    return sum(len([c for c in t["cols"] if c["kind"] == "col"]) for t in tables)


# ---------- render ----------

def main() -> None:
    routes, redirects = parse_routes()
    view_files = all_files("views")
    comp_files = all_files("components")
    scans = {rel: scan_file(rel) for rel in view_files + comp_files}
    table_scans = {
        k: v for k, v in scans.items() if v["tables"] and k not in INFRA
    }

    routed_srcs = [r["component"] for r in routes]
    reach_all: set[str] = set()
    for src in routed_srcs:
        reach_all.update(reachable(src, scans))

    per_route = {r["path"]: page_tables(r["component"], scans) for r in routes}

    uniq_tables = sum(len(v["tables"]) for v in table_scans.values())
    uniq_cols = count_cols([t for v in table_scans.values() for t in v["tables"]])
    pages_with_tables = sum(1 for ts in per_route.values() if ts)

    lines: list[str] = []
    add = lines.append
    add("# 运营后台全页面 × 全数据表格盘点")
    add("")
    add(
        "> 扫描：`clients/admin-vue` 全部业务路由 + `views/**` 与 `components/**` 里的每一个表格宿主"
        "（`<el-table>` 与包了列表状态的 `<CrudTable :table=\"x\">`），按 import 图把组件里的表格归到宿主页面。"
    )
    add("> 复现：`python scripts/gen-admin-table-inventory.py`")
    add("")
    add("## 0. 总览")
    add("")
    add("| 指标 | 数量 |")
    add("|------|------|")
    add(f"| 业务路由页 | {len(routes)} |")
    add(f"| 重定向入口（无组件） | {len(redirects)} |")
    add(f"| views 文件 | {len(view_files)} |")
    add(f"| components 文件 | {len(comp_files)} |")
    add(f"| 含表格的页面（含组件下钻） | {pages_with_tables} |")
    add(f"| 表格实例总数（跨文件去重） | {uniq_tables} |")
    add(f"| 数据列总数（不含 selection/index） | {uniq_cols} |")
    add("")
    add("> 计数口径：表格实例与列数按**文件去重**统计；同一组件被多页复用时，在「按路由展开」里会重复出现。")
    add("")

    orphan_views = [v for v in view_files if v not in routed_srcs and v not in reach_all]
    add("### 视图存在但未挂 router 懒加载")
    add("")
    if orphan_views:
        for v in orphan_views:
            add(f"- `{v}`（表 {len(scans[v]['tables'])}）")
    else:
        add("- （无）")
    add("")

    add("### 表格有代码但路由上到不了")
    add("")
    add("> 有表格的文件，从任何路由页顺着 import 都进不去。要么没被引用，要么被外壳页的 `:only` tab 子集挡掉。")
    add("")
    unreached = [c for c in sorted(table_scans) if c not in reach_all]
    if unreached:
        for c in unreached:
            why = "未被任何页面引用"
            for shell in view_files + comp_files:
                gate = pane_gates(shell).get(c)
                if gate:
                    why = f"在 `{shell}` 的 `{gate}` 分页里，但没有哪个页面把它放进 `:only` 子集"
                    break
            add(f"- `{c}`（表 {len(scans[c]['tables'])}）—— {why}")
    else:
        add("- （无）")
    add("")

    add("## 1. 路由总表（一页一行）")
    add("")
    add("| 分组 | 路径 | 标题 | 组件 | 表数 | 列合计 |")
    add("|------|------|------|------|------|--------|")
    for r in routes:
        ts = per_route[r["path"]]
        add(
            f"| {r['group']} | `{r['path']}` | {r['title']} | `{r['component']}` | "
            f"{len(ts)} | {count_cols(ts)} |"
        )
    add("")
    if redirects:
        add("重定向入口（兼容旧地址，无组件、无表格）：" + "、".join(f"`{x['path']}`（{x['name']}）" for x in redirects))
        add("")

    add("## 2. 按路由展开：每一张表的每一列")
    add("")
    by_group: dict[str, list] = {}
    for r in routes:
        by_group.setdefault(r["group"], []).append(r)

    for group in by_group:
        add(f"### {group}")
        add("")
        for r in by_group[group]:
            scan = scans.get(r["component"])
            add(f"#### `{r['path']}` · {r['title']}")
            add("")
            add(f"组件：`{r['component']}`")
            add("")
            if not scan or not scan["exists"]:
                add("**文件缺失**")
                add("")
                continue
            ts = per_route[r["path"]]
            if not ts:
                if scan["desc_labels"]:
                    add(
                        "无表格。详情描述项："
                        + "、".join(f"`{x}`" for x in scan["desc_labels"])
                    )
                else:
                    add("无表格（图表 / 表单 / 卡片页）。")
                add("")
                continue
            for i, t in enumerate(ts, 1):
                meta = []
                if t["host"] == "CrudTable":
                    meta.append(f"`CrudTable :table=\"{t['data']}\"`")
                elif t["data"]:
                    meta.append(f"`:data=\"{t['data']}\"`")
                if t["testid"]:
                    meta.append(f"testid=`{t['testid']}`")
                if t["vif"]:
                    meta.append(f"v-if=`{t['vif']}`")
                if t.get("nested"):
                    meta.append("展开行内子表")
                if t["src"] != r["component"]:
                    meta.append(f"来源 `{t['src']}`")
                add(f"**表 {i}**" + (f" — {' · '.join(meta)}" if meta else ""))
                add("")
                real = [c for c in t["cols"] if c["kind"] == "col"]
                special = [c for c in t["cols"] if c["kind"] != "col"]
                if special:
                    add("特殊列：" + "、".join(c["kind"] for c in special))
                    add("")
                if not real:
                    add("_无 label 数据列（仅 selection 或动态列）_")
                    add("")
                    continue
                add("| # | prop | label |")
                add("|---|------|-------|")
                for j, c in enumerate(real, 1):
                    add(f"| {j} | `{c['prop'] or '—'}` | {c['label']} |")
                add("")
            if scan["desc_labels"]:
                add(
                    "同页 el-descriptions："
                    + "、".join(f"`{x}`" for x in scan["desc_labels"])
                )
                add("")

    add("## 3. 单页多表（≥2）")
    add("")
    for r in routes:
        ts = per_route[r["path"]]
        if len(ts) >= 2:
            bits = []
            for i, t in enumerate(ts, 1):
                n = len([c for c in t["cols"] if c["kind"] == "col"])
                label = t["data"] or "?"
                if t["src"] != r["component"]:
                    label = f"{label} @ {t['src'].split('/')[-1]}"
                bits.append(f"#{i} `{label}`({n}列)")
            add(f"- `{r['path']}`：{'；'.join(bits)}")
    add("")

    add("## 4. 列偏少（数据列 ≤ 4）")
    add("")
    add("| 路径 | 表 | 来源 | data | 列 |")
    add("|------|----|------|------|----|")
    for r in routes:
        for i, t in enumerate(per_route[r["path"]], 1):
            real = [c for c in t["cols"] if c["kind"] == "col"]
            if 0 < len(real) <= 4:
                add(
                    f"| `{r['path']}` | #{i} | `{t['src']}` | `{t['data'] or '—'}` | "
                    + "、".join(c["label"] for c in real)
                    + " |"
                )
    add("")

    add("## 5. 已挂路由但无表格的页面")
    add("")
    for r in routes:
        scan = scans.get(r["component"])
        if scan and scan["exists"] and not per_route[r["path"]]:
            add(f"- `{r['path']}` {r['title']} → `{r['component']}`")
    add("")

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(
        f"Wrote {OUT}\n"
        f"routes={len(routes)} redirects={len(redirects)} views={len(view_files)} "
        f"components={len(comp_files)} tables={uniq_tables} cols={uniq_cols} "
        f"pages_with_tables={pages_with_tables}"
    )


if __name__ == "__main__":
    main()
