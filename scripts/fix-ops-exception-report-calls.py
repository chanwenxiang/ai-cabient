#!/usr/bin/env python3
"""Rewrite OpsExceptionService.report 8-arg calls to 5-arg refs-based calls."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TRADE = ROOT / "services/trade-service/src/main/java"

PATTERN = re.compile(
    r"(?P<prefix>\b(?:opsExceptionService|exceptionService)\.report\(\s*)"
    r"(?P<a1>[^,]+?),\s*"
    r"(?P<a2>[^,]+?),\s*"
    r"(?P<a3>[^,]+?),\s*"
    r"(?P<a4>[^,]+?),\s*"
    r"(?P<a5>[^,]+?),\s*"
    r"(?P<a6>[^,]+?),\s*"
    r"(?P<a7>[^,]+?),\s*"
    r"(?P<a8>[^)]+?)\s*\)",
    re.DOTALL,
)

REPLACEMENT = (
    r"\g<prefix>\g<a1>, \g<a2>, "
    r"new OpsExceptionService.ExceptionReport.ExceptionRefs(\g<a3>, \g<a4>, \g<a5>, \g<a6>), "
    r"\g<a7>, \g<a8>)"
)


def main() -> None:
    changed = 0
    for path in TRADE.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        if ".report(" not in text:
            continue
        new_text, n = PATTERN.subn(REPLACEMENT, text)
        if n:
            path.write_text(new_text, encoding="utf-8")
            print(f"{path.relative_to(ROOT)}: {n}")
            changed += n
    print(f"Total replacements: {changed}")


if __name__ == "__main__":
    main()
