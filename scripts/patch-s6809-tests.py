#!/usr/bin/env python3
"""Patch unit tests: add null self ctor arg + ReflectionTestUtils.setField."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEST_DIR = ROOT / "services/trade-service/src/test/java"
SERVICE_DIR = ROOT / "services/trade-service/src/main/java/com/aicabinet/trade/service"

SELF_SERVICES: list[str] = []
for path in SERVICE_DIR.glob("*.java"):
    text = path.read_text(encoding="utf-8")
    cn = path.stem
    if re.search(rf"private\s+final\s+{cn}\s+self\s*;", text):
        SELF_SERVICES.append(cn)

SETFIELD = 'org.springframework.test.util.ReflectionTestUtils.setField({var}, "self", {var});'


def patch_constructor(text: str, cn: str) -> tuple[str, int]:
    pattern = re.compile(rf"new\s+{cn}\s*\(", re.MULTILINE)
    changes = 0
    pos = 0
    out = []
    while True:
        m = pattern.search(text, pos)
        if not m:
            out.append(text[pos:])
            break
        out.append(text[pos : m.start()])
        i = m.end()
        depth = 1
        while i < len(text) and depth > 0:
            ch = text[i]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            i += 1
        ctor = text[m.start() : i]
        if re.search(rf"@Lazy\s+{cn}\s+self|null\s*\)\s*;?\s*$", ctor):
            out.append(ctor)
        else:
            ctor = ctor[:-1] + ", null)"
            changes += 1
            out.append(ctor)
        pos = i
    return "".join(out), changes


def ensure_setfield(text: str, cn: str) -> tuple[str, int]:
    changes = 0
    # assignment: var = new ClassName(...)
    assign_re = re.compile(
        rf"^(\s*)(\w+)\s*=\s*new\s+{cn}\s*\(",
        re.MULTILINE,
    )
    for m in assign_re.finditer(text):
        var = m.group(2)
        line_start = m.start()
        line_end = text.find("\n", line_start)
        block_end = line_end
        # find end of statement (;)
        paren = 0
        started = False
        j = m.end() - 1
        while j < len(text):
            if text[j] == "(":
                paren += 1
                started = True
            elif text[j] == ")":
                paren -= 1
            if started and paren == 0 and text[j] == ";":
                block_end = j + 1
                break
            j += 1
        after = text[block_end : block_end + 200]
        setline = SETFIELD.format(var=var)
        if setline.split("(")[0] in after:
            continue
        indent = m.group(1)
        insert = f"\n{indent}{setline}"
        text = text[:block_end] + insert + text[block_end:]
        changes += 1
    return text, changes


def main() -> None:
    total_ctor = total_sf = 0
    for test_path in TEST_DIR.rglob("*.java"):
        text = test_path.read_text(encoding="utf-8")
        orig = text
        for cn in SELF_SERVICES:
            if f"new {cn}(" not in text:
                continue
            text, c = patch_constructor(text, cn)
            total_ctor += c
            text, s = ensure_setfield(text, cn)
            total_sf += s
        if text != orig:
            test_path.write_text(text, encoding="utf-8", newline="\n")
            print(f"Patched {test_path.relative_to(ROOT)}")
    print(f"Ctor patches: {total_ctor}, setField patches: {total_sf}, services: {len(SELF_SERVICES)}")


if __name__ == "__main__":
    main()
