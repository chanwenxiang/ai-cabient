#!/usr/bin/env python3
"""Fix java:S1192 by extracting duplicated string literals to private constants per class."""
from __future__ import annotations

import base64
import json
import re
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOTS = [
    ROOT / "services/trade-service/src/main/java",
    ROOT / "services/device-service/src/main/java",
    ROOT / "services/common/common-core/src/main/java",
]

# Prefer shared constants when literal already exists in CabinetConstants
CABINET_CONSTANTS = {
    "ACTIVE": "CabinetConstants.PROMOTION_STATUS_ACTIVE",
    "PAID": "CabinetConstants.ORDER_STATUS_PAID",
    "REFUNDED": "CabinetConstants.ORDER_STATUS_REFUNDED",
    "DISPUTED": "CabinetConstants.ORDER_STATUS_DISPUTED",
    "FAILED": "CabinetConstants.ORDER_STATUS_FAILED",
    "ONLINE": "CabinetConstants.DEVICE_ONLINE",
    "OFFLINE": "CabinetConstants.DEVICE_OFFLINE",
    "DRAFT": "CabinetConstants.PROMOTION_STATUS_DRAFT",
    "STOPPED": "CabinetConstants.PROMOTION_STATUS_STOPPED",
    "UNUSED": "CabinetConstants.COUPON_STATUS_UNUSED",
    "USED": "CabinetConstants.COUPON_STATUS_USED",
    "EXPIRED": "CabinetConstants.COUPON_STATUS_EXPIRED",
    "WECHAT": "CabinetConstants.PAY_CHANNEL_WECHAT",
    "BALANCE": "CabinetConstants.PAY_CHANNEL_BALANCE",
    "ALIPAY": "CabinetConstants.PAY_CHANNEL_ALIPAY",
    "DISABLED": "CabinetConstants.SKU_STATUS_DISABLED",
}


def load_token() -> str:
    for line in (ROOT / "infra/.env").read_text(encoding="utf-8").splitlines():
        if line.startswith("SONAR_TOKEN="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("SONAR_TOKEN missing")


def fetch_issues() -> list[dict]:
    token = load_token()
    auth = base64.b64encode(f"{token}:".encode()).decode()
    headers = {"Authorization": f"Basic {auth}"}
    issues: list[dict] = []
    page = 1
    while True:
        url = (
            "http://localhost:19002/api/issues/search?"
            f"componentKeys=ai-cabinet-dev&rules=java:S1192&statuses=OPEN&branch=dev&ps=500&p={page}"
        )
        r = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=headers)))
        issues.extend(r["issues"])
        if len(issues) >= r["total"]:
            break
        page += 1
    return issues


def literal_from_message(msg: str) -> str | None:
    m = re.search(r'literal "([^"]+)"', msg)
    return m.group(1) if m else None


def to_const_name(literal: str) -> str:
    s = re.sub(r"[^A-Za-z0-9]+", "_", literal.strip()).strip("_").upper()
    if not s:
        s = "LITERAL"
    if s[0].isdigit():
        s = "V_" + s
    if literal.startswith("ops:"):
        return "PERM_" + s
    status_words = {
        "ACTIVE", "PENDING", "CANCELLED", "COMPLETED", "DRAFT", "FAILED",
        "IN_PROGRESS", "APPROVED", "REJECTED", "OPEN", "CLOSED", "RESOLVED",
        "NORMAL", "SUCCESS", "SKIPPED", "PUBLISHED", "ARCHIVED", "DISABLED",
    }
    if s in status_words:
        return "STATUS_" + s
    return s


def count_literal_in_file(text: str, literal: str) -> int:
    pat = re.compile(re.escape(f'"{literal}"'))
    return len(pat.findall(text))


def already_has_constant(text: str, literal: str) -> str | None:
    esc = re.escape(literal)
    m = re.search(rf'private\s+static\s+final\s+String\s+(\w+)\s*=\s*"{esc}"\s*;', text)
    if m:
        return m.group(1)
    if literal in CABINET_CONSTANTS and CABINET_CONSTANTS[literal] in text:
        return CABINET_CONSTANTS[literal]
    return None


def ensure_cabinet_import(text: str) -> str:
    imp = "import com.aicabinet.common.constants.CabinetConstants;"
    if imp in text:
        return text
    pkg = re.search(r"^package .+;\n", text, re.M)
    if not pkg:
        return text
    end = pkg.end()
    return text[:end] + imp + "\n" + text[end:]


def insert_constants(text: str, class_name: str, additions: list[tuple[str, str, str]]) -> str:
    """additions: (const_name, literal, replacement_ref)"""
    if not additions:
        return text
    block_lines = []
    for const_name, literal, replacement in additions:
        if replacement.startswith("CabinetConstants."):
            continue  # no local const
        block_lines.append(f'    private static final String {const_name} = "{literal}";')
    if not block_lines:
        return text

    # after class opening brace
    m = re.search(rf"(public\s+(?:final\s+)?class\s+{class_name}[^{{]*\{{)", text)
    if not m:
        return text
    insert_at = m.end()
    block = "\n" + "\n".join(block_lines) + "\n"
    return text[:insert_at] + block + text[insert_at:]


def replace_literal(text: str, literal: str, replacement: str) -> str:
    """Replace usages but preserve private static final String ... = \"literal\" initializers."""
    esc = re.escape(literal)
    defn_pat = re.compile(rf'(private static final String \w+ = )"{esc}"(\s*;)')
    protected: list[str] = []

    def protect(match: re.Match[str]) -> str:
        protected.append(match.group(0))
        return f"__S1192_DEF_{len(protected) - 1}__"

    text = defn_pat.sub(protect, text)
    text = re.sub(rf'"{esc}"', replacement, text)
    for i, original in enumerate(protected):
        text = text.replace(f"__S1192_DEF_{i}__", original)
    return text


def class_name_from_path(path: Path) -> str:
    return path.stem


def resolve_path(component: str) -> Path | None:
    rel = component.split(":", 1)[-1]
    for root in JAVA_ROOTS:
        candidate = ROOT / rel.replace("/", "\\") if "\\" in str(ROOT) else ROOT / rel
        if candidate.exists():
            return candidate
    p = ROOT / rel
    return p if p.exists() else None


def fix_file(path: Path, literals: set[str]) -> bool:
    text = path.read_text(encoding="utf-8")
    orig = text
    cn = class_name_from_path(path)
    needs_cabinet = False
    local_additions: list[tuple[str, str, str]] = []
    replacements: list[tuple[str, str]] = []

    for literal in sorted(literals, key=len, reverse=True):
        if count_literal_in_file(text, literal) < 3:
            continue
        existing = already_has_constant(text, literal)
        if existing:
            replacements.append((literal, existing))
            continue
        if literal in CABINET_CONSTANTS:
            replacements.append((literal, CABINET_CONSTANTS[literal]))
            needs_cabinet = True
            continue
        const_name = to_const_name(literal)
        # avoid duplicate const names
        base = const_name
        i = 2
        while any(a[0] == const_name for a in local_additions) or re.search(
            rf"String\s+{const_name}\s*=", text
        ):
            const_name = f"{base}_{i}"
            i += 1
        local_additions.append((const_name, literal, const_name))
        replacements.append((literal, const_name))

    if not replacements:
        return False

    text = insert_constants(text, cn, local_additions)
    if needs_cabinet:
        text = ensure_cabinet_import(text)

    for literal, ref in replacements:
        text = replace_literal(text, literal, ref)

    if text != orig:
        path.write_text(text, encoding="utf-8", newline="\n")
        return True
    return False


def main() -> None:
    issues = fetch_issues()
    by_file: dict[str, set[str]] = defaultdict(set)
    pat_msg = re.compile(r'literal "([^"]+)"')
    for issue in issues:
        comp = issue["component"]
        lit = literal_from_message(issue["message"])
        if lit:
            by_file[comp].add(lit)

    changed = 0
    for comp, lits in sorted(by_file.items()):
        path = resolve_path(comp)
        if not path:
            continue
        if fix_file(path, lits):
            print(f"Fixed {path.relative_to(ROOT)}")
            changed += 1
    print(f"\nUpdated {changed} files")


if __name__ == "__main__":
    main()
