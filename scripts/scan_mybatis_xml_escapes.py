#!/usr/bin/env python3
"""Scan mapper annotations for unescaped < before #{...}."""
from pathlib import Path
import re

root = Path("services/trade-service/src/main/java/com/aicabinet/trade/mapper")
pat = re.compile(
    r"@((?:org\.apache\.ibatis\.annotations\.)?(?:Select|Delete|Update|Insert))"
    r"\(\s*\"\"\"(.*?)\"\"\"\s*\)",
    re.S,
)
tag = re.compile(
    r"</?(?:script|foreach|if|choose|when|otherwise|trim|where|set)(?:\s[^>]*)?>",
    re.I,
)

for p in sorted(root.glob("*.java")):
    text = p.read_text(encoding="utf-8")
    for m in pat.finditer(text):
        cleaned = tag.sub(" ", m.group(2))
        bad = re.findall(r"<[^&]", cleaned)
        if bad:
            print(p.name, bad)
