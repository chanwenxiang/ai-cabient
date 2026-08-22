from pathlib import Path

root = Path(r"c:\Users\cwx\OneDrive\Desktop\demo\ai-cabinet\clients\admin-vue\src")
EM = "\u2014"
repls = [
    (f"{EM} 加载中", "加载中"),
    (f"({EM})", "(…)"),
    (f"'{EM} '", "'· '"),
]
changed = 0
for p in list(root.rglob("*.vue")) + list(root.rglob("*.ts")):
    if p.name in {"display.ts"}:
        continue
    t = p.read_text(encoding="utf-8")
    n = t
    for a, b in repls:
        n = n.replace(a, b)
    if n != t:
        p.write_text(n, encoding="utf-8", newline="\n")
        changed += 1
        print("fixed", p.relative_to(root))
print("changed", changed)
left = 0
for p in list(root.rglob("*.vue")) + list(root.rglob("*.ts")):
    t = p.read_text(encoding="utf-8")
    c = t.count(EM)
    if c and p.name != "display.ts":
        # ignore comment-only
        code_lines = [ln for ln in t.splitlines() if EM in ln and not ln.strip().startswith("//") and "/*" not in ln]
        if code_lines:
            left += len(code_lines)
            for ln in code_lines[:3]:
                print("left", p.relative_to(root), ln.strip()[:100])
print("code_lines_left", left)
