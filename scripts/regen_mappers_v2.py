#!/usr/bin/env python3
"""Regenerate MyBatis-Plus *Mapper.java from Spring Data JpaRepository sources.

Reads from scripts/_repo_backup/*.java (fallback: trade.repository/).
Does not overwrite BaseTradeMapper.java.
"""
from __future__ import annotations

import re
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BACKUP_DIR = ROOT / "scripts/_repo_backup"
REPO_DIR = ROOT / "services/trade-service/src/main/java/com/aicabinet/trade/repository"
MAPPER_DIR = ROOT / "services/trade-service/src/main/java/com/aicabinet/trade/mapper"
DOMAIN_DIR = ROOT / "services/trade-service/src/main/java/com/aicabinet/trade/domain"
ENUMS_DIR = ROOT / "services/common/common-core/src/main/java/com/aicabinet/common/enums"

SKIP_OVERWRITE = {"BaseTradeMapper.java"}

# Known boolean property names (heuristic + entity scan)
BOOLEAN_PROPS: set[str] = {
    "verified",
    "payscoreEnabled",
    "visionEnabled",
    "applied",
    "matched",
    "mandatory",
    "enabled",
    "allowMerchantPlanogramEdit",
    "allowMerchantPricingEdit",
    "inventoryDeducted",
    "picked",
}

OPERATOR_SUFFIXES = [
    ("GreaterThanEqual", "ge"),
    ("LessThanEqual", "le"),
    ("GreaterThan", "gt"),
    ("LessThan", "lt"),
    ("Containing", "like"),
    ("IgnoreCase", "eqIgnoreCase"),
    ("IsNotNull", "isNotNull"),
    ("IsNull", "isNull"),
    ("NotIn", "notIn"),
    ("In", "in"),
    ("Not", "ne"),
    ("True", "eqTrue"),
    ("False", "eqFalse"),
    ("Between", "between"),
    ("After", "gt"),
    ("Before", "lt"),
    ("StartingWith", "likeRight"),
    ("EndingWith", "likeLeft"),
    ("Like", "like"),
]


def camel_to_snake(name: str) -> str:
    s1 = re.sub(r"(.)([A-Z][a-z]+)", r"\1_\2", name)
    return re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", s1).lower()


def entity_table_guess(entity: str) -> str:
    return camel_to_snake(entity)


def scan_boolean_props(entity: str) -> None:
    path = DOMAIN_DIR / f"{entity}.java"
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8")
    for m in re.finditer(r"\b(?:private|protected)\s+boolean\s+(\w+)\b", text):
        BOOLEAN_PROPS.add(m.group(1))


def flatten_embedded_prop(prop: str) -> str:
    """IdDeviceId → DeviceId only for embedded-id prefix Id+Upper; keep OrderId intact."""
    # Only strip Id when it is an embedded-id path: starts with Id AND rest looks like a property
    # Spring Data: findByIdDeviceId → Id + DeviceId. Do NOT strip OrderId / UserId alone.
    if prop.startswith("Id") and len(prop) > 2 and prop[2].isupper():
        # IdUserId → UserId; but OrderId starts with Or not Id
        prop = prop[2:]
    if not prop:
        return prop
    return prop[0].lower() + prop[1:]


def prop_to_ref(entity: str, prop: str) -> str:
    """Map property name to method reference Entity::getX or Entity::isX."""
    # Prefer isX only for primitive boolean fields
    if prop in BOOLEAN_PROPS:
        path = DOMAIN_DIR / f"{entity}.java"
        use_is = True
        if path.exists():
            text = path.read_text(encoding="utf-8")
            # Boolean wrapper → getVerified; primitive boolean → isVerified
            if re.search(rf"\b(?:private|protected)\s+Boolean\s+{re.escape(prop)}\b", text):
                use_is = False
            elif not re.search(rf"\b(?:private|protected)\s+boolean\s+{re.escape(prop)}\b", text):
                use_is = prop in BOOLEAN_PROPS and bool(
                    re.search(rf"\bis{prop[0].upper()}{prop[1:]}\s*\(", text)
                )
        if use_is:
            return f"{entity}::is{prop[0].upper()}{prop[1:]}"
    return f"{entity}::get{prop[0].upper()}{prop[1:]}"


def strip_method_prefix(method_name: str) -> tuple[str, str, int | None]:
    """Return (kind, remainder_after_By_or_empty, top_n).

    kind: find|count|exists|delete
    top_n: int for TopN / First, None otherwise; First → 1; Top without N → 1
    """
    name = method_name
    kind = "find"
    top_n: int | None = None

    if name.startswith("count"):
        kind = "count"
        name = name[5:]
    elif name.startswith("exists"):
        kind = "exists"
        name = name[6:]
    elif name.startswith("delete"):
        kind = "delete"
        name = name[6:]
    elif name.startswith("find"):
        name = name[4:]
    elif name.startswith("read"):
        name = name[4:]
    elif name.startswith("query"):
        name = name[5:]
    elif name.startswith("get"):
        name = name[3:]
    else:
        return kind, method_name, None

    # Optional Distinct
    if name.startswith("Distinct"):
        name = name[8:]

    # First / TopN before By or OrderBy
    m = re.match(r"(?:First(\d*)|Top(\d*))(.*)$", name)
    if m:
        n_str = m.group(1) if m.group(1) is not None and name.startswith("First") else m.group(2)
        if name.startswith("First"):
            top_n = int(n_str) if n_str else 1
            name = m.group(3)
        else:
            top_n = int(n_str) if n_str else 1
            name = m.group(3)

    # All
    if name.startswith("All"):
        name = name[3:]

    # By ...
    if name.startswith("By"):
        name = name[2:]
    elif name.startswith("OrderBy"):
        # findAllOrderBy / findFirstOrderBy — no criteria
        pass
    else:
        # e.g. empty after findAll
        pass

    return kind, name, top_n


def parse_order_by(rest: str, entity: str) -> tuple[str, str]:
    """Split criteria and OrderBy; return (criteria_segment, order_chain)."""
    m = re.search(r"OrderBy(.+)$", rest)
    if not m:
        return rest, ""
    criteria = rest[: m.start()]
    order_rest = m.group(1)
    parts = re.findall(r"([A-Z][a-zA-Z0-9]*?)(Asc|Desc)", order_rest)
    if not parts:
        # bare property → Asc
        prop = flatten_embedded_prop(order_rest)
        return criteria, f".orderByAsc({prop_to_ref(entity, prop)})"
    chains = []
    for prop_raw, direction in parts:
        prop = flatten_embedded_prop(prop_raw)
        ref = prop_to_ref(entity, prop)
        if direction == "Desc":
            chains.append(f".orderByDesc({ref})")
        else:
            chains.append(f".orderByAsc({ref})")
    return criteria, "".join(chains)


def split_and_or(segment: str) -> list[tuple[str | None, str]]:
    """Split by And/Or only at property boundaries (not inside OrderId / PurchaseOrderId)."""
    if not segment:
        return []
    tokens = re.split(r"(?<=[a-z0-9])(And|Or)(?=[A-Z])", segment)
    result: list[tuple[str | None, str]] = []
    logic: str | None = None
    for tok in tokens:
        if tok in ("And", "Or"):
            logic = tok
            continue
        if not tok:
            continue
        result.append((logic, tok))
        logic = None
    return result


def parse_prop_op(tok: str) -> tuple[str, str]:
    """Return (propertyCamel, op)."""
    prop = tok
    op = "eq"
    # longest suffix first already in OPERATOR_SUFFIXES
    for suf, mapped in OPERATOR_SUFFIXES:
        if prop.endswith(suf) and len(prop) > len(suf):
            prop = prop[: -len(suf)]
            op = mapped
            break
    prop = flatten_embedded_prop(prop)
    return prop, op


def build_wrapper(entity: str, method_name: str, param_names: list[str]) -> tuple[str, int | None]:
    """Build Wrappers.<E>lambdaQuery()... chain and optional top_n."""
    kind, rest, top_n = strip_method_prefix(method_name)
    criteria, order_chain = parse_order_by(rest, entity)

    code = [f"Wrappers.<{entity}>lambdaQuery()"]
    pi = 0

    if criteria:
        for logic, tok in split_and_or(criteria):
            # Or is rare; MP chain uses .or() then condition — approximate with and only warning
            if logic == "Or":
                code.append(".or()")
            prop, op = parse_prop_op(tok)
            if not prop and op not in ("eqTrue", "eqFalse"):
                continue
            ref = prop_to_ref(entity, prop) if prop else None

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
            if op == "between":
                if pi + 1 >= len(param_names):
                    break
                a, b = param_names[pi], param_names[pi + 1]
                pi += 2
                code.append(f".between({ref}, {a}, {b})")
                continue
            if pi >= len(param_names):
                break
            pname = param_names[pi]
            pi += 1
            ops_map = {
                "eq": f".eq({ref}, {pname})",
                "ne": f".ne({ref}, {pname})",
                "gt": f".gt({ref}, {pname})",
                "ge": f".ge({ref}, {pname})",
                "lt": f".lt({ref}, {pname})",
                "le": f".le({ref}, {pname})",
                "like": f".like({ref}, {pname})",
                "likeRight": f".likeRight({ref}, {pname})",
                "likeLeft": f".likeLeft({ref}, {pname})",
                "in": f".in({ref}, {pname})",
                "notIn": f".notIn({ref}, {pname})",
                "eqIgnoreCase": f".eq({ref}, {pname})",  # approx
            }
            code.append(ops_map.get(op, f".eq({ref}, {pname})"))

    code.append(order_chain)
    if top_n is not None:
        code.append(f'.last("LIMIT {top_n}")')
    return "".join(code), top_n


def parse_params(params_str: str) -> list[tuple[str, str]]:
    """Return [(type, name), ...] excluding Pageable."""
    inner = params_str.strip()
    if not inner:
        return []
    parts: list[str] = []
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
        if not p:
            continue
        tokens = p.split()
        if len(tokens) < 2:
            continue
        name = tokens[-1]
        typ = " ".join(tokens[:-1])
        if "Pageable" in typ or name == "pageable":
            continue
        result.append((typ, name))
    return result


def extract_query_sql(anns: str) -> tuple[str | None, bool]:
    """Extract SQL/JPQL string and whether nativeQuery=true."""
    if "Query" not in anns and "@Query" not in anns:
        return None, False
    # Match @Query or @org.springframework...Query
    if not re.search(r"@(?:[\w.]+\.)?Query\b", anns):
        return None, False
    native = bool(re.search(r"nativeQuery\s*=\s*true", anns))
    # text block
    m = re.search(r'@(?:[\w.]+\.)?Query\s*\(\s*(?:value\s*=\s*)?"""(.*?)"""', anns, re.S)
    if m:
        return m.group(1).strip(), native
    # Find Query( ... ) with balanced parens
    qm = re.search(r"@(?:[\w.]+\.)?Query\s*\(", anns)
    if not qm:
        return None, native
    start = qm.end() - 1  # at '('
    depth = 0
    i = start
    while i < len(anns):
        if anns.startswith('"""', i):
            i += 3
            while i < len(anns) and not anns.startswith('"""', i):
                i += 1
            i = min(i + 3, len(anns))
            continue
        ch = anns[i]
        if ch == '"':
            i += 1
            while i < len(anns) and anns[i] != '"':
                i += 2 if anns[i] == "\\" else 1
            i += 1
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                inner = anns[start + 1 : i]
                break
        i += 1
    else:
        return None, native

    strings = re.findall(r'"((?:[^"\\]|\\.)*)"', inner)
    sql_parts = []
    for s in strings:
        if s in ("true", "false"):
            continue
        if re.search(r"\b(SELECT|select|DELETE|delete|UPDATE|update|FROM|from)\b", s) or sql_parts:
            sql_parts.append(s)
    if sql_parts:
        return "".join(sql_parts).strip(), native
    return None, native


def wrap_in_foreach(sql: str, collection_params: list[str]) -> str:
    """Replace `IN #{coll}` with foreach script for Collection params."""
    out = sql
    out = re.sub(
        r"\bIN\s+#\{(\w+)\}",
        lambda m: (
            f'IN <foreach collection="{m.group(1)}" item="__item" open="(" separator="," close=")">'
            f"#{{__item}}</foreach>"
        ),
        out,
        flags=re.I,
    )
    if "<foreach" in out and "<script>" not in out:
        out = f"<script>\n{out}\n</script>"
    return out


def format_select_ann(sql: str) -> str:
    """Always emit Java text-block @Select so newlines/quotes are legal."""
    sql = sql.strip("\n")
    lines = sql.splitlines() or [sql]
    body = "\n".join("            " + ln.rstrip() for ln in lines)
    return f'@Select("""\n{body}\n            """)'


def extract_methods(text: str) -> list[dict]:
    """Parse repository methods with nested @Query text blocks / parens."""
    body_m = re.search(r"interface\s+\w+[^{]*\{(.*)\}\s*\Z", text, re.S)
    if not body_m:
        return []
    body = body_m.group(1)
    methods: list[dict] = []
    i = 0
    n = len(body)

    def skip_ws(j: int) -> int:
        while j < n and body[j] in " \t\r\n":
            j += 1
        return j

    while i < n:
        i = skip_ws(i)
        if i >= n:
            break
        # Collect leading annotations
        anns_start = i
        while i < n and body[i] == "@":
            # annotation name
            i += 1
            while i < n and (body[i].isalnum() or body[i] in "._"):
                i += 1
            i = skip_ws(i)
            if i < n and body[i] == "(":
                # scan balanced args respecting strings/text blocks
                depth = 0
                while i < n:
                    if body.startswith('"""', i):
                        i += 3
                        while i < n and not body.startswith('"""', i):
                            i += 1
                        i = min(i + 3, n)
                        continue
                    ch = body[i]
                    if ch == '"':
                        i += 1
                        while i < n and body[i] != '"':
                            if body[i] == "\\":
                                i += 2
                            else:
                                i += 1
                        i += 1
                        continue
                    if ch == "'":
                        i += 1
                        while i < n and body[i] != "'":
                            if body[i] == "\\":
                                i += 2
                            else:
                                i += 1
                        i += 1
                        continue
                    if ch == "(":
                        depth += 1
                    elif ch == ")":
                        depth -= 1
                        i += 1
                        if depth == 0:
                            break
                        continue
                    i += 1
            i = skip_ws(i)
        anns = body[anns_start:i]

        # Method signature: type name(params);
        sig_start = i
        # must look like a type
        if i >= n or not (body[i].isalpha() or body[i] in "<@"):
            # skip stray char
            if anns.strip():
                # annotation without method — skip
                pass
            i = max(i + 1, sig_start + 1) if i < n else n
            # try find next @ or identifier at line start
            while i < n and body[i] not in "@abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ":
                i += 1
            continue

        # find matching paren for params then ;
        # Scan until we find '(' at top of signature, then balance, then ;
        depth_angle = 0
        found_paren = False
        paren_depth = 0
        while i < n:
            if body.startswith('"""', i):
                i += 3
                while i < n and not body.startswith('"""', i):
                    i += 1
                i = min(i + 3, n)
                continue
            ch = body[i]
            if ch == '"':
                i += 1
                while i < n and body[i] != '"':
                    i += 2 if body[i] == "\\" else 1
                i += 1
                continue
            if not found_paren:
                if ch == "<":
                    depth_angle += 1
                elif ch == ">":
                    depth_angle = max(0, depth_angle - 1)
                elif ch == "(" and depth_angle == 0:
                    found_paren = True
                    paren_depth = 1
                elif ch == ";" and depth_angle == 0:
                    break
                i += 1
                continue
            # inside params
            if ch == "(":
                paren_depth += 1
            elif ch == ")":
                paren_depth -= 1
                i += 1
                if paren_depth == 0:
                    i = skip_ws(i)
                    if i < n and body[i] == ";":
                        i += 1
                        sig = body[sig_start : i - 1].strip()
                        sig = re.sub(r"\s+", " ", sig)
                        # validate looks like method
                        if re.search(r"\w+\s*\(.*\)\s*$", sig):
                            methods.append({"anns": anns, "sig": sig})
                    break
                continue
            i += 1
        else:
            break
    return methods


def jpql_to_sql(jpql: str, entity: str) -> str:
    sql = jpql.strip()
    table = entity_table_guess(entity)

    # COUNT(x) FROM Entity → COUNT(*) FROM table (drop alias temporarily handled below)
    sql = re.sub(
        r"SELECT\s+COUNT\s*\(\s*\w+\s*\)\s+FROM\s+(\w+)(?:\s+(\w+))?",
        lambda m: f"SELECT COUNT(*) FROM {entity_table_guess(m.group(1))}"
        + (f" {m.group(2)}" if m.group(2) else ""),
        sql,
        flags=re.I,
    )

    # JOIN alias.order o (JPA association) → JOIN cabinet_order o ON alias.order_id = o.order_id
    sql = re.sub(
        r"\bJOIN\s+(\w+)\.order\s+(\w+)\b",
        r"JOIN cabinet_order \2 ON \1.order_id = \2.order_id",
        sql,
        flags=re.I,
    )

    # FROM/JOIN EntityName alias
    def ent_table(m: re.Match) -> str:
        kw, ent, alias = m.group(1), m.group(2), m.group(3)
        return f"{kw} {entity_table_guess(ent)} {alias}"

    sql = re.sub(r"\b(FROM|JOIN)\s+([A-Z]\w+)\s+(\w+)\b", ent_table, sql)

    # SELECT alias FROM table alias → SELECT * / alias.*
    sql = re.sub(
        rf"SELECT\s+(\w+)\s+FROM\s+{table}\s+\1\b",
        rf"SELECT \1.* FROM {table} \1",
        sql,
        flags=re.I,
    )
    sql = re.sub(
        r"SELECT\s+(\w+)\s+FROM\s+(\w+)\s+\1\b",
        r"SELECT \1.* FROM \2 \1",
        sql,
        flags=re.I,
    )

    # alias.camelProp → alias.snake_prop (preserve alias); id.x → x column on embedded
    sql = re.sub(
        r"\b([a-zA-Z_]\w*)\.([a-zA-Z]\w*)",
        lambda m: (
            camel_to_snake(m.group(2))
            if m.group(2) and m.group(1) == "id"
            else f"{m.group(1)}.{camel_to_snake(m.group(2))}"
            if m.group(1) != "id"
            else camel_to_snake(m.group(2))
        ),
        sql,
    )
    # Fix id.prop leftovers like m.id.merchant_id → after first pass m.id.merchant_id
    # Re-run: x.id.y → handle chained: convert \w+\.id\.(\w+) → just snake of last if embedded cols are flat
    sql = re.sub(
        r"\b([a-zA-Z_]\w*)\.id\.([a-z_]\w*)",
        lambda m: f"{m.group(1)}.{m.group(2)}",
        sql,
    )

    sql = re.sub(r":(\w+)", r"#{\1}", sql)
    return sql


def collection_param_names(params: list[tuple[str, str]]) -> list[str]:
    names = []
    for typ, name in params:
        if re.search(r"\b(Collection|List|Set|Iterable)\b", typ):
            names.append(name)
    return names


def generate_method(entity: str, method: dict, repo_imports: list[str]) -> str:
    anns = method["anns"]
    sig = method["sig"]
    mm = re.match(r"(?:public\s+)?(.+?)\s+(\w+)\s*\((.*)\)\s*$", sig)
    if not mm:
        return f"    // FAILED parse: {sig}\n"
    ret, name, params_str = mm.group(1).strip(), mm.group(2), mm.group(3)
    params = parse_params(params_str)
    param_names = [p[1] for p in params]
    has_pageable = "Pageable" in params_str
    # keep @Param on signature when present in original params_str
    full_params = params_str  # already includes annotations from compressed sig — may have lost @Param
    # restore from original if needed — sig was whitespace-normalized; @Param should remain

    sql_src, native = extract_query_sql(anns)

    # ---------- @Query methods ----------
    if sql_src is not None:
        sql = sql_src if native else jpql_to_sql(sql_src, entity)
        if native:
            sql = re.sub(r":(\w+)", r"#{\1}", sql)
        coll_names = collection_param_names(params)
        # If JPQL has IS NULL dynamic conditions + Pageable, prefer wrapper approximation
        is_dynamic = bool(re.search(r"IS\s+NULL", sql_src, re.I)) and has_pageable
        has_join = bool(re.search(r"\bJOIN\b", sql_src, re.I))

        if is_dynamic and not has_join and ret.startswith("Page"):
            # null-safe wrapper
            return _gen_dynamic_page(entity, name, params_str, params, sql_src)

        if has_pageable and ret.startswith("Page"):
            # Dynamic null-checked JOINs → approximate with wrapper on entity fields
            if is_dynamic:
                return _gen_dynamic_page(entity, name, params_str, params, sql_src)
            sql2 = wrap_in_foreach(sql, coll_names)
            raw_params = _params_without_pageable(params_str)
            call_args = ", ".join(param_names)
            page_body = textwrap.dedent(
                f"""
                default {ret} {name}({params_str}) {{
                    var all = _{name}All({call_args});
                    int start = (int) pageable.getOffset();
                    int end = Math.min(start + pageable.getPageSize(), all.size());
                    var slice = start >= all.size() ? java.util.List.<{entity}>of() : all.subList(start, end);
                    return new org.springframework.data.domain.PageImpl<>(slice, pageable, all.size());
                }}
                """
            )
            return f"    {format_select_ann(sql2)}\n    List<{entity}> _{name}All({raw_params});\n{page_body}"

        sql = wrap_in_foreach(sql, coll_names)

        # DELETE query
        if re.match(r"DELETE\b", sql_src.strip(), re.I) or re.match(r"<script>\s*DELETE", sql.strip(), re.I):
            lines = sql.strip("\n").splitlines()
            body = "\n".join("            " + ln.rstrip() for ln in lines)
            return f'    @org.apache.ibatis.annotations.Delete("""\n{body}\n            """)\n    int {name}({params_str});\n'

        if ret.startswith("Optional"):
            inner = ret[len("Optional<") : -1]
            return textwrap.dedent(
                f"""
                {format_select_ann(sql)}
                {inner} _{name}Raw({params_str});
                default {ret} {name}({params_str}) {{
                    return Optional.ofNullable(_{name}Raw({', '.join(param_names)}));
                }}
                """
            )

        return f"    {format_select_ann(sql)}\n    {ret} {name}({params_str});\n"

    # ---------- derived query methods ----------
    wrapper, top_n = build_wrapper(entity, name, param_names)

    if name.startswith("count") or strip_method_prefix(name)[0] == "count":
        return textwrap.dedent(
            f"""
            default {ret} {name}({params_str}) {{
                Long c = selectCount({wrapper});
                return c == null ? 0 : c;
            }}
            """
        )

    if name.startswith("exists"):
        return textwrap.dedent(
            f"""
            default {ret} {name}({params_str}) {{
                return selectCount({wrapper}) > 0;
            }}
            """
        )

    if name.startswith("delete"):
        if ret.strip() == "void":
            return textwrap.dedent(
                f"""
                default void {name}({params_str}) {{
                    delete({wrapper});
                }}
                """
            )
        return textwrap.dedent(
            f"""
            default {ret} {name}({params_str}) {{
                return delete({wrapper});
            }}
            """
        )

    if has_pageable and ret.startswith("Page"):
        # remove .last(LIMIT) if any for page queries
        w = re.sub(r'\.last\("LIMIT \d+"\)', "", wrapper)
        return textwrap.dedent(
            f"""
            default {ret} {name}({params_str}) {{
                var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<{entity}>(
                        pageable.getPageNumber() + 1L, pageable.getPageSize());
                var result = selectPage(mpPage, {w});
                return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
            }}
            """
        )

    if ret.startswith("Optional"):
        # Top/First already has LIMIT 1 via last
        if top_n is None and name.startswith("findFirst"):
            wrapper2 = wrapper + '.last("LIMIT 1")' if '.last("LIMIT' not in wrapper else wrapper
            wrapper = wrapper2
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

    return textwrap.dedent(
        f"""
        default {ret} {name}({params_str}) {{
            return selectOne({wrapper});
        }}
        """
    )


def _params_without_pageable(params_str: str) -> str:
    parts: list[str] = []
    depth = 0
    cur = ""
    for ch in params_str:
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
    keep = []
    for p in parts:
        if re.search(r"\bPageable\b", p):
            continue
        keep.append(p)
    return ", ".join(keep)


def _entity_has_field(entity: str, field: str) -> bool:
    path = DOMAIN_DIR / f"{entity}.java"
    if not path.exists():
        return True
    text = path.read_text(encoding="utf-8")
    return bool(re.search(rf"\b(?:private|protected)\s+[\w.<>,\s\[\]]+\s+{re.escape(field)}\b", text))


def _gen_dynamic_page(entity: str, name: str, params_str: str, params: list[tuple[str, str]], jpql: str) -> str:
    """Generate null-safe Page method from JPQL null patterns."""
    # Extract ORDER BY field
    order_prop = "createdAt"
    om = re.search(r"ORDER\s+BY\s+\w+\.(\w+)", jpql, re.I)
    if om:
        order_prop = om.group(1)

    lines = [
        f"    default Page<{entity}> {name}({params_str}) {{",
        f"        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<{entity}>(",
        "                pageable.getPageNumber() + 1L, pageable.getPageSize());",
        f"        var q = Wrappers.<{entity}>lambdaQuery()",
    ]

    for typ, pname in params:
        if pname == "phone":
            lines.append(
                f'                .like({pname} != null && !{pname}.isEmpty(), {entity}::getPhoneNumber, {pname})'
            )
        elif pname == "name":
            lines.append(
                f'                .like({pname} != null && !{pname}.isEmpty(), {entity}::getName, {pname})'
            )
        elif pname == "verified":
            lines.append(f"                .eq({pname} != null, {entity}::isVerified, {pname})")
        elif pname == "minUserId":
            lines.append(f"                .ge({pname} != null, {entity}::getUserId, {pname})")
        elif pname == "maxUserId":
            lines.append(f"                .le({pname} != null, {entity}::getUserId, {pname})")
        elif pname == "status" and _entity_has_field(entity, "status"):
            lines.append(
                f'                .eq({pname} != null && !{pname}.isEmpty(), {entity}::getStatus, {pname})'
            )
        elif pname == "sessionId" and _entity_has_field(entity, "sessionId"):
            lines.append(
                f'                .eq({pname} != null && !{pname}.isEmpty(), {entity}::getSessionId, {pname})'
            )
        elif pname == "deviceId":
            if _entity_has_field(entity, "deviceId"):
                lines.append(
                    f'                .eq({pname} != null && !{pname}.isEmpty(), {entity}::getDeviceId, {pname})'
                )
            # else joined-only filter — skip for compile-safe approx
        elif "Collection" in typ or "List" in typ:
            if "device" in pname.lower() and _entity_has_field(entity, "deviceId"):
                lines.append(
                    f"                .in({pname} != null && !{pname}.isEmpty(), {entity}::getDeviceId, {pname})"
                )
            elif "merchant" in pname.lower() and _entity_has_field(entity, "merchantId"):
                lines.append(
                    f"                .in({pname} != null && !{pname}.isEmpty(), {entity}::getMerchantId, {pname})"
                )
        else:
            field = pname
            if not _entity_has_field(entity, field):
                continue
            ref = prop_to_ref(entity, field)
            if "String" in typ:
                lines.append(f'                .eq({pname} != null && !{pname}.isEmpty(), {ref}, {pname})')
            else:
                lines.append(f"                .eq({pname} != null, {ref}, {pname})")

    order_ref = prop_to_ref(entity, order_prop)
    if re.search(r"ORDER\s+BY.*\bDESC\b", jpql, re.I):
        lines.append(f"                .orderByDesc({order_ref});")
    else:
        lines.append(f"                .orderByAsc({order_ref});")
    lines.append("        var result = selectPage(mpPage, q);")
    lines.append(
        "        return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());"
    )
    lines.append("    }")
    return "\n".join(lines) + "\n"


def collect_imports(repo_text: str, entity: str, body: str) -> list[str]:
    imports: list[str] = [
        f"com.aicabinet.trade.domain.{entity}",
        "com.baomidou.mybatisplus.core.toolkit.Wrappers",
        "org.apache.ibatis.annotations.Mapper",
    ]
    # carry over non-spring-data imports from repository
    for m in re.finditer(r"import\s+([\w.]+);", repo_text):
        imp = m.group(1)
        if imp.startswith("org.springframework.data"):
            continue
        if imp.startswith("jakarta.persistence") or imp.startswith("javax.persistence"):
            continue
        if imp.endswith("JpaRepository"):
            continue
        imports.append(imp)

    if "@Select" in body:
        imports.append("org.apache.ibatis.annotations.Select")
    if "@Param" in body or "@Param" in repo_text:
        imports.append("org.apache.ibatis.annotations.Param")
    if "Optional" in body or "Optional" in repo_text:
        imports.append("java.util.Optional")
    if re.search(r"\bList<", body + repo_text):
        imports.append("java.util.List")
    if re.search(r"\bCollection<", body + repo_text):
        imports.append("java.util.Collection")
    if "Pageable" in repo_text or "Page<" in repo_text or "PageImpl" in body:
        imports.append("org.springframework.data.domain.Page")
        imports.append("org.springframework.data.domain.Pageable")
    if "Instant" in body or "Instant" in repo_text:
        imports.append("java.time.Instant")
    if "LocalDate" in body or "LocalDate" in repo_text:
        imports.append("java.time.LocalDate")

    # resolve referenced types in domain / enums
    for m in re.finditer(r"\b([A-Z][A-Za-z0-9]+)\b", body + repo_text):
        t = m.group(1)
        if t in ("Optional", "List", "Collection", "Page", "Pageable", "PageImpl", "Mapper", "Select", "Param", "Wrappers", "Long", "String", "Boolean", "Integer", "Void"):
            continue
        if (DOMAIN_DIR / f"{t}.java").exists():
            imports.append(f"com.aicabinet.trade.domain.{t}")
        elif (ENUMS_DIR / f"{t}.java").exists():
            imports.append(f"com.aicabinet.common.enums.{t}")

    # SessionState specifically
    if "SessionState" in body or "SessionState" in repo_text:
        imports.append("com.aicabinet.common.enums.SessionState")

    return sorted(set(imports))


def indent_methods(blocks: list[str]) -> str:
    out = []
    for b in blocks:
        b = b.strip("\n")
        if not b:
            continue
        # ensure each line has at least 4-space indent for interface members
        lines = b.splitlines()
        fixed = []
        for ln in lines:
            if not ln.strip():
                fixed.append("")
            elif ln.startswith("    "):
                fixed.append(ln)
            else:
                fixed.append("    " + ln)
        out.append("\n".join(fixed))
    return "\n\n".join(out)


def migrate_one(repo_path: Path) -> None:
    text = repo_path.read_text(encoding="utf-8")
    name_m = re.search(r"interface\s+(\w+Repository)", text)
    if not name_m:
        print("skip", repo_path.name)
        return
    repo_name = name_m.group(1)
    mapper_name = repo_name.replace("Repository", "Mapper")
    if f"{mapper_name}.java" in SKIP_OVERWRITE:
        print("preserve", mapper_name)
        return

    ent_m = re.search(r"JpaRepository\s*<\s*(\w+)\s*,", text)
    if not ent_m:
        print("no entity", repo_path.name)
        return
    entity = ent_m.group(1)
    scan_boolean_props(entity)

    methods = extract_methods(text)
    method_blocks = []
    repo_imports = re.findall(r"import\s+([\w.]+);", text)
    for meth in methods:
        try:
            method_blocks.append(generate_method(entity, meth, repo_imports))
        except Exception as e:
            method_blocks.append(f"    // ERROR generating {meth.get('sig')}: {e}\n")

    body = indent_methods(method_blocks)
    imports = collect_imports(text, entity, body)
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
    src_dir = BACKUP_DIR if BACKUP_DIR.exists() and any(BACKUP_DIR.glob("*Repository.java")) else REPO_DIR
    print("source", src_dir)
    for p in sorted(src_dir.glob("*Repository.java")):
        migrate_one(p)
    print("DONE")


if __name__ == "__main__":
    main()
