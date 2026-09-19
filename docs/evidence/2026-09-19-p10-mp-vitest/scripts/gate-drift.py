#!/usr/bin/env python3
"""P0-10：`check-mp-unit-tests-wired.mjs` 的注入漂移验证。

铁律：新增判据必须证明「真注入漂移 ⇒ 必红」，否则它与恒真断言无从区分。
本脚本对四条规则各注入一次真实漂移，并额外做一条反假红（只加注释），
每条用例跑完**无条件还原**并逐字校验（sha256 + 内容等价）。

用法：python docs/evidence/2026-09-19-p10-mp-vitest/scripts/gate-drift.py
"""
from __future__ import annotations

import hashlib
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
GATE = "scripts/check-mp-unit-tests-wired.mjs"
OUT = pathlib.Path(__file__).resolve().parents[1] / "gate-drift.txt"

CONSUMER_PKG = ROOT / "clients/consumer-mp/package.json"
MERCHANT_CFG = ROOT / "clients/merchant-mp/vitest.config.ts"
CI = ROOT / ".github/workflows/ci.yml"
CONSUMER_TESTS = [
    ROOT / "clients/consumer-mp/src/utils/account.test.ts",
    ROOT / "clients/consumer-mp/src/utils/dispute-form.test.ts",
    ROOT / "clients/consumer-mp/src/utils/secure-id.test.ts",
]


def sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def run_gate() -> tuple[int, str]:
    proc = subprocess.run(
        ["node", GATE], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    return proc.returncode, f"{proc.stdout or ''}{proc.stderr or ''}"


def read_text(path: pathlib.Path) -> str:
    """字节级读：绝不让 Python 的换行转换碰一下（否则会把 eol=lf 的文件写成 CRLF）。"""
    return path.read_bytes().decode("utf-8")


def write_text(path: pathlib.Path, text: str) -> None:
    path.write_bytes(text.encode("utf-8"))


def edit_text(path: pathlib.Path, old: str, new: str) -> None:
    text = read_text(path)
    assert text.count(old) == 1, f"{path.name}: 锚点出现 {text.count(old)} 次（应为 1）"
    write_text(path, text.replace(old, new))


def restore(path: pathlib.Path, original: str) -> bool:
    write_text(path, original)
    return read_text(path) == original


lines: list[str] = []


def push(s: str = "") -> None:
    lines.append(s)
    print(s)


def case(name: str, desc: str, expect_red: bool, mutate, revert) -> dict:
    push(f"[{name}] {desc}")
    push(f"      期望：{'红' if expect_red else '绿'}")
    mutate()
    code, out = run_gate()
    reverted = revert()
    # 还原后必须复绿，否则说明还原不干净，后续用例全部不可信
    code_after, _ = run_gate()
    went_red = code != 0
    match = went_red == expect_red
    detail = [
        ln.strip()
        for ln in out.splitlines()
        if ln.strip().startswith("[check-mp-unit-tests-wired]") or ln.strip().startswith("- ")
    ][:6]
    push(f"      实际 exit={code}（{'红' if went_red else '绿'}）；还原 ok={reverted}，还原后 exit={code_after}")
    for d in detail:
        push(f"        {d}")
    push(f"      VERDICT: {'✅ 符合期望' if match else '❌ 不符期望'}")
    push()
    return {
        "name": name,
        "desc": desc,
        "expect_red": expect_red,
        "exit": code,
        "restored": reverted and code_after == 0,
        "match": match,
    }


push("P0-10 门禁注入漂移验证：scripts/check-mp-unit-tests-wired.mjs")
push("")

code0, out0 = run_gate()
push("=== 基线（无漂移） ===")
push(f"exit={code0}")
for ln in out0.strip().splitlines():
    push(f"  {ln.strip()}")
push()

results: list[dict] = []

# —— 规则 1：test 脚本被换掉（最典型的「名义上有测试」） ——
orig = CONSUMER_PKG.read_text(encoding="utf-8")
results.append(
    case(
        "G1",
        "规则1：consumer-mp 的 test 脚本改成非 vitest（echo skip）",
        True,
        lambda: edit_text(CONSUMER_PKG, '"test": "vitest run",', '"test": "echo skip",'),
        lambda: restore(CONSUMER_PKG, orig),
    )
)

# —— 规则 2：测试文件全没了 ——
orig = None
moved: list[tuple[pathlib.Path, pathlib.Path]] = []
for p in CONSUMER_TESTS:
    moved.append((p, p.with_suffix(".ts.bak")))


def hide_tests() -> None:
    for p, bak in moved:
        p.rename(bak)


def show_tests() -> bool:
    for p, bak in moved:
        if bak.exists():
            bak.rename(p)
    return all(p.exists() for p, _ in moved)


results.append(case("G2", "规则2：consumer-mp 三个测试文件全部移走", True, hide_tests, show_tests))

# —— 规则 3：include 被改窄（文件在、一条都不匹配；失效形态③的隐蔽写法） ——
orig = MERCHANT_CFG.read_text(encoding="utf-8")
results.append(
    case(
        "G3",
        "规则3：merchant-mp 的 include 改成 'tests/**/*.test.ts'（文件在但不匹配）",
        True,
        lambda: edit_text(
            MERCHANT_CFG,
            "include: ['src/**/*.{test,spec}.ts'],",
            "include: ['tests/**/*.test.ts'],",
        ),
        lambda: restore(MERCHANT_CFG, orig),
    )
)

# —— 规则 4：CI 真实调用被注释掉（同时验证「剥 shell 注释」不是搜关键词） ——
orig = CI.read_text(encoding="utf-8")
results.append(
    case(
        "G4",
        "规则4：把 `pnpm --filter @aicabinet/merchant-mp test` 那行注释掉",
        True,
        lambda: edit_text(
            CI,
            "        run: pnpm --filter @aicabinet/merchant-mp test",
            "        # run: pnpm --filter @aicabinet/merchant-mp test",
        ),
        lambda: restore(CI, orig),
    )
)

# —— 反假红：真命令仍在，只在 CI 里多一行**注释**提到同样的命令串 ——
orig = CI.read_text(encoding="utf-8")
results.append(
    case(
        "G5",
        "反假红：真命令保留，仅在 CI 里新增一行注释提到该命令",
        False,
        lambda: edit_text(
            CI,
            "      - name: Merchant MP unit tests (vitest)\n",
            "      # 下面这条曾经漏接线：pnpm --filter @aicabinet/merchant-mp test\n"
            "      - name: Merchant MP unit tests (vitest)\n",
        ),
        lambda: restore(CI, orig),
    )
)

push("=== 汇总 ===")
ok = sum(1 for r in results if r["match"] and r["restored"])
push(f"{ok}/{len(results)} 条符合期望且还原干净")
for r in results:
    push(
        f"  {r['name']} {r['desc']} → exit={r['exit']} 期望红={r['expect_red']} "
        f"还原ok={r['restored']} {'✅' if r['match'] else '❌'}"
    )

OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(f"\n[written] {OUT}")

sys.exit(0 if ok == len(results) else 1)
