#!/usr/bin/env python3
"""Rename MyBatis mapper methods that violate java:S100 (leading underscore)."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAPPER_JAVA = ROOT / "services/trade-service/src/main/java/com/aicabinet/trade/mapper"
MAPPER_XML = ROOT / "services/trade-service/src/main/resources/mapper"

METHOD_DECL = re.compile(
    r"^(\s+)([\w<>,\[\].\s]+)\s+(_\w+)\s*\(",
    re.MULTILINE,
)
METHOD_CALL = re.compile(r"\b(_\w+)\s*\(")


def new_name(old: str, existing: set[str]) -> str:
    candidate = old[1:]  # drop leading _
    if candidate not in existing:
        return candidate
    # e.g. _sumSoldQtyBySkuSince vs sumSoldQtyBySkuSince default wrapper
    alt = "select" + candidate[0].upper() + candidate[1:]
    if alt not in existing:
        return alt
    raise ValueError(f"cannot rename {old}, candidates exhausted")


def collect_methods(text: str) -> list[str]:
    return [m.group(3) for m in METHOD_DECL.finditer(text)]


def rename_in_file(path: Path, renames: dict[str, str]) -> bool:
    text = path.read_text(encoding="utf-8")
    original = text
    for old, new in sorted(renames.items(), key=lambda x: -len(x[0])):
        text = re.sub(rf"\b{re.escape(old)}\b", new, text)
    if text != original:
        path.write_text(text, encoding="utf-8", newline="\n")
        return True
    return False


def main() -> None:
    all_renames: dict[str, str] = {}
    per_file: dict[Path, dict[str, str]] = {}

    for java_path in sorted(MAPPER_JAVA.glob("*.java")):
        text = java_path.read_text(encoding="utf-8")
        declared = collect_methods(text)
        if not declared:
            continue
        existing = set(re.findall(r"\b([a-z][a-zA-Z0-9]*)\s*\(", text))
        file_renames: dict[str, str] = {}
        for old in declared:
            if not old.startswith("_"):
                continue
            new = new_name(old, existing)
            file_renames[old] = new
            existing.add(new)
            all_renames[old] = new
        if file_renames:
            per_file[java_path] = file_renames

    changed_java = 0
    for path, renames in per_file.items():
        if rename_in_file(path, renames):
            changed_java += 1
            print(f"java {path.name}: {', '.join(f'{a}->{b}' for a, b in renames.items())}")

    changed_xml = 0
    for xml_path in sorted(MAPPER_XML.glob("*.xml")):
        text = xml_path.read_text(encoding="utf-8")
        file_renames = {old: new for old, new in all_renames.items() if old in text}
        if not file_renames:
            continue
        if rename_in_file(xml_path, file_renames):
            changed_xml += 1
            print(f"xml  {xml_path.name}")

    print(f"done: {len(all_renames)} renames, {changed_java} java, {changed_xml} xml")


if __name__ == "__main__":
    main()
