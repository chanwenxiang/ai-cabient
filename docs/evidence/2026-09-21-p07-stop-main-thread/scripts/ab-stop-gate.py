#!/usr/bin/env python3
"""规则④（CabinetService 启停必须在异步块内）的 A/B 注入漂移。

为什么需要它：新判据「首跑就绿」不能说明任何事 —— 必须证明它**能红**，而且红的是**该红的地方**。
本脚本把「有没有规则④」作为**唯一自变量**，其余条件（同一份 stripComments 修复、同一批源码、
同一行尾）全部固定：

    旧臂 = 工作区门禁脚本 **删掉规则④ 整段**（等价于「上一步的门禁」）
    新臂 = 工作区门禁脚本（含规则④）

四个注入，各跑两臂：

    none                工作区真实源（正确实现）        → 两臂都必须绿（否则新判据是误报机）
    stop-sync           onDestroy 换回 HEAD 的裸同步调用 → 旧绿 / 新红
    stop-service-scope  `serviceScope.launch { stop() }` → 旧绿 / 新红（会被紧随的 cancel 静默吃掉）
    rename-stop         把 `.stop()` 改名                → 旧绿 / 新红（守卫：锚点失效不许静默变绿）

⚠️ 注入必须**命中锚点**才算数：锚点漂移（没替换成功）会让「判据绿」看起来像通过。
⚠️ `edge/**` 在 Windows 检出下是 CRLF，多行锚点必须先归一成 LF 再匹配，写回时还原 CRLF。

退出码：0 = 矩阵全部符合期望；1 = 有格子不符。
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
EDGE_MAIN_REL = Path("edge/android-app/app/src/main/java/com/aicabinet/edge")
SERVICE_REL = Path("service/CabinetService.kt")
GATE_SRC = REPO / "scripts" / "check-edge-queue-threading.mjs"
NODE = os.environ.get("NODE_BIN") or "C:/Users/cwx/.workbuddy/binaries/node/versions/22.22.2-3/node.exe"

SANDBOX = Path(tempfile.gettempdir()) / "aicabinet-stop-gate-ab"

# 工作区里「正确的」停机接线（A/B 注入的锚点）。
CORRECT_BLOCK = """        } else {
            ServiceShutdown.schedule(shutdownScope, onError = { Log.w(TAG, "stop() 失败", it) }) {
                controller.stop()
            }
        }"""


def inj_none(src: str) -> str:
    return src


def inj_stop_sync(src: str) -> str:
    """把停机改回 HEAD 的形态：主线程裸同步调用。"""
    return src.replace(CORRECT_BLOCK, "        } else {\n            controller.stop()\n        }")


def inj_stop_service_scope(src: str) -> str:
    """把停机挂到**会被 cancel 的**业务作用域上（看似异步了，其实静默不执行）。"""
    return src.replace(
        CORRECT_BLOCK,
        "        } else {\n            serviceScope.launch { controller.stop() }\n        }",
    )


def inj_rename_stop(src: str) -> str:
    """锚点失效：`.stop()` 改名后规则④ 的扫描对象消失，守卫必须拦住「静默变绿」。"""
    return src.replace("controller.stop()", "controller.stopX()")


CASES = [
    ("none", inj_none, {"old": 0, "new": 0}),
    ("stop-sync", inj_stop_sync, {"old": 0, "new": 1}),
    ("stop-service-scope", inj_stop_service_scope, {"old": 0, "new": 1}),
    ("rename-stop", inj_rename_stop, {"old": 0, "new": 1}),
]


def build_old_arm(text: str) -> str:
    """旧臂 = 去掉规则④ 整段（含其专用 helper）。"""
    m = re.search(r"// ─+ ④", text)
    if not m:
        sys.exit("RED  旧臂构造失败：门禁脚本里找不到规则④ 段落标记 `// ── ④`")
    start = m.start()
    end = text.index("const undeclared =", start)
    old = text[:start] + text[end:]
    old = old.replace("、启停异步块 ${asyncBlocks.length} 个 ✓", "")
    if "asyncBlocks" in old:
        sys.exit("RED  旧臂构造失败：仍残留对规则④ 变量的引用，删段不完整")
    return old


def reset_sources() -> tuple[Path, bool]:
    dst = SANDBOX / EDGE_MAIN_REL
    if dst.exists():
        shutil.rmtree(dst)
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(REPO / EDGE_MAIN_REL, dst)
    raw = (dst / SERVICE_REL).read_bytes()
    return dst / SERVICE_REL, b"\r\n" in raw


def apply_case(path: Path, crlf: bool, fn) -> None:
    raw = path.read_bytes()
    text = raw.decode("utf-8").replace("\r\n", "\n")
    new = fn(text)
    if new == text and fn is not inj_none:
        sys.exit(f"RED  注入未命中锚点（源文本没变）⇒ 本次实验无效，需修锚点")
    out = new.replace("\n", "\r\n") if crlf else new
    path.write_bytes(out.encode("utf-8"))


def run_gate(script: Path, log: Path) -> int:
    proc = subprocess.run(
        [NODE, str(script)],
        cwd=str(SANDBOX),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    log.write_text((proc.stdout or "") + (proc.stderr or ""), encoding="utf-8")
    return proc.returncode


def main() -> int:
    gate_text = GATE_SRC.read_text(encoding="utf-8")
    old_arm_text = build_old_arm(gate_text)

    if SANDBOX.exists():
        shutil.rmtree(SANDBOX)
    (SANDBOX / "tools").mkdir(parents=True)
    old_gate = SANDBOX / "tools" / "gate-old.mjs"
    new_gate = SANDBOX / "tools" / "gate-new.mjs"
    old_gate.write_text(old_arm_text, encoding="utf-8")
    new_gate.write_text(gate_text, encoding="utf-8")

    print(f"沙箱：{SANDBOX}")
    print(f"旧臂 = 规则①②③（删掉规则④，{len(old_arm_text)} 字节）")
    print(f"新臂 = 规则①②③④（工作区，{len(gate_text)} 字节）")
    print()

    rows = []
    ok = True
    for label, fn, expect in CASES:
        service, crlf = reset_sources()
        apply_case(service, crlf, fn)
        old_rc = run_gate(old_gate, SANDBOX / f"gate-old-{label}.log")
        new_rc = run_gate(new_gate, SANDBOX / f"gate-new-{label}.log")
        good = old_rc == expect["old"] and new_rc == expect["new"]
        ok = ok and good
        rows.append((label, crlf, old_rc, new_rc, expect, good))

    print(f"{'注入':<20}{'源行尾':<9}{'旧臂':<7}{'新臂':<7}{'期望(旧/新)':<14}{'判定'}")
    for label, crlf, old_rc, new_rc, expect, good in rows:
        eol = "CRLF" if crlf else "LF"
        print(
            f"{label:<20}{eol:<9}{old_rc:<7}{new_rc:<7}"
            f"{expect['old']}/{expect['new']:<12}{'OK' if good else 'MISMATCH'}"
        )

    if not ok:
        print("\nRED  矩阵存在不符合期望的格子：先看 gate-*-*.log，别改期望值去迁就结果")
        return 1

    print("\nOK  4 注入 × 2 臂全部符合期望：规则④ 能红，且红在它该红的地方")
    print("    关键三格（stop-sync / stop-service-scope / rename-stop）都是「旧绿新红」")
    print("    ⇒ 规则④ 是**真增量**，不是把已有的判别力换个写法。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
