#!/usr/bin/env python3
"""Convert JPA domain entities under trade-service to MyBatis-Plus annotations."""

from __future__ import annotations

import re
import sys
from pathlib import Path

DOMAIN_DIR = (
    Path(__file__).resolve().parents[1]
    / "services"
    / "trade-service"
    / "src"
    / "main"
    / "java"
    / "com"
    / "aicabinet"
    / "trade"
    / "domain"
)

EMBEDDABLE_IDS = {
    "DeviceSkuInventoryId",
    "DeviceSkuPriceId",
    "DeviceSlotId",
    "OpsUserRoleId",
    "OpsUserMerchantId",
    "OpsRolePermissionId",
    "MerchantSubscribePrefId",
}


def camel_to_snake(name: str) -> str:
    s1 = re.sub(r"(.)([A-Z][a-z]+)", r"\1_\2", name)
    return re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", s1).lower()


def strip_jpa_imports(text: str) -> str:
    text = re.sub(r"^import jakarta\.persistence\.\*;\s*\n", "", text, flags=re.M)
    text = re.sub(r"^import jakarta\.persistence\.[A-Za-z.]+;\s*\n", "", text, flags=re.M)
    text = re.sub(r"^import org\.hibernate\.annotations\.[A-Za-z.]+;\s*\n", "", text, flags=re.M)
    text = re.sub(r"^import org\.hibernate\.type\.[A-Za-z.]+;\s*\n", "", text, flags=re.M)
    return text


def ensure_imports(text: str, imports: list[str]) -> str:
    """Insert missing imports after package declaration."""
    if not imports:
        return text
    existing = set(re.findall(r"^import\s+([\w.]+);", text, flags=re.M))
    to_add = [i for i in imports if i not in existing]
    if not to_add:
        return text
    block = "\n".join(f"import {i};" for i in to_add) + "\n"
    m = re.search(r"(package\s+[\w.]+;\s*\n)", text)
    if not m:
        return block + text
    insert_at = m.end()
    # Keep a blank line after package if present
    if text[insert_at : insert_at + 1] != "\n":
        block = "\n" + block
    else:
        # insert after the blank line following package if any
        if text[insert_at : insert_at + 1] == "\n":
            pass
    return text[:insert_at] + "\n" + block + text[insert_at:]


def remove_pre_lifecycle_methods(text: str) -> str:
    """Remove @PrePersist / @PreUpdate annotated methods entirely."""
    # Combined annotations + multi-line or single-line methods
    pattern = re.compile(
        r"(?:^[ \t]*@(?:PrePersist|PreUpdate)\s*\n)+"
        r"[ \t]*(?:public|protected|private)?\s*(?:void|[A-Za-z_][\w<>\[\].]*)\s+\w+\s*\([^)]*\)\s*"
        r"(?:\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}|;)\s*\n?",
        re.M,
    )
    prev = None
    while prev != text:
        prev = text
        text = pattern.sub("", text)

    # Inline: @PrePersist void create() { ... }
    pattern2 = re.compile(
        r"[ \t]*@(?:PrePersist|PreUpdate)\s+(?:@(?:PrePersist|PreUpdate)\s+)*"
        r"(?:public|protected|private)?\s*void\s+\w+\s*\([^)]*\)\s*\{[^{}]*\}\s*\n?",
        re.M,
    )
    text = pattern2.sub("", text)
    return text


def extract_column_name(ann: str) -> str | None:
    m = re.search(r'name\s*=\s*"([^"]+)"', ann)
    return m.group(1) if m else None


def parse_id_class_fields(id_path: Path) -> list[dict]:
    """Parse fields from an *Id embeddable class."""
    src = id_path.read_text(encoding="utf-8")
    fields = []
    # Match optional annotations then field declaration
    for m in re.finditer(
        r"((?:@[^\n]+\n\s*)*)"
        r"(?:private|protected)\s+"
        r"([\w.<>,\s\[\]]+?)\s+"
        r"(\w+)\s*(?:=\s*[^;]+)?;",
        src,
    ):
        anns = m.group(1)
        ftype = " ".join(m.group(2).split())
        fname = m.group(3)
        col = None
        for am in re.finditer(r"@Column\s*\(([^)]*)\)", anns):
            col = extract_column_name(am.group(0))
        fields.append({"type": ftype, "name": fname, "column": col})
    return fields


def convert_embeddable(path: Path, text: str) -> str:
    text = strip_jpa_imports(text)
    text = re.sub(r"^@Embeddable\s*\n", "", text, flags=re.M)
    # Remove @Column annotations on fields
    text = re.sub(r"[ \t]*@Column\s*\([^)]*\)\s*\n", "", text)
    text = re.sub(r"[ \t]*@Column\s*\n", "", text)
    # Clean duplicate blank lines after package
    text = re.sub(r"(package [\w.]+;\n)\n+", r"\1\n", text, count=1)
    return text


def build_embedded_get_set(id_type: str, fields: list[dict]) -> str:
    setters = "\n".join(
        f"            this.{f['name']} = id.get{f['name'][0].upper() + f['name'][1:]}();"
        for f in fields
    )
    # Prefer existing getters on Id (standard JavaBean)
    getter_calls = []
    for f in fields:
        prop = f["name"][0].upper() + f["name"][1:]
        getter_calls.append(f"id.get{prop}()")
    null_checks = " && ".join(f"{f['name']} != null" for f in fields)
    ctor_args = ", ".join(f["name"] for f in fields)
    return f"""
    public {id_type} getId() {{
        if (id == null && {null_checks}) {{
            id = new {id_type}({ctor_args});
        }}
        return id;
    }}
    public void setId({id_type} id) {{
        this.id = id;
        if (id != null) {{
{setters}
        }}
    }}
"""


def convert_embedded_id_entity(path: Path, text: str, class_name: str) -> str:
    id_field_m = re.search(
        r"@EmbeddedId\s*\n\s*private\s+(\w+)\s+(\w+)\s*;",
        text,
    )
    if not id_field_m:
        raise ValueError(f"{path.name}: @EmbeddedId field not found")
    id_type = id_field_m.group(1)
    id_var = id_field_m.group(2)

    id_path = path.parent / f"{id_type}.java"
    if not id_path.exists():
        raise ValueError(f"{path.name}: missing Id class {id_type}")
    fields = parse_id_class_fields(id_path)
    if not fields:
        raise ValueError(f"{path.name}: no fields parsed from {id_type}")

    has_json = bool(
        re.search(r"JdbcTypeCode|SqlTypes\.JSON|JacksonTypeHandler", text)
    )
    table_m = re.search(r'@Table\s*\(\s*name\s*=\s*"([^"]+)"\s*\)', text)
    if not table_m:
        raise ValueError(f"{path.name}: @Table not found")
    table = table_m.group(1)

    text = remove_pre_lifecycle_methods(text)
    text = strip_jpa_imports(text)

    # Replace class-level annotations
    table_ann = (
        f'@TableName(value = "{table}", autoResultMap = true)'
        if has_json
        else f'@TableName("{table}")'
    )
    text = re.sub(r"@Entity\s*\n", "", text)
    text = re.sub(r'@Table\s*\([^)]*\)\s*\n', table_ann + "\n", text)

    # Build flat field declarations
    flat_decls = []
    for f in fields:
        col = f["column"]
        expected = camel_to_snake(f["name"])
        if col and col != expected:
            flat_decls.append(f'    @TableField("{col}")\n    private {f["type"]} {f["name"]};')
        else:
            flat_decls.append(f"    private {f['type']} {f['name']};")
    flat_block = "\n\n".join(flat_decls)

    # Replace @EmbeddedId private XxxId id; with exist=false + flat fields
    replacement = (
        f"    @TableField(exist = false)\n"
        f"    private {id_type} {id_var};\n\n"
        f"{flat_block}"
    )
    text = re.sub(
        r"[ \t]*@EmbeddedId\s*\n[ \t]*private\s+\w+\s+\w+\s*;",
        replacement,
        text,
        count=1,
    )

    # Remove remaining @Column that only have attrs / keep TableField when name differs
    text = transform_columns(text, skip_field_names={f["name"] for f in fields} | {id_var})

    # Update/replace getId/setId
    get_set = build_embedded_get_set(id_type, fields)
    # Remove existing getId/setId (possibly one-liners)
    text = re.sub(
        rf"[ \t]*public\s+{re.escape(id_type)}\s+getId\s*\(\s*\)\s*\{{[^}}]*\}}\s*\n?",
        "",
        text,
    )
    text = re.sub(
        rf"[ \t]*public\s+void\s+setId\s*\(\s*{re.escape(id_type)}\s+\w+\s*\)\s*\{{[^}}]*\}}\s*\n?",
        "",
        text,
    )

    # Add getters/setters for flat fields if missing
    extra_accessors = []
    for f in fields:
        prop = f["name"][0].upper() + f["name"][1:]
        if not re.search(rf"get{prop}\s*\(", text):
            extra_accessors.append(
                f"    public {f['type']} get{prop}() {{ return {f['name']}; }}"
            )
            extra_accessors.append(
                f"    public void set{prop}({f['type']} {f['name']}) {{ this.{f['name']} = {f['name']}; }}"
            )

    # Insert getId/setId + accessors before closing brace of class
    insert = get_set
    if extra_accessors:
        insert += "\n" + "\n".join(extra_accessors) + "\n"
    text = re.sub(r"\n\}\s*\Z", "\n" + insert + "}\n", text)

    # Fix constructors that only assign this.id = new Id(...) — call setId instead
    text = re.sub(
        rf"this\.{id_var}\s*=\s*new\s+{re.escape(id_type)}\(",
        f"setId(new {id_type}(",
        text,
    )
    # Fix closing: setId(new Xxx(...);  -> setId(new Xxx(...));
    # The original was `this.id = new Xxx(...);` which becomes `setId(new Xxx(...);` — broken paren.
    # Fix by matching setId(new ... ); where the semicolon closes before the setId paren closes.
    text = re.sub(
        rf"setId\(new\s+{re.escape(id_type)}\(([^;]*)\);",
        rf"setId(new {id_type}(\1));",
        text,
    )

    imports = [
        "com.baomidou.mybatisplus.annotation.TableName",
        "com.baomidou.mybatisplus.annotation.TableField",
    ]
    text = ensure_imports(text, imports)
    text = cleanup_blank_lines(text)
    return text


def transform_columns(text: str, skip_field_names: set[str] | None = None) -> str:
    """Convert/remove @Column annotations based on field names that follow them."""
    skip_field_names = skip_field_names or set()
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        # Gather consecutive annotation / blank lines ending with a field
        if "@Column" in line or (
            line.strip().startswith("@Column")
        ):
            # Collect annotation block that includes @Column (may be multi-line @Column(...))
            block_start = i
            block = []
            while i < len(lines):
                block.append(lines[i])
                joined = "".join(block)
                # Stop when we have a complete @Column(...) and later a field decl,
                # OR we've started looking for the field after annotations.
                if re.search(r"(?:private|protected|public)\s+[\w.<>,\s\[\]]+\s+\w+\s*[;=]", joined):
                    break
                # Also break if line looks like start of method (shouldn't happen mid-ann)
                i += 1
                if i >= len(lines):
                    break
                # continue collecting annotations
                if lines[i].strip().startswith("@") or not lines[i].strip() or lines[i].strip().startswith("("):
                    continue
                if re.match(
                    r"\s*(?:private|protected|public)\s+",
                    lines[i],
                ):
                    block.append(lines[i])
                    break
                # multi-line annotation continuation
                block.append(lines[i])
            # Actually simpler approach: use regex on whole text instead
            out.extend(block)
            i += 1
            continue
        out.append(line)
        i += 1

    # Simpler whole-text approach for @Column
    return _transform_columns_regex(text, skip_field_names)


def _transform_columns_regex(text: str, skip_field_names: set[str]) -> str:
    def repl_column_block(m: re.Match) -> str:
        full = m.group(0)
        col_ann = m.group(1)
        prefix_anns = m.group(2) or ""
        field_line = m.group(3)
        fname_m = re.search(r"(?:private|protected|public)\s+[\w.<>,\s\[\]]+\s+(\w+)\s*", field_line)
        if not fname_m:
            return full
        fname = fname_m.group(1)
        if fname in skip_field_names:
            # Still remove @Column for flat fields we already handled
            return prefix_anns + field_line

        col_name = extract_column_name(col_ann)
        expected = camel_to_snake(fname)
        other_anns = prefix_anns
        # JSON / JdbcTypeCode handled separately — if this Column follows JdbcTypeCode,
        # leave name handling to that transformer. Here if JSON marker already converted:
        if "typeHandler" in other_anns or "JacksonTypeHandler" in other_anns:
            if col_name and col_name != expected:
                return f'{other_anns}    @TableField(value = "{col_name}", typeHandler = JacksonTypeHandler.class)\n{field_line}'
            # typeHandler already present on previous line
            return other_anns + field_line

        if col_name and col_name != expected:
            return f'{other_anns}    @TableField("{col_name}")\n{field_line}'
        # name matches or no name — drop @Column
        return other_anns + field_line

    # Match @Column(...) optionally preceded by other annotations on previous lines (same field)
    # We process @Column that appears immediately before the field (after other anns).
    pattern = re.compile(
        r"([ \t]*@Column\s*\([^)]*\)\s*\n)"
        r"((?:[ \t]*@[^\n]+\n)*)"
        r"([ \t]*(?:private|protected|public)\s+[^\n]+)",
        re.M,
    )
    # Also handle case where other anns come BEFORE @Column
    pattern2 = re.compile(
        r"((?:[ \t]*@[^\n]+\n)*)"
        r"([ \t]*@Column\s*\([^)]*\)\s*\n)"
        r"([ \t]*(?:private|protected|public)\s+[^\n]+)",
        re.M,
    )

    def repl2(m: re.Match) -> str:
        before = m.group(1)
        col_ann = m.group(2)
        field_line = m.group(3)
        # Don't process if before contains something we need to keep carefully —
        # exclude if before already has @TableId / @TableField
        if "@TableId" in before or "@TableField" in before or "@TableName" in before:
            return m.group(0)
        # Skip class-level? field_line must have private etc. - ok
        fname_m = re.search(
            r"(?:private|protected|public)\s+[\w.<>,\s\[\]]+\s+(\w+)\s*",
            field_line,
        )
        if not fname_m:
            return m.group(0)
        fname = fname_m.group(1)
        if fname in skip_field_names and "@EmbeddedId" not in before:
            # remove column only
            return before + field_line

        col_name = extract_column_name(col_ann)
        expected = camel_to_snake(fname)

        # If JSON already converted in before
        if "JacksonTypeHandler" in before or "typeHandler" in before:
            # strip Column; if col name needed merge into TableField — already done elsewhere
            if col_name and col_name != expected and "TableField" not in before:
                return (
                    f'{before.rstrip()}\n'
                    f'    @TableField(value = "{col_name}", typeHandler = JacksonTypeHandler.class)\n'
                    f"{field_line}"
                )
            return before + field_line

        if col_name and col_name != expected:
            return f'{before}    @TableField("{col_name}")\n{field_line}'
        return before + field_line

    text = pattern2.sub(repl2, text)
    # Simple @Column without parentheses (rare)
    text = re.sub(r"[ \t]*@Column\s*\n", "", text)
    return text


def convert_json_annotations(text: str) -> tuple[str, bool]:
    has_json = bool(re.search(r"JdbcTypeCode|SqlTypes\.JSON", text))

    pattern = re.compile(
        r"[ \t]*(?:@org\.hibernate\.annotations\.JdbcTypeCode\s*\([^)]*\)|@JdbcTypeCode\s*\([^)]*\))\s*\n"
        r"([ \t]*@Column\s*\([^)]*\)\s*\n)?"
        r"([ \t]*(?:private|protected|public)\s+[^\n]+)",
        re.M,
    )

    def repl(m: re.Match) -> str:
        col_ann = m.group(1) or ""
        field_line = m.group(2)
        fname_m = re.search(
            r"(?:private|protected|public)\s+[\w.<>,\s\[\]]+\s+(\w+)\s*",
            field_line,
        )
        col_name = extract_column_name(col_ann) if col_ann else None
        if fname_m and col_name:
            expected = camel_to_snake(fname_m.group(1))
            if col_name != expected:
                return (
                    f'    @TableField(value = "{col_name}", typeHandler = JacksonTypeHandler.class)\n'
                    f"{field_line}"
                )
        return f"    @TableField(typeHandler = JacksonTypeHandler.class)\n{field_line}"

    text2, n = pattern.subn(repl, text)
    if n:
        has_json = True
    return text2, has_json


def convert_id_annotations(text: str) -> str:
    """Convert @Id / @GeneratedValue to @TableId (supports inline same-line forms)."""

    # Same-line: @Id @GeneratedValue(...) @Column(...) private Type name;
    def repl_inline(m: re.Match) -> str:
        block = m.group(0)
        field_line = m.group(1)
        has_gen = "@GeneratedValue" in block
        col_name = extract_column_name(block)
        fname_m = re.search(
            r"(?:private|protected|public)\s+[\w.<>,\s\[\]]+\s+(\w+)\s*",
            field_line,
        )
        fname = fname_m.group(1) if fname_m else "id"
        indent = re.match(r"^(\s*)", field_line).group(1) if field_line.startswith(" ") else "    "
        # When field is on same line as @Id, extract field part
        if not field_line.strip().startswith(("private", "protected", "public")):
            fm = re.search(
                r"((?:private|protected|public)\s+[^\n]+)",
                block,
            )
            field_line = (indent + fm.group(1)) if fm else block
            fname_m = re.search(
                r"(?:private|protected|public)\s+[\w.<>,\s\[\]]+\s+(\w+)\s*",
                field_line,
            )
            fname = fname_m.group(1) if fname_m else "id"

        if has_gen:
            table_id = f"{indent}@TableId(type = IdType.AUTO)\n"
        else:
            expected = camel_to_snake(fname)
            if col_name and col_name != expected:
                table_id = f'{indent}@TableId(value = "{col_name}", type = IdType.INPUT)\n'
            else:
                table_id = f"{indent}@TableId(type = IdType.INPUT)\n"
        # Ensure field_line has indent and is just the field
        fm = re.search(r"((?:private|protected|public)\s+[^\n]+)", block)
        field_only = indent + fm.group(1) if fm else field_line
        if not field_only.endswith("\n"):
            field_only = field_only.rstrip() + "\n"
        return table_id + field_only

    text = re.sub(
        r"[ \t]*@Id\b[^\n]*(?:\n[ \t]*@(?:GeneratedValue|Column)\b[^\n]*)*\n?"
        r"[ \t]*((?:private|protected|public)\s+[^\n]+)",
        repl_inline,
        text,
    )
    # Inline single line where private is on same line as @Id
    text = re.sub(
        r"[ \t]*@Id\b(?:[ \t]+@[A-Za-z]+(?:\([^)]*\))?)*[ \t]+"
        r"((?:private|protected|public)\s+[^\n]+)",
        repl_inline,
        text,
    )
    text = re.sub(r"[ \t]*@GeneratedValue\s*\([^)]*\)\s*\n?", "", text)
    return text


def convert_associations(text: str, class_name: str) -> str:
    # @OneToMany(...) → @TableField(exist = false)
    text = re.sub(
        r"[ \t]*@OneToMany\s*\([^)]*\)\s*\n",
        "    @TableField(exist = false)\n",
        text,
    )

    if class_name == "CabinetOrderLine":
        # Replace ManyToOne + JoinColumn + CabinetOrder order with orderId
        text = re.sub(
            r"[ \t]*@ManyToOne\s*\([^)]*\)\s*\n"
            r"[ \t]*@JoinColumn\s*\([^)]*\)\s*\n"
            r"[ \t]*private\s+CabinetOrder\s+order\s*;\s*\n",
            '    @TableField("order_id")\n    private String orderId;\n',
            text,
        )
        # Remove setOrder, add getOrderId/setOrderId if missing
        text = re.sub(
            r"[ \t]*public\s+void\s+setOrder\s*\(\s*CabinetOrder\s+\w+\s*\)\s*\{[^}]*\}\s*\n?",
            "",
            text,
        )
        if "getOrderId(" not in text:
            text = re.sub(
                r"(\n\}\s*\Z)",
                "\n    public String getOrderId() { return orderId; }\n"
                "    public void setOrderId(String orderId) { this.orderId = orderId; }\n}",
                text,
            )
    else:
        # Generic ManyToOne → exist=false (shouldn't happen except CabinetOrderLine)
        text = re.sub(
            r"[ \t]*@ManyToOne\s*\([^)]*\)\s*\n"
            r"(?:[ \t]*@JoinColumn\s*\([^)]*\)\s*\n)?"
            r"([ \t]*private\s+[^\n]+)",
            r"    @TableField(exist = false)\n\1",
            text,
        )

    if class_name == "CabinetOrder":
        text = re.sub(
            r"line\.setOrder\s*\(\s*this\s*\)\s*;",
            "line.setOrderId(this.orderId);",
            text,
        )

    # Remove @Enumerated
    text = re.sub(r"[ \t]*@Enumerated\s*\([^)]*\)\s*\n", "", text)
    return text


def convert_normal_entity(path: Path, text: str, class_name: str) -> str:
    text = remove_pre_lifecycle_methods(text)
    text, has_json = convert_json_annotations(text)
    # Also detect already if jsonb in remaining
    if "JacksonTypeHandler" in text:
        has_json = True

    table_m = re.search(r'@Table\s*\(\s*name\s*=\s*"([^"]+)"\s*\)', text)
    if not table_m:
        raise ValueError(f"{path.name}: @Table(name=...) not found")
    table = table_m.group(1)
    table_ann = (
        f'@TableName(value = "{table}", autoResultMap = true)'
        if has_json
        else f'@TableName("{table}")'
    )

    text = re.sub(r"@Entity\s*\n", "", text)
    text = re.sub(r'@Table\s*\([^)]*\)\s*\n', table_ann + "\n", text)

    text = convert_associations(text, class_name)
    text = convert_id_annotations(text)
    text = _transform_columns_regex(text, set())
    text = strip_jpa_imports(text)

    # Remove leftover GeneratedValue / JoinColumn / Entity references
    text = re.sub(r"[ \t]*@GeneratedValue\s*\([^)]*\)\s*\n", "", text)
    text = re.sub(r"[ \t]*@JoinColumn\s*\([^)]*\)\s*\n", "", text)

    imports = [
        "com.baomidou.mybatisplus.annotation.TableName",
        "com.baomidou.mybatisplus.annotation.TableId",
        "com.baomidou.mybatisplus.annotation.IdType",
    ]
    if "@TableField" in text:
        imports.insert(1, "com.baomidou.mybatisplus.annotation.TableField")
    if "JacksonTypeHandler" in text:
        imports.append(
            "com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler"
        )

    text = ensure_imports(text, imports)
    text = cleanup_blank_lines(text)
    return text


def cleanup_blank_lines(text: str) -> str:
    text = re.sub(r"\n{3,}", "\n\n", text)
    # Fix imports cluster: ensure blank line between last import and class
    text = re.sub(
        r"(import [\w.]+;\n)(@(?:TableName|Embeddable))",
        r"\1\n\2",
        text,
    )
    text = re.sub(
        r"(import [\w.]+;\n)(public class)",
        r"\1\n\2",
        text,
    )
    return text


def process_file(path: Path) -> str:
    text = path.read_text(encoding="utf-8")
    class_m = re.search(r"public\s+class\s+(\w+)", text)
    if not class_m:
        return "skipped:no-class"
    class_name = class_m.group(1)

    if "@Embeddable" in text or class_name in EMBEDDABLE_IDS:
        new = convert_embeddable(path, text)
        path.write_text(new, encoding="utf-8", newline="\n")
        return "embeddable"

    if "@EmbeddedId" in text:
        new = convert_embedded_id_entity(path, text, class_name)
        path.write_text(new, encoding="utf-8", newline="\n")
        return "embedded-id"

    if "@Entity" in text:
        new = convert_normal_entity(path, text, class_name)
        path.write_text(new, encoding="utf-8", newline="\n")
        return "entity"

    return "skipped:no-jpa"


def main() -> int:
    if not DOMAIN_DIR.is_dir():
        print(f"ERROR: domain dir not found: {DOMAIN_DIR}", file=sys.stderr)
        return 1

    files = sorted(DOMAIN_DIR.glob("*.java"))
    counts: dict[str, int] = {}
    modified = 0
    problems: list[str] = []
    skipped: list[str] = []

    for path in files:
        try:
            result = process_file(path)
            counts[result] = counts.get(result, 0) + 1
            if result.startswith("skipped"):
                skipped.append(f"{path.name}: {result}")
            else:
                modified += 1
        except Exception as e:
            problems.append(f"{path.name}: {e}")
            print(f"FAIL {path.name}: {e}", file=sys.stderr)

    print(f"Domain dir: {DOMAIN_DIR}")
    print(f"Files scanned: {len(files)}")
    print(f"Files modified: {modified}")
    print(f"By kind: {counts}")
    if skipped:
        print("Skipped:")
        for s in skipped:
            print(f"  - {s}")
    if problems:
        print("Problems:")
        for p in problems:
            print(f"  - {p}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
