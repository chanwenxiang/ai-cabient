#!/usr/bin/env python3
"""把 PowerShell 重定向产物规范化为 UTF-8/LF，**正文逐字保留、只动编码**。

为什么需要它
------------
本机 PowerShell 工具不回报 stdout，命令输出一律 `Out-File` / `*>` 落盘再读。而 Windows
PowerShell 5.1 的重定向有两种形态，读日志时都会翻车：

  * `*> file`（原生重定向）：正文是 **UTF-16LE**，按 UTF-8 读出来是 `= >   0 .` 这种
    字符间夹空格的乱码；若同一命令里还有 `Add-Content` 追加的 ASCII 标签
    （如 `EXIT=0`），文件就成了**混杂编码**：UTF-16LE 正文 + ANSI 尾部。
    整体按 `Encoding::Unicode` 转会把尾部解成乱码（`EXIT=0` 的字节对被当码位）。
  * `| Out-File -Encoding utf8`：正常 UTF-8（另需注意 PS 5.1 会带 BOM）。

**证据文件不可读 ≈ 证据不存在**，所以归档前必须规范化；且规范化只允许改编码，
不允许改内容 —— 本脚本因此按**字节序列**判断分段，不做任何文本改写。

用法
----
    python scripts/devops/normalize-ps-redirect-encoding.py <输入> [输出]
    未给输出时打印到 stdout。

分段规则
--------
0. 先切出尾部的**单字节 ANSI 段**（`CHILD_EXIT=` / `EXITCODE=` / `EXIT=`）：`*>` 重定向之后
   再用 `Add-Content` 追加的标签是单字节的，混在 UTF-16LE 正文里会出现「一行两种编码」。
   判据是「标签后一个字节是否为 0x00」——UTF-16LE 的 ASCII 字符高位恒为 0。
1. 剩下的主体：有 UTF-16 BOM（FF FE / FE FF）→ 整段按对应端序解码。
2. 否则找**第一个 NUL 字节**：它左边的单字节段按 UTF-8 解、从右边那个字符起按 UTF-16LE 解。
   找不到 NUL 就整段按 UTF-8 解。
3. 统一 `\\r\\n` → `\\n`，去掉 BOM 残留，末尾补一个换行。
   交界处被误读成伪码位 U+0A0D 的 `\\r\\n` 还原为换行；其余 U+FFFD 保留（编码损失要可见）。
"""

from __future__ import annotations

import sys
from pathlib import Path


def split_ansi_tail(data: bytes) -> tuple[bytes, bytes]:
    """切出尾部的**单字节 ANSI 段**（`*> file` 之后再 `Add-Content` 追加的标签）。

    判据：从后往前找 `CHILD_EXIT=` / `EXITCODE=` / `EXIT=` 的字面量；若它**下一个字节不是 0x00**
    就说明是 ANSI（UTF-16LE 里 ASCII 字符的高位字节恒为 0）。返回 (主体, 尾部 ANSI 段)。
    """
    for tag in (b"CHILD_EXIT=", b"EXITCODE=", b"EXIT="):
        idx = data.rfind(tag)
        if idx < 0:
            continue
        nxt = data[idx + 1 : idx + 2]
        if nxt == b"\x00":  # 是 UTF-16LE 形态，不切
            continue
        head, tail = data[:idx], data[idx:]
        # 把紧邻的 ANSI 换行也归入尾部，否则它会被当成一个 UTF-16LE 码位
        if head.endswith(b"\r\n"):
            head, tail = head[:-2], head[-2:] + tail
        elif head.endswith(b"\n") or head.endswith(b"\r"):
            head, tail = head[:-1], head[-1:] + tail
        return head, tail
    return data, b""


def normalize(data: bytes) -> str:
    head, ansi_tail = split_ansi_tail(data)
    if head.startswith(b"\xff\xfe") or head.startswith(b"\xfe\xff"):
        text = head.decode("utf-16")
    else:
        nul = head.find(b"\x00")
        if nul < 0:
            text = head.decode("utf-8", errors="replace")
        else:
            # 正文起点：第一个 NUL 前一个字节（UTF-16LE 的 ASCII 低位字节）
            start = max(0, nul - 1)
            prefix = head[:start].decode("utf-8", errors="replace")
            body = head[start:].decode("utf-16-le", errors="replace")
            # `\r\n` 若恰好处在两段交界，会被误读成伪码位 U+0A0D —— 这是机械产物，还原为换行。
            # ⚠️ 只清理这一个已知产物；其余替换字符（U+FFFD）一律保留，让编码损失**可见**。
            body = body.replace("\u0a0d", "\n").rstrip("\ufffd")
            text = prefix + body
    if ansi_tail:
        text = text.rstrip("\n") + "\n" + ansi_tail.decode("ascii", errors="replace")
    text = text.replace("\ufeff", "").replace("\r\n", "\n").replace("\r", "\n")
    if not text.endswith("\n"):
        text += "\n"
    return text


def main(argv: list[str]) -> int:
    if len(argv) < 2 or argv[1] in ("-h", "--help"):
        print(__doc__)
        return 0 if len(argv) >= 2 else 2
    src = Path(argv[1])
    if not src.exists():
        print(f"输入不存在：{src}", file=sys.stderr)
        return 1
    text = normalize(src.read_bytes())
    if len(argv) >= 3:
        dst = Path(argv[2])
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(text.encode("utf-8"))
        print(f"已写入 {dst}（{len(text)} 字符，UTF-8/LF）")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
