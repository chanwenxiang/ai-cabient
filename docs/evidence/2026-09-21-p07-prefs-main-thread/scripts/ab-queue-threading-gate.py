#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""`check-edge-queue-threading` 的 A/B：证明「剥离注释」这处修复既**治好假红**、又**没削弱判据**。

发现的缺陷（2026-09-21）：
  `stripComments()` 原实现是 `split('\\n')` + `line.replace(/\\/\\/.*$/, '')`。
  `edge/**` 在 Windows 检出下是 **CRLF**（`.gitattributes` 只约束 `clients/**`），
  于是每行以 `\\r` 结尾；`.` 不匹配 `\\r`，而 `$`（不带 `m`）只匹配**输入串尾**
  ⇒ 该正则**永不匹配** ⇒ 行注释剥离**整片空转** ⇒ 任何在 `//` 注释里提到过队列 API 名的
  文件都被当成真调用点 ⇒ **本机假红**；同一份代码在 CI（LF 检出）却是绿的。

⚠️ 所以「源的行尾」是这张矩阵的**自变量之一**：判据读源码文本时，行尾会改变结论。
   实测确认：`git archive HEAD` 取出的是 **LF** blob ⇒ 旧门禁在 LF 源上一切正常。

期望矩阵（旧臂＝`git show HEAD:…`，新臂＝工作区）：
  | # | 注入                       | 源行尾 | 旧臂 | 新臂 |
  |---|----------------------------|--------|------|------|
  | 1 | 无                         | LF     | 绿   | 绿   |
  | 2 | 无                         | CRLF   | 绿   | 绿   |
  | 3 | `//` 注释里提到 drain(     | LF     | 绿   | 绿   |
  | 4 | `//` 注释里提到 drain(     | CRLF   | 红❌ | 绿   | ← 缺陷
  | 5 | 无（工作区真实源）         | CRLF   | 红❌ | 绿   | ← 本次实际踩中的假红
  | 6 | KDoc 里提到 mutate(（对照）| CRLF   | 绿   | 绿   |
  | 7 | 真新增未声明 enqueue(      | CRLF   | 红   | 红   |
  | 8 | Scope 改 Dispatchers.Main  | CRLF   | 红   | 红   |
  | 9 | 已声明条目改名失效         | CRLF   | 红   | 红   |

只读仓库；所有注入都发生在 `%TEMP%/aicabinet-gate-ab` 沙箱里。

用法：
  python docs/evidence/2026-09-21-p07-prefs-main-thread/scripts/ab-queue-threading-gate.py
退出码：0 = 矩阵全部符合期望；1 = 有格子不符。
"""

import io
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
GATE_REL = Path("scripts/check-edge-queue-threading.mjs")
EDGE_MAIN_REL = "edge/android-app/app/src/main/java"

_localtmp = os.environ.get("LOCALAPPDATA") or os.environ.get("TEMP") or tempfile.gettempdir()
TMP = Path(str(_localtmp).replace("\\", "/"))
SANDBOX = Path(os.environ.get("GATE_AB_DIR", TMP / "Temp/aicabinet-gate-ab"))

NODE = Path(
    os.environ.get(
        "NODE_BIN", r"C:\Users\cwx\.workbuddy\binaries\node\versions\22.22.2-3\node.exe"
    )
)

REL = EDGE_MAIN_REL + "/com/aicabinet/edge"
CABINET_SERVICE = REL + "/service/CabinetService.kt"
CABINET_CONTROLLER = REL + "/service/CabinetController.kt"
DEVICE_STATUS = REL + "/status/DeviceStatusHub.kt"
OFFLINE_QUEUE = REL + "/upload/OfflineUploadQueue.kt"


# ── 注入器：入参为沙箱根，直接改沙箱里的文件（保持原行尾风格）────────────────
def _append(root: Path, rel: str, line: str) -> None:
    p = root / rel
    raw = p.read_bytes()
    eol = "\r\n" if b"\r\n" in raw else "\n"
    p.write_bytes(raw + (eol + line).encode("utf-8"))


def _insert_after_first_line(root: Path, rel: str, line: str) -> None:
    """插到**第一行之后**（即文件中段）。

    ⚠️ 别图省事 `append` 到文件尾：文件**最后一行没有尾随换行**，旧正则 `/\\/\\/.*$/`
    在那里恰好能匹配（`$` 命中输入串尾）⇒ 缺陷被掩盖，A/B 会得出错误结论。
    真实事故里注释在**中间行**（行尾带 `\\r`），必须照此复现。
    """
    p = root / rel
    raw = p.read_bytes()
    eol = b"\r\n" if b"\r\n" in raw else b"\n"
    idx = raw.index(eol) + len(eol)
    p.write_bytes(raw[:idx] + line.encode("utf-8") + eol + raw[idx:])


def inj_none(_root: Path) -> None:
    pass


def inj_comment_mention(root: Path) -> None:
    """在 `//` 注释里提到 `drain(` ⇒ CRLF 源上旧门禁误判成真调用点（假红）。"""
    _insert_after_first_line(
        root, CABINET_CONTROLLER, "// 备注：离线上传完成后会走到 outboundQueue.drain( 这一步"
    )


def inj_kdoc_mention(root: Path) -> None:
    """KDoc 里提到 `mutate(` ⇒ 对照组：`filter` 那条路一直有效，新旧都应绿。"""
    _insert_after_first_line(root, CABINET_CONTROLLER, "* 例：offlineQueue.mutate { } 用法见文档")


def inj_real_undeclared(root: Path) -> None:
    """真新增一处**未声明**的队列调用 ⇒ 新旧都必须红（判据没被削弱）。"""
    _append(
        root,
        DEVICE_STATUS,
        "fun __driftProbe(q: com.aicabinet.edge.mqtt.OutboundMqttQueue) {\n"
        '    q.enqueue("t", byteArrayOf(), 1)\n'
        "}",
    )


def inj_dispatchers_main(root: Path) -> None:
    """把喂给 CabinetController 的 scope 换成 Main ⇒ 新旧都必须红（规则②）。"""
    p = root / CABINET_SERVICE
    raw = p.read_bytes()
    if b"Dispatchers.Default" not in raw:
        raise SystemExit("RED  锚点 Dispatchers.Default 不在 CabinetService.kt")
    p.write_bytes(raw.replace(b"Dispatchers.Default", b"Dispatchers.Main", 1))


def inj_stale_declaration(root: Path) -> None:
    """让一条已声明条目失效（改名）⇒ 新旧都必须红（反向检测：清单不许腐烂）。"""
    p = root / OFFLINE_QUEUE
    raw = p.read_bytes()
    if b"store.replaceAll(remaining)" not in raw:
        raise SystemExit("RED  锚点 store.replaceAll(remaining) 不在 OfflineUploadQueue.kt")
    p.write_bytes(raw.replace(b"store.replaceAll(remaining)", b"store.replaceAllX(remaining)", 1))


# (tag, 说明, 源模式, 注入器, 旧臂期望绿, 新臂期望绿)
ROWS = [
    ("none-lf", "无注入", "head-lf", inj_none, True, True),
    ("none-crlf", "无注入", "head-crlf", inj_none, True, True),
    ("comment-lf", "`//` 注释里提到 drain(", "head-lf", inj_comment_mention, True, True),
    ("comment-crlf", "`//` 注释里提到 drain(", "head-crlf", inj_comment_mention, False, True),
    ("worktree-crlf", "无注入（工作区真实源）", "worktree", inj_none, False, True),
    ("kdoc-crlf", "KDoc 里提到 mutate(（对照）", "head-crlf", inj_kdoc_mention, True, True),
    ("real-undeclared", "真新增未声明 enqueue(", "head-crlf", inj_real_undeclared, False, False),
    ("dispatchers-main", "Scope 改 Dispatchers.Main", "head-crlf", inj_dispatchers_main, False, False),
    ("stale-declaration", "已声明条目改名失效", "head-crlf", inj_stale_declaration, False, False),
]


def build_sandbox() -> None:
    if SANDBOX.exists():
        shutil.rmtree(SANDBOX)
        if SANDBOX.exists():
            raise SystemExit("RED  无法清空沙箱 %s" % SANDBOX)
    (SANDBOX / "scripts").mkdir(parents=True)
    (SANDBOX / EDGE_MAIN_REL).mkdir(parents=True)


def _to_crlf(root: Path) -> None:
    for p in root.rglob("*.kt"):
        raw = p.read_bytes()
        p.write_bytes(raw.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n"))


def _to_lf(root: Path) -> None:
    for p in root.rglob("*.kt"):
        p.write_bytes(p.read_bytes().replace(b"\r\n", b"\n"))


def reset_sources(kind: str) -> None:
    """kind = head-lf | head-crlf | worktree。

    ⚠️ 行尾必须**显式强制**：`git archive` 在 `core.autocrlf=true` 下同样会做行尾转换，
    不能假定它输出 LF —— 而这个 A/B 的整个结论都挂在「源是 LF 还是 CRLF」上。
    """
    dst = SANDBOX / EDGE_MAIN_REL
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True)
    if kind == "worktree":
        shutil.copytree(REPO_ROOT / EDGE_MAIN_REL, dst, dirs_exist_ok=True)
        return
    blob = subprocess.run(
        ["git", "archive", "HEAD", EDGE_MAIN_REL],
        cwd=str(REPO_ROOT),
        capture_output=True,
        check=True,
    ).stdout
    with tarfile.open(fileobj=io.BytesIO(blob)) as tf:
        tf.extractall(SANDBOX, filter="data")
    (_to_crlf if kind == "head-crlf" else _to_lf)(dst)
    # 自证：抽一个文件确认行尾符合本模式
    probe = dst / "com/aicabinet/edge/service/CabinetService.kt"
    raw = probe.read_bytes()
    want_crlf = kind == "head-crlf"
    if (b"\r\n" in raw) != want_crlf:
        raise SystemExit("RED  沙箱行尾与模式不符（%s）" % kind)


def gate_source(kind: str) -> str:
    if kind == "new":
        return (REPO_ROOT / GATE_REL).read_text(encoding="utf-8")
    return subprocess.run(
        ["git", "show", "HEAD:%s" % GATE_REL.as_posix()],
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=True,
    ).stdout


def run_gate(kind: str, tag: str) -> bool:
    (SANDBOX / GATE_REL).write_text(gate_source(kind), encoding="utf-8", newline="\n")
    p = subprocess.run(
        [str(NODE), str(SANDBOX / GATE_REL).replace("\\", "/")],
        cwd=str(SANDBOX),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    (SANDBOX / ("gate-%s-%s.log" % (kind, tag))).write_text(
        (p.stdout or "") + (p.stderr or ""), encoding="utf-8"
    )
    return p.returncode == 0


def yn(g: bool) -> str:
    return "绿" if g else "红"


def main() -> int:
    if not NODE.exists():
        print("RED  找不到 node: %s" % NODE)
        return 1

    print("== 沙箱：%s ==" % SANDBOX)
    build_sandbox()

    rows = []
    ok = True
    for tag, name, src_kind, inject, old_exp, new_exp in ROWS:
        cells = {}
        for kind in ("old", "new"):
            reset_sources(src_kind)
            inject(SANDBOX)
            cells[kind] = run_gate(kind, tag)
        good = cells["new"] == new_exp and cells["old"] == old_exp
        ok = ok and good
        rows.append((tag, name, cells, old_exp, new_exp, good))
        print("\n-- %-34s 源=%-10s 期望 旧=%s / 新=%s" % (name, src_kind, yn(old_exp), yn(new_exp)))
        print("     实测 旧=%s / 新=%s ⇒ %s" % (
            yn(cells["old"]), yn(cells["new"]), "符合预期" if good else "**不符合预期**"))

    print("\n== 矩阵汇总 ==")
    print("  %-18s %-28s %-8s %-6s %-6s %-6s %-6s" % (
        "tag", "说明", "源", "旧实测", "旧期", "新实测", "新期"))
    for (tag, name, cells, old_exp, new_exp, good), row in zip(rows, ROWS):
        print("  %-18s %-28s %-8s %-6s %-6s %-6s %-6s %s" % (
            tag, name, row[2], yn(cells["old"]), yn(old_exp), yn(cells["new"]), yn(new_exp),
            "" if good else "**不符**"))
    print("\n  读法：`comment-*` 两行是同一注入、**只差源行尾** ⇒ LF 绿 / CRLF 假红，")
    print("        `worktree-crlf` 是本次真实事故；其余真漂移行两臂都必须红。")
    print("\nA/B 结论：%s" % ("全部符合预期" if ok else "存在不符合预期的格子"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
