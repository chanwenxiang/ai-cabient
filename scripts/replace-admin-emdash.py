#!/usr/bin/env python3
"""Replace em-dash empty placeholders in admin-vue with meaningful Chinese."""
from __future__ import annotations

import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1] / "clients" / "admin-vue" / "src"
EM = "\u2014"  # —

# Contextual replacements applied in order (more specific first)
REPLACEMENTS: list[tuple[re.Pattern[str], str]] = [
    # Loading / not hydrated → ellipsis
    (
        re.compile(
            rf"(listHydrated|hydrated|ready|profileReady|boardHydrated|profileHydrated\.value)\s*\?\s*([^:?]+?)\s*:\s*['\"]{EM}['\"]"
        ),
        r"\1 ? \2 : '…'",
    ),
    # return '—' in formatters → 暂无 / 未统计 handled below with simpler global
    (re.compile(rf"return\s+['\"]{EM}['\"]"), "return '暂无'"),
    # ?? '—' after numbers often counts — keep as 暂无 for safety; numeric pages fixed manually
    (re.compile(rf"\?\?\s*['\"]{EM}['\"]"), "?? '暂无'"),
    (re.compile(rf"\|\|\s*['\"]{EM}['\"]"), "|| '暂无'"),
    (re.compile(rf":\s*['\"]{EM}['\"]"), ": '暂无'"),
    (re.compile(rf"['\"]{EM}['\"]"), "'暂无'"),
]


def transform(text: str) -> str:
    out = text
    for pat, repl in REPLACEMENTS:
        out = pat.sub(repl, out)
    # leftover bare em dash in templates as text content between tags
    out = out.replace(f">{EM}</", ">暂无</")
    out = out.replace(f">{EM} ", ">暂无 ")
    return out


def main() -> None:
    changed = 0
    for path in list(ROOT.rglob("*.vue")) + list(ROOT.rglob("*.ts")):
        if path.name == "display.ts":
            continue
        raw = path.read_text(encoding="utf-8")
        if EM not in raw:
            continue
        new = transform(raw)
        if new != raw:
            path.write_text(new, encoding="utf-8", newline="\n")
            changed += 1
            print("updated", path.relative_to(ROOT))
    print("files_changed", changed)
    # verify leftovers
    left = 0
    for path in list(ROOT.rglob("*.vue")) + list(ROOT.rglob("*.ts")):
        t = path.read_text(encoding="utf-8")
        c = t.count(EM)
        if c:
            left += c
            print("remain", c, path.relative_to(ROOT))
    print("emdash_remain", left)


if __name__ == "__main__":
    main()
