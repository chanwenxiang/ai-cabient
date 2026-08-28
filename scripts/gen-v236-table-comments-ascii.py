# -*- coding: utf-8 -*-
"""Generate encoding-safe V236 TABLE comments from curated map."""
from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "services/trade-service/src/main/resources/db/migration/V236__schema_comments_backfill.sql"


def load_table_comments() -> dict[str, str]:
    path = ROOT / "scripts/gen-schema-comments-v236.py"
    spec = importlib.util.spec_from_file_location("gen_v236", path)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    comments = dict(mod.TABLE_COMMENTS)
    comments.update(
        {
            "approval_definition": "审批流定义（按 biz_type）",
            "approval_instance": "审批实例（业务单据运行中/已完成）",
            "approval_node": "审批节点（顺序；assignee_type=PERM|ROLE）",
            "approval_task": "审批待办（按人；ANY 规则一人通过即完成本节点）",
        }
    )
    return comments


def to_u(text: str) -> str:
    parts: list[str] = []
    for ch in text:
        code = ord(ch)
        if ch == "'":
            parts.append("''")
        elif code < 128 and ch != "\\":
            parts.append(ch)
        else:
            parts.append(f"\\{code:04x}")
    return "U&'" + "".join(parts) + "'"


def main() -> None:
    comments = load_table_comments()
    lines = [
        "-- V236: backfill missing TABLE comments (Unicode-escaped, encoding-safe)",
        "-- Column comments are handled by V237.",
        "-- Idempotent.",
        "",
    ]
    for table in sorted(comments):
        lines.append(f"COMMENT ON TABLE {table} IS {to_u(comments[table])};")
    text = "\n".join(lines) + "\n"
    OUT.write_text(text, encoding="utf-8")
    OUT.read_text(encoding="ascii")
    print(f"wrote {OUT.name} tables={len(comments)} bytes={OUT.stat().st_size}")


if __name__ == "__main__":
    main()
