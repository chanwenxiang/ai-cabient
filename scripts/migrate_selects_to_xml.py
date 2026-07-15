#!/usr/bin/env python3
"""Move remaining @Select/@Delete/@Insert/@Update annotations into MyBatis XML mappers."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "services" / "trade-service"
MAPPER_JAVA = ROOT / "src" / "main" / "java" / "com" / "aicabinet" / "trade" / "mapper"
MAPPER_XML = ROOT / "src" / "main" / "resources" / "mapper"

ANN_START = re.compile(r'@(Select|Insert|Update|Delete)\(\s*("""|")', re.M)
METHOD_TAIL = re.compile(
    r'\)\s*(?:public\s+)?([\w.<>,\s\[\]]+?)\s+(\w+)\s*\(',
    re.S,
)


def unescape(sql: str) -> str:
    return (
        sql.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", '"')
    )


def result_type(ret: str) -> str:
    r = ret.strip().replace(" ", "")
    mapping = {
        "long": "long",
        "Long": "long",
        "int": "int",
        "Integer": "int",
        "boolean": "boolean",
        "Boolean": "boolean",
        "double": "double",
        "Double": "double",
        "String": "string",
        "java.lang.String": "string",
        "Object[]": "map",
        "java.lang.Object[]": "map",
        "Map": "map",
        "java.util.Map": "map",
        "Map<String,Object>": "map",
        "java.util.Map<String,Object>": "map",
        "List<String>": "string",
        "java.util.List<String>": "string",
        "List<Object[]>": "map",
        "java.util.List<Object[]>": "map",
        "List<Map<String,Object>>": "map",
        "Instant": "java.time.Instant",
        "java.time.Instant": "java.time.Instant",
        "LocalDate": "java.time.LocalDate",
        "java.time.LocalDate": "java.time.LocalDate",
        "BigDecimal": "java.math.BigDecimal",
        "java.math.BigDecimal": "java.math.BigDecimal",
    }
    if r in mapping:
        return mapping[r]
    m = re.match(r"(?:java\.util\.)?List<(.+)>", r)
    if m:
        inner = m.group(1).strip()
        if not inner.startswith("com.") and not inner.startswith("java."):
            if re.match(r"^[A-Z]\w+$", inner):
                return f"com.aicabinet.trade.domain.{inner}"
        return inner
    if re.match(r"^[A-Z]\w+$", r) and not r.startswith("java"):
        return f"com.aicabinet.trade.domain.{r}"
    return r


def wrap_sql(sql: str) -> str:
    sql = unescape(sql.strip())
    # strip outer <script> if present — XML body does not need it; foreach stays raw
    if sql.startswith("<script>") and sql.endswith("</script>"):
        sql = sql[len("<script>") : -len("</script>")].strip()
    # If still has mybatis dynamic tags, embed raw; comparison-only → CDATA
    if re.search(r"<(foreach|if|choose|when|otherwise|where|trim|set)\b", sql):
        return "\n    " + sql + "\n  "
    return "\n    <![CDATA[\n    " + sql + "\n    ]]>\n  "


def balance_params(text: str, start: int) -> int:
    """Find index after the closing ')' of method params starting at '('."""
    depth = 0
    i = start
    while i < len(text):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError("unbalanced params")


def extract_annotations(text: str) -> list[tuple[int, int, str, str, str, str]]:
    """Return list of (start, end, tag, sql, method_name, result_type_raw)."""
    items = []
    for m in ANN_START.finditer(text):
        tag = m.group(1).lower()
        quote = m.group(2)
        sql_start = m.end()
        if quote == '"""':
            sql_end = text.find('"""', sql_start)
            if sql_end < 0:
                continue
            sql = text[sql_start:sql_end]
            after_str = sql_end + 3
        else:
            # single "string" — find unescaped "
            i = sql_start
            while i < len(text):
                if text[i] == "\\" and i + 1 < len(text):
                    i += 2
                    continue
                if text[i] == '"':
                    break
                i += 1
            else:
                continue
            sql = text[sql_start:i]
            after_str = i + 1

        # find closing ) of annotation, then return type + method name + (
        rest = text[after_str:]
        mt = METHOD_TAIL.match(rest)
        if not mt:
            # maybe whitespace / newline then )
            mt = re.match(r"\s*\)\s*(?:public\s+)?([\w.<>,\s\[\]]+?)\s+(\w+)\s*\(", rest, re.S)
            if not mt:
                print(f"  skip: cannot parse method after @{tag} at {m.start()}")
                continue
        ret = mt.group(1).strip()
        name = mt.group(2)
        # absolute index of '(' after method name
        paren_abs = after_str + mt.end() - 1
        after_params = balance_params(text, paren_abs)
        # skip whitespace and optional ;
        end = after_params
        while end < len(text) and text[end] in " \t\r\n":
            end += 1
        if end < len(text) and text[end] == ";":
            end += 1
        items.append((m.start(), end, tag, sql, name, ret))
    return items


def process(java_path: Path) -> int:
    text = java_path.read_text(encoding="utf-8")
    if "@Select" not in text and "@Delete" not in text and "@Insert" not in text and "@Update" not in text:
        return 0

    ns = "com.aicabinet.trade.mapper." + java_path.stem
    items = extract_annotations(text)
    if not items:
        return 0

    xml_path = MAPPER_XML / f"{java_path.stem}.xml"
    if xml_path.exists():
        existing = xml_path.read_text(encoding="utf-8")
        # strip closing </mapper> to append
        if "</mapper>" in existing:
            body = existing[: existing.rfind("</mapper>")].rstrip() + "\n"
        else:
            body = existing
    else:
        body = (
            '<?xml version="1.0" encoding="UTF-8" ?>\n'
            '<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"\n'
            '        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">\n'
            f'<mapper namespace="{ns}">\n'
        )

    # avoid duplicate ids
    for _, _, tag, sql, name, ret in items:
        if f'id="{name}"' in body:
            continue
        rt = result_type(ret)
        sql_body = wrap_sql(sql)
        if tag == "select":
            body += f'\n  <select id="{name}" resultType="{rt}">{sql_body}</select>\n'
        else:
            body += f'\n  <{tag} id="{name}">{sql_body}</{tag}>\n'

    body = body.rstrip() + "\n</mapper>\n"
    MAPPER_XML.mkdir(parents=True, exist_ok=True)
    xml_path.write_text(body, encoding="utf-8")

    # remove annotations from java (from end)
    for start, end, tag, sql, name, ret in sorted(items, key=lambda x: x[0], reverse=True):
        text = text[:start] + f"    {ret.strip()} {name}(PLACEHOLDER);\n" + text[end:]
        # restore original params by re-extracting from a copy — easier: keep params via second pass
    # Fix placeholders: re-read original and replace properly
    original = java_path.read_text(encoding="utf-8")
    items2 = extract_annotations(original)
    text = original
    for start, end, tag, sql, name, ret in sorted(items2, key=lambda x: x[0], reverse=True):
        # extract method declaration without annotation
        chunk = original[start:end]
        # find method signature after closing of annotation string
        # chunk starts with @Select...
        mt = METHOD_TAIL.search(chunk)
        if not mt:
            mt = re.search(
                r"\)\s*(?:public\s+)?([\w.<>,\s\[\]]+?)\s+(\w+)\s*\(",
                chunk,
                re.S,
            )
        if not mt:
            raise RuntimeError(f"cannot strip {name} in {java_path.name}")
        # signature starts at return type
        sig_start = mt.start(1)
        # find params in chunk
        paren_rel = mt.end() - 1
        abs_paren = start + paren_rel
        after_params = balance_params(original, abs_paren)
        sig = original[start + sig_start : after_params].strip()
        if not sig.endswith(";"):
            # ensure ends with );
            if not sig.endswith(")"):
                raise RuntimeError(sig)
            sig = sig + ";"
        text = text[:start] + "    " + sig + "\n" + text[end:]

    # clean unused imports
    if "@Select" not in text and "@Delete" not in text:
        text = re.sub(r"^import org\.apache\.ibatis\.annotations\.Select;\n", "", text, flags=re.M)
        text = re.sub(r"^import org\.apache\.ibatis\.annotations\.Delete;\n", "", text, flags=re.M)
        text = re.sub(r"^import org\.apache\.ibatis\.annotations\.Insert;\n", "", text, flags=re.M)
        text = re.sub(r"^import org\.apache\.ibatis\.annotations\.Update;\n", "", text, flags=re.M)
    java_path.write_text(text, encoding="utf-8")
    return len(items2)


def main() -> None:
    total = 0
    for p in sorted(MAPPER_JAVA.glob("*Mapper.java")):
        if p.name == "BaseTradeMapper.java":
            continue
        n = process(p)
        if n:
            print(f"{p.name}: moved {n} statements to XML")
            total += n
    print(f"TOTAL {total}")


if __name__ == "__main__":
    main()
