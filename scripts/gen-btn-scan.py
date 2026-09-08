# -*- coding: utf-8 -*-
"""Extract Chinese button/action labels from admin/merchant/consumer pages."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def extract_el_buttons(text: str) -> list[str]:
    labels = []
    for m in re.finditer(r"<el-button\b([^>]*)>(.*?)</el-button>", text, re.S | re.I):
        attrs, body = m.group(1), m.group(2)
        body = re.sub(r"<[^>]+>", "", body)
        body = re.sub(r"\{\{.*?\}\}", "", body)
        body = " ".join(body.split()).strip()
        if body and re.search(r"[\u4e00-\u9fff]", body) and len(body) <= 32:
            labels.append(body)
        for am in re.finditer(r"(?:title|aria-label)=[\"']([^\"']+)[\"']", attrs):
            t = am.group(1).strip()
            if re.search(r"[\u4e00-\u9fff]", t) and len(t) <= 32:
                labels.append(t)
    return labels


def extract_mp_actions(text: str) -> list[str]:
    labels = []
    # common uni-app button / text button patterns
    for m in re.finditer(
        r"<(?:button|view|text)\b[^>]*class=[\"'][^\"']*(?:btn|button|action|primary)[^\"']*[\"'][^>]*>\s*([^<{]+?)\s*</",
        text,
        re.S | re.I,
    ):
        s = " ".join(m.group(1).split()).strip()
        if re.search(r"[\u4e00-\u9fff]", s) and len(s) <= 24:
            labels.append(s)
    for m in re.finditer(r"<button\b[^>]*>\s*([^<{]+?)\s*</button>", text, re.S | re.I):
        s = " ".join(m.group(1).split()).strip()
        if re.search(r"[\u4e00-\u9fff]", s) and len(s) <= 24:
            labels.append(s)
    # bare Chinese next to click handlers in template (heuristic)
    for m in re.finditer(
        r"@click(?:\.stop)?=[\"'][^\"']+[\"'][^>]*>\s*([\u4e00-\u9fffA-Za-z0-9¥￥·（）()：:\s]{1,20})\s*<",
        text,
    ):
        s = " ".join(m.group(1).split()).strip()
        if re.search(r"[\u4e00-\u9fff]", s) and len(s) <= 20:
            labels.append(s)
    return labels


NOISE = {
    "加载中",
    "暂无数据",
    "重试",
    "取消",
    "确定",
    "关闭",
    "返回",
    "搜索",
    "查询",
    "重置",
    "刷新",
}


def scan(root: Path, kind: str) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for p in sorted(root.rglob("*")):
        if p.suffix not in {".vue", ".uvue"}:
            continue
        try:
            text = p.read_text(encoding="utf-8")
        except OSError:
            continue
        if kind == "admin":
            labels = extract_el_buttons(text)
        else:
            labels = extract_mp_actions(text)
        # dedupe preserve order
        seen = set()
        keep = []
        for x in labels:
            if x in seen:
                continue
            seen.add(x)
            if x in NOISE:
                continue
            keep.append(x)
        if keep:
            rel = str(p.relative_to(root)).replace("\\", "/")
            out[rel] = keep[:60]
    return out


def main() -> None:
    data = {
        "admin": scan(ROOT / "clients/admin-vue/src/views", "admin"),
        "merchant": scan(ROOT / "clients/merchant-mp/src/pages", "mp"),
        "consumer": scan(ROOT / "clients/consumer-mp/src/pages", "mp"),
    }
    out = Path(__file__).resolve().parent / "_btn_scan_cache.json"
    out.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(
        "admin",
        len(data["admin"]),
        "merchant",
        len(data["merchant"]),
        "consumer",
        len(data["consumer"]),
        "->",
        out,
    )


if __name__ == "__main__":
    main()
