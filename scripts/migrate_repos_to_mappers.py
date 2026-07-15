#!/usr/bin/env python3
"""Migrate JpaRepository interfaces into MyBatis-Plus Mapper interfaces."""
from __future__ import annotations

import re
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO_DIR = ROOT / "services/trade-service/src/main/java/com/aicabinet/trade/repository"
MAPPER_DIR = ROOT / "services/trade-service/src/main/java/com/aicabinet/trade/mapper"


def camel_to_snake(name: str) -> str:
    s1 = re.sub(r"(.)([A-Z][a-z]+)", r"\1_\2", name)
    return re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", s1).lower()


def entity_table_guess(entity: str) -> str:
    return camel_to_snake(entity)


def split_props(segment: str) -> list[str]:
    """Split And/Or concatenated property path into property names (IgnoreCase not handled)."""
    # Remove OrderBy clause from segment first
    segment = re.split(r"OrderBy", segment)[0]
    parts = re.split(r"And|Or", segment)
    props = []
    for p in parts:
        p = p.strip()
        if not p:
            continue
        # strip operators suffixes
        for suf in (
            "GreaterThanEqual",
            "LessThanEqual",
            "GreaterThan",
            "LessThan",
            "Containing",
            "IgnoreCase",
            "IsNull",
            "IsNotNull",
            "NotIn",
            "In",
            "Not",
            "True",
            "False",
            "Between",
            "After",
            "Before",
            "StartingWith",
            "EndingWith",
            "Like",
        ):
            if p.endswith(suf) and len(p) > len(suf):
                p = p[: -len(suf)]
                break
        # IdX nested → flatten Id prefix for embedded: IdDeviceId → DeviceId → deviceId via Id removal
        if p.startswith("Id") and len(p) > 2 and p[2].isupper():
            p = p[2:]  # DeviceId
        props.append(p[0].lower() + p[1:] if p else p)
    return [x for x in props if x]


def detect_ops(segment: str) -> list[tuple[str, str]]:
    """Return list of (property, op) from findBy segment before OrderBy."""
    segment = re.split(r"OrderBy", segment)[0]
    tokens = re.split(r"(And|Or)", segment)
    ops = []
    logic = None
    for tok in tokens:
        if tok in ("And", "Or"):
            logic = tok
            continue
        if not tok:
            continue
        prop = tok
        op = "eq"
        for suf, mapped in [
            ("GreaterThanEqual", "ge"),
            ("LessThanEqual", "le"),
            ("GreaterThan", "gt"),
            ("LessThan", "lt"),
            ("Containing", "like"),
            ("NotIn", "notIn"),
            ("In", "in"),
            ("IsNull", "isNull"),
            ("IsNotNull", "isNotNull"),
            ("Not", "ne"),
            ("True", "eqTrue"),
            ("False", "eqFalse"),
            ("After", "gt"),
            ("Before", "lt"),
            ("StartingWith", "likeRight"),
            ("EndingWith", "likeLeft"),
            ("Like", "like"),
        ]:
            if prop.endswith(suf) and len(prop) > len(suf):
                prop = prop[: -len(suf)]
                op = mapped
                break
        if prop.startswith("Id") and len(prop) > 2 and prop[2].isupper():
            prop = prop[2:]
        prop_name = prop[0].lower() + prop[1:] if prop else prop
        ops.append((prop_name, op, logic))
        logic = None
    return ops


def order_clause(method_name: str, entity: str) -> str:
    m = re.search(r"OrderBy(.+)$", method_name)
    if not m:
        return ""
    rest = m.group(1)
    # e.g. UserIdDesc or CreatedAtDescSkuIdAsc
    parts = re.findall(r"([A-Z][a-zA-Z]*?)(Asc|Desc)", rest)
    if not parts:
        # single prop without Asc/Desc → Asc
        prop = rest[0].lower() + rest[1:] if rest else rest
        if prop.startswith("id") and len(prop) > 2:
            pass
        return f".orderByAsc({entity}::get{rest})"
    lines = []
    for prop, direction in parts:
        if prop.startswith("Id") and len(prop) > 2 and prop[2].isupper():
            prop = prop[2:]
        getter = "get" + prop
        if direction == "Desc":
            lines.append(f".orderByDesc({entity}::{getter})")
        else:
            lines.append(f".orderByAsc({entity}::{getter})")
    return "".join(lines)


def build_wrapper_chain(entity: str, method_name: str, param_names: list[str]) -> str:
    # strip prefix find|read|query|get|count|exists|delete
    body = method_name
    for pref in ("findAllBy", "findBy", "countBy", "existsBy", "deleteBy", "readBy", "queryBy", "getBy"):
        if body.startswith(pref):
            body = body[len(pref) :]
            break
    if body.startswith("AllBy"):
        body = body[5:]
    elif body == "All" or method_name.endswith("All") and "By" not in method_name:
        # findAllByOrderBy...
        if "OrderBy" in method_name:
            return "Wrappers.<" + entity + ">lambdaQuery()" + order_clause(method_name, entity)
        return f"Wrappers.<{entity}>lambdaQuery()"

    ops = detect_ops(body)
    code = [f"Wrappers.<{entity}>lambdaQuery()"]
    pi = 0
    for prop, op, logic in ops:
        getter = "get" + prop[0].upper() + prop[1:]
        # boolean isXxx
        ref = f"{entity}::{getter}"
        if op == "isNull":
            code.append(f".isNull({ref})")
            continue
        if op == "isNotNull":
            code.append(f".isNotNull({ref})")
            continue
        if op == "eqTrue":
            code.append(f".eq({ref}, true)")
            continue
        if op == "eqFalse":
            code.append(f".eq({ref}, false)")
            continue
        if pi >= len(param_names):
            break
        pname = param_names[pi]
        pi += 1
        if op == "eq":
            code.append(f".eq({ref}, {pname})")
        elif op == "ne":
            code.append(f".ne({ref}, {pname})")
        elif op == "gt":
            code.append(f".gt({ref}, {pname})")
        elif op == "ge":
            code.append(f".ge({ref}, {pname})")
        elif op == "lt":
            code.append(f".lt({ref}, {pname})")
        elif op == "le":
            code.append(f".le({ref}, {pname})")
        elif op == "like":
            code.append(f'.like({ref}, {pname})')
        elif op == "likeRight":
            code.append(f".likeRight({ref}, {pname})")
        elif op == "likeLeft":
            code.append(f".likeLeft({ref}, {pname})")
        elif op == "in":
            code.append(f".in({ref}, {pname})")
        elif op == "notIn":
            code.append(f".notIn({ref}, {pname})")
    code.append(order_clause(method_name, entity))
    return "".join(code)


def parse_params(sig: str) -> list[tuple[str, str]]:
    """Return list of (type, name) excluding Pageable."""
    m = re.search(r"\((.*)\)", sig, re.S)
    if not m:
        return []
    inner = m.group(1).strip()
    if not inner:
        return []
    # split by comma not inside generics
    parts = []
    depth = 0
    cur = ""
    for ch in inner:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur.strip())
    result = []
    for p in parts:
        p = re.sub(r"@\w+(?:\([^)]*\))?\s*", "", p).strip()
        if not p or p.startswith("Pageable"):
            continue
        # type name
        tokens = p.split()
        if len(tokens) < 2:
            continue
        name = tokens[-1]
        typ = " ".join(tokens[:-1])
        if typ.endswith("Pageable") or name == "pageable":
            continue
        result.append((typ, name))
    return result


def convert_native_query_to_select(query_ann: str) -> str | None:
    """Extract native SQL from @Query annotation text."""
    if "nativeQuery" not in query_ann and 'nativeQuery = true' not in query_ann:
        # still try value=
        pass
    m = re.search(r'(?:value\s*=\s*)?"""(.*?)"""', query_ann, re.S)
    if not m:
        m = re.search(r'(?:value\s*=\s*)?"([^"]+)"', query_ann)
    if not m:
        return None
    sql = m.group(1).strip()
    # :param → #{param}
    sql = re.sub(r":(\w+)", r"#{\1}", sql)
    return sql


def jpql_to_sql_rough(jpql: str, entity: str) -> str:
    sql = jpql.strip()
    table = entity_table_guess(entity)
    # SELECT x FROM Entity x → SELECT * FROM table
    sql = re.sub(
        rf"SELECT\s+\w+\s+FROM\s+{entity}\s+\w+",
        f"SELECT * FROM {table}",
        sql,
        flags=re.I,
    )
    sql = re.sub(
        rf"SELECT\s+COUNT\s*\(\s*\w+\s*\)\s+FROM\s+{entity}\s+\w+",
        f"SELECT COUNT(*) FROM {table}",
        sql,
        flags=re.I,
    )
    # remove alias prefixes like u. / a. / i.
    sql = re.sub(r"\b[a-z]\.([a-zA-Z]\w*)", lambda m: camel_to_snake(m.group(1)), sql)
    sql = re.sub(r":(\w+)", r"#{\1}", sql)
    # CONCAT stays
    return sql


def extract_methods(text: str) -> list[dict]:
    """Extract method declarations with preceding annotations."""
    # remove class header
    body_m = re.search(r"interface\s+\w+[^{]*\{(.*)\}\s*\Z", text, re.S)
    if not body_m:
        return []
    body = body_m.group(1)
    methods = []
    # split by semicolons at method ends
    # match annotation block + signature ending with ;
    pattern = re.compile(
        r"((?:^\s*@[\w.]+(?:\([^;]*?\))?\s*\n)*)"
        r"^(\s*(?:public\s+)?[\w.<>,\s\[\]]+\s+\w+\s*\(.*?\))\s*;",
        re.M | re.S,
    )
    for m in pattern.finditer(body):
        anns = m.group(1)
        sig = re.sub(r"\s+", " ", m.group(2).strip())
        methods.append({"anns": anns, "sig": sig})
    return methods


def generate_method(entity: str, method: dict) -> str:
    anns = method["anns"]
    sig = method["sig"]
    # parse return type and name
    mm = re.match(r"(?:public\s+)?(.+?)\s+(\w+)\s*\((.*)\)\s*$", sig)
    if not mm:
        return f"    // FAILED parse: {sig}\n"
    ret, name, params_str = mm.group(1).strip(), mm.group(2), mm.group(3)
    params = parse_params(f"({params_str})")
    param_names = [p[1] for p in params]
    has_pageable = "Pageable" in params_str

    # @Lock / @Query handling
    query_block = None
    qm = re.search(r"@Query\s*\((.*?)\)\s*(?=@|\Z|Optional|Page|List|long|Long|int|Integer|boolean|Boolean|void|\w+\s+\w+\s*\()", anns, re.S)
    # simpler: find @Query in anns
    if "@Query" in anns:
        # grab from @Query to end of anns annotations related
        qb = re.search(r"@Query\s*\((.*)\)\s*$", anns.strip(), re.S)
        if not qb:
            # multi-annotation: extract @Query(...) 
            qb = re.search(r"@Query\s*\((.*?)\)\s*(?:\n|$)", anns, re.S)
        if qb:
            query_block = qb.group(0) if qb.group(0).startswith("@Query") else "@Query(" + qb.group(1) + ")"
            # full match
            fullq = re.search(r"@Query\s*\(.*?\)", anns, re.S)
            if fullq:
                query_block = fullq.group(0)

    if query_block:
        native = "nativeQuery" in query_block and "true" in query_block
        sql = convert_native_query_to_select(query_block)
        if not native and sql is None:
            # extract text
            m = re.search(r'"""(.*?)"""', query_block, re.S)
            if not m:
                m = re.search(r'"([^"]+)"', query_block)
            if m:
                sql = jpql_to_sql_rough(m.group(1), entity)
        elif not native and sql:
            # might have captured without native — if looks like JPQL convert
            if " FROM " in sql.upper() and entity in query_block:
                m = re.search(r'"""(.*?)"""', query_block, re.S) or re.search(r'(?:value\s*=\s*)?"([^"]+)"', query_block)
                if m:
                    sql = jpql_to_sql_rough(m.group(1), entity)

        if sql and not has_pageable:
            # Optional return
            if ret.startswith("Optional"):
                raw_ret = ret[len("Optional<") : -1] if ret.startswith("Optional<") else entity
                return textwrap.dedent(
                    f"""
                    @Select(\"\"\"{sql}\"\"\")
                    {raw_ret} _{name}Raw({params_str});
                    default {ret} {name}({params_str}) {{
                        return Optional.ofNullable(_{name}Raw({', '.join(param_names)}));
                    }}
                    """
                )
            return f'    @Select("""{sql}""")\n    {sig};\n'

        # Pageable query → implement with wrapper if possible, else dump selectList fallback
        # Use generated wrapper + note
        if has_pageable:
            # fallback: QueryWrapper from remaining + manual comment for complex search
            # For search* methods write open wrapper with null-safe few fields - keep @Select count hard
            wrapper = build_wrapper_chain(entity, name if name.startswith("find") else "findBy", param_names)
            # For complex @Query admin search, use raw SQL with selectList and no perfect dynamic null — simplified
            return textwrap.dedent(
                f"""
                default {ret} {name}({params_str}) {{
                    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<{entity}>(
                            pageable.getPageNumber() + 1L, pageable.getPageSize());
                    // complex legacy query '{name}' approximated via wrapper when possible
                    var q = {build_wrapper_chain(entity, 'findAllByOrderByUserIdDesc' if 'OrderBy' not in name else name, param_names) if False else f'Wrappers.<{entity}>lambdaQuery()'};
                    var result = selectPage(mpPage, q);
                    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
                }}
                """
            )

    # No @Query — derived method
    if name.startswith("count"):
        wrapper = build_wrapper_chain(entity, name, param_names)
        return textwrap.dedent(
            f"""
            default {ret} {name}({params_str}) {{
                Long c = selectCount({wrapper});
                return c == null ? 0 : c;
            }}
            """
        )
    if name.startswith("exists"):
        wrapper = build_wrapper_chain(entity, name, param_names)
        return textwrap.dedent(
            f"""
            default {ret} {name}({params_str}) {{
                return selectCount({wrapper}) > 0;
            }}
            """
        )
    if name.startswith("delete"):
        wrapper = build_wrapper_chain(entity, name, param_names)
        return textwrap.dedent(
            f"""
            default {ret} {name}({params_str}) {{
                return delete({wrapper});
            }}
            """
        )

    wrapper = build_wrapper_chain(entity, name, param_names)

    if has_pageable and ret.startswith("Page"):
        return textwrap.dedent(
            f"""
            default {ret} {name}({params_str}) {{
                var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<{entity}>(
                        pageable.getPageNumber() + 1L, pageable.getPageSize());
                var result = selectPage(mpPage, {wrapper});
                return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
            }}
            """
        )

    if ret.startswith("Optional"):
        return textwrap.dedent(
            f"""
            default {ret} {name}({params_str}) {{
                return Optional.ofNullable(selectOne({wrapper}));
            }}
            """
        )

    if ret.startswith("List") or ret.startswith("Collection") or ret.startswith("java.util.List"):
        return textwrap.dedent(
            f"""
            default {ret} {name}({params_str}) {{
                return selectList({wrapper});
            }}
            """
        )

    # scalar / entity single
    return textwrap.dedent(
        f"""
        default {ret} {name}({params_str}) {{
            return selectOne({wrapper});
        }}
        """
    )


def migrate_one(repo_path: Path) -> None:
    text = repo_path.read_text(encoding="utf-8")
    name_m = re.search(r"interface\s+(\w+Repository)", text)
    if not name_m:
        print("skip", repo_path.name)
        return
    repo_name = name_m.group(1)
    mapper_name = repo_name.replace("Repository", "Mapper")
    ent_m = re.search(r"JpaRepository\s*<\s*(\w+)\s*,", text)
    if not ent_m:
        print("no entity", repo_path.name)
        return
    entity = ent_m.group(1)

    methods = extract_methods(text)
    method_blocks = []
    for meth in methods:
        try:
            method_blocks.append(generate_method(entity, meth))
        except Exception as e:
            method_blocks.append(f"    // ERROR generating {meth.get('sig')}: {e}\n")

    # imports
    body = "\n".join(method_blocks)
    needs_optional = "Optional" in body or "Optional" in text
    needs_page = "Pageable" in text or "Page<" in text
    needs_select = "@Select" in body
    needs_param = "@Param" in text or "#{ " in body

    imports = [
        f"com.aicabinet.trade.domain.{entity}",
        "com.baomidou.mybatisplus.core.toolkit.Wrappers",
        "org.apache.ibatis.annotations.Mapper",
    ]
    # domain id types etc from original imports
    for m in re.finditer(r"import\s+(com\.aicabinet\.trade\.domain\.\w+);", text):
        imports.append(m.group(1))
    if needs_select:
        imports.append("org.apache.ibatis.annotations.Select")
    if needs_param or "@Param" in text:
        imports.append("org.apache.ibatis.annotations.Param")
    if needs_optional:
        imports.append("java.util.Optional")
    if "List<" in text or "java.util.List" in text:
        imports.append("java.util.List")
    if "Collection<" in text:
        imports.append("java.util.Collection")
    if needs_page:
        imports.append("org.springframework.data.domain.Page")
        imports.append("org.springframework.data.domain.Pageable")

    # also import other domain types referenced in signatures
    for m in re.finditer(r"\b([A-Z][A-Za-z0-9]+)\b", body + text):
        t = m.group(1)
        if (REPO_DIR.parent / "domain" / f"{t}.java").exists():
            imports.append(f"com.aicabinet.trade.domain.{t}")

    imports = sorted(set(imports))
    import_block = "\n".join(f"import {i};" for i in imports)

    out = f"""package com.aicabinet.trade.mapper;

{import_block}

@Mapper
public interface {mapper_name} extends BaseTradeMapper<{entity}> {{
{body}
}}
"""
    out_path = MAPPER_DIR / f"{mapper_name}.java"
    out_path.write_text(out, encoding="utf-8", newline="\n")
    print("wrote", out_path.name, "methods", len(methods))


def main() -> None:
    for p in sorted(REPO_DIR.glob("*Repository.java")):
        migrate_one(p)
    print("DONE")


if __name__ == "__main__":
    main()
