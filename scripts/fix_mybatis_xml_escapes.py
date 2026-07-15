#!/usr/bin/env python3
from pathlib import Path
import re

root = Path("services/trade-service/src/main/java/com/aicabinet/trade/mapper")
TAG_SPLIT = re.compile(
    r"(</?(?:script|foreach|if|choose|when|otherwise|trim|where|set)(?:\s[^>]*)?>)",
    re.I,
)


def escape_compares(text: str) -> str:
    """Escape SQL comparison operators that break XML (keep MyBatis tags intact)."""
    parts = TAG_SPLIT.split(text)
    out = []
    for i, part in enumerate(parts):
        if i % 2 == 1:
            out.append(part)
            continue
        # Order matters: longer ops first
        part = part.replace("<=", "&lt;=")
        part = part.replace("<>", "&lt;&gt;")
        part = part.replace(">=", "&gt;=")  # legal in XML but keep consistent
        # Remaining bare < that is not already an entity (&lt;) and not tag start handled above
        part = re.sub(r"(?<![&])<(?![=;/!?\w])", r"&lt;", part)
        part = re.sub(r"(?<![&])<(?=[\w#])", r"&lt;", part)
        out.append(part)
    return "".join(out)


def fix_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    pattern = re.compile(
        r"@((?:org\.apache\.ibatis\.annotations\.)?(?:Select|Delete|Update|Insert))"
        r"\(\s*\"\"\"(.*?)\"\"\"\s*\)",
        re.S,
    )

    def repl(m: re.Match) -> str:
        kind, body = m.group(1), m.group(2)
        return '@%s("""%s""")' % (kind, escape_compares(body))

    new = pattern.sub(repl, text)
    if new != text:
        path.write_text(new, encoding="utf-8", newline="\n")
        return True
    return False


def main() -> None:
    n = 0
    for p in sorted(root.glob("*.java")):
        if fix_file(p):
            print("fixed", p.name)
            n += 1
    print("total", n)


if __name__ == "__main__":
    main()
