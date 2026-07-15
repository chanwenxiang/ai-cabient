#!/usr/bin/env python3
"""Fix native @Query methods and @Select text-block formatting in generated mappers."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO_DIR = ROOT / "services/trade-service/src/main/java/com/aicabinet/trade/repository"
MAPPER_DIR = ROOT / "services/trade-service/src/main/java/com/aicabinet/trade/mapper"


def extract_native_methods(repo_text: str) -> list[tuple[str, str, str]]:
    """Return list of (method_name, sql, full_signature_line_ish)."""
    results = []
    # Find @Query(... nativeQuery = true) preceding a method
    pattern = re.compile(
        r"@Query\s*\(\s*value\s*=\s*(?:\"\"\"(.*?)\"\"\"|\"([^\"]+)\")\s*,\s*nativeQuery\s*=\s*true\s*\)\s*"
        r"(?:public\s+)?([\w.<>,\s\[\]]+)\s+(\w+)\s*\(([^;]*)\)\s*;",
        re.S,
    )
    for m in pattern.finditer(repo_text):
        sql = (m.group(1) or m.group(2) or "").strip()
        sql = re.sub(r":(\w+)", r"#{\1}", sql)
        ret = " ".join(m.group(3).split())
        name = m.group(4)
        params = m.group(5).strip()
        results.append((name, sql, ret, params))

    # Also @Query("...", nativeQuery=true) shorter form
    pattern2 = re.compile(
        r"@Query\s*\(\s*(?:value\s*=\s*)?\"([^\"]+)\"\s*,\s*nativeQuery\s*=\s*true\s*\)\s*"
        r"(?:public\s+)?([\w.<>,\s\[\]]+)\s+(\w+)\s*\(([^;]*)\)\s*;",
        re.S,
    )
    for m in pattern2.finditer(repo_text):
        sql = re.sub(r":(\w+)", r"#{\1}", m.group(1).strip())
        ret = " ".join(m.group(2).split())
        name = m.group(3)
        params = m.group(4).strip()
        if not any(r[0] == name for r in results):
            results.append((name, sql, ret, params))
    return results


def format_select(sql: str) -> str:
    lines = sql.strip().splitlines()
    if len(lines) == 1:
        return f'@Select("""\n            {lines[0]}\n            """)'
    body = "\n".join("            " + ln for ln in lines)
    return f'@Select("""\n{body}\n            """)'


def fix_mapper(repo_path: Path) -> None:
    repo_text = repo_path.read_text(encoding="utf-8")
    mapper_name = repo_path.name.replace("Repository", "Mapper")
    mapper_path = MAPPER_DIR / mapper_name
    if not mapper_path.exists():
        return
    mapper = mapper_path.read_text(encoding="utf-8")
    natives = extract_native_methods(repo_text)
    for name, sql, ret, params in natives:
        # Replace wrong default method or any method with this name
        select_ann = format_select(sql)
        new_method = f"\n    {select_ann}\n    {ret} {name}({params});\n"
        # remove existing default/annotated method with same name
        mapper = re.sub(
            rf"(?:@Select\(\"\"\".*?\"\"\"\)\s*)?(?:default\s+)?[\w.<>,\s\[\]]+\s+_{name}Raw\s*\([^;]*\);\s*"
            rf"default\s+[\w.<>,\s\[\]]+\s+{name}\s*\([^)]*\)\s*\{{.*?\}}\s*",
            "",
            mapper,
            flags=re.S,
        )
        mapper = re.sub(
            rf"(?:@Select\([^\)]*\)\s*)?(?:default\s+)?[\w.<>,\s\[\]]+\s+{name}\s*\([^)]*\)\s*(?:\{{.*?}}|;)\s*",
            "",
            mapper,
            flags=re.S,
        )
        # insert before closing brace
        mapper = re.sub(r"\n\}\s*\Z", new_method + "\n}\n", mapper)
        if "import org.apache.ibatis.annotations.Select;" not in mapper:
            mapper = mapper.replace(
                "import org.apache.ibatis.annotations.Mapper;",
                "import org.apache.ibatis.annotations.Mapper;\nimport org.apache.ibatis.annotations.Select;",
            )
        print(f"  fixed native {mapper_name}.{name}")

    # Fix broken one-line text blocks: @Select("""SQL""")
    def fix_select(m: re.Match) -> str:
        sql = m.group(1).strip()
        return format_select(sql)

    mapper = re.sub(r'@Select\("""([^"]*?)"""\)', fix_select, mapper, flags=re.S)

    # Add FOR UPDATE when method name contains ForUpdate
    mapper = re.sub(
        r'(@Select\("""\s*)(SELECT \* FROM user_account WHERE user_id = #\{userId\})(\s*"""\))',
        r'\1\2 FOR UPDATE\3',
        mapper,
    )

    mapper_path.write_text(mapper, encoding="utf-8", newline="\n")


def main() -> None:
    for p in sorted(REPO_DIR.glob("*Repository.java")):
        fix_mapper(p)
    print("DONE fixups")


if __name__ == "__main__":
    main()
