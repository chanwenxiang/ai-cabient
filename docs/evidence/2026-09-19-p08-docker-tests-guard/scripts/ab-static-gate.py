"""P0-8 A/B 负向验证：check-docker-tests-guarded.mjs

沙箱 = %TEMP%\\p08-ab（scripts/.github 为副本，services/edge 为 junction 指向真仓库）。
不修改真仓库的任何文件。
"""
import os
import pathlib
import shutil
import subprocess
import sys

NODE = r"C:\Users\cwx\.workbuddy\binaries\node\versions\22.22.2-3\node.exe"
REPO = pathlib.Path(r"C:\Users\cwx\OneDrive\Desktop\demo\ai-cabinet")
SB = pathlib.Path(os.environ["TEMP"]) / "p08-ab"

GUARD_SCRIPT = SB / "scripts" / "check-docker-tests-guarded.mjs"
GUARD_ORIG = REPO / "scripts" / "check-docker-tests-guarded.mjs"
CI_SB = SB / ".github" / "workflows" / "ci.yml"
CI_ORIG = REPO / ".github" / "workflows" / "ci.yml"

assert SB.exists(), f"沙箱不存在: {SB}"
assert GUARD_SCRIPT.exists(), f"脚本副本不存在: {GUARD_SCRIPT}"


def reset():
    shutil.copyfile(GUARD_ORIG, GUARD_SCRIPT)
    shutil.copyfile(CI_ORIG, CI_SB)


def edit(path, old, new, count=1):
    t = path.read_text(encoding="utf-8")
    n = t.count(old)
    assert n >= count, f"待替换文本未找到({n}<{count}): {old[:70]!r}"
    path.write_text(t.replace(old, new, count), encoding="utf-8", newline="")


def run():
    r = subprocess.run(
        [NODE, str(GUARD_SCRIPT)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    out = (r.stdout or "") + (r.stderr or "")
    return r.returncode, out.strip()


CASES = []


def case(label, expect_red, setup):
    reset()
    setup()
    rc, out = run()
    ok = (rc != 0) if expect_red else (rc == 0)
    CASES.append((label, expect_red, rc, ok))
    print(f"{'PASS' if ok else '**FAIL**'}  {label}  (rc={rc}, 期望{'红' if expect_red else '绿'})")
    for line in out.splitlines()[:4]:
        print(f"        {line}")
    print()


# ── 0. 基线 ────────────────────────────────────────────────────────────────
case("基线（真仓库内容）", False, lambda: None)

# ── 1. 源码里有 @Testcontainers，清单里被拿掉 ⇒ 等价于「新增类未登记」 ──────
def drift_unregistered():
    edit(
        GUARD_SCRIPT,
        """  [
    'services/trade-service/src/test/java/com/aicabinet/trade/integration/ReconciliationIntegrationTest.java',
    {
      job: 'build',
      why: 'surefire 跑（`*Test.java`，failsafe 只抓 `*IT.java`）；2026-09-19 前**不在任何守卫名单里**，本门禁的由来'
    }
  ],
""",
        "",
    )


case("漂移① 新增@WithTestcontainers类但未登记（从清单移除一条）", True, drift_unregistered)

# ── 2. 清单里登记了不存在的类 ⇒ 清单腐烂 ─────────────────────────────────
def drift_stale():
    edit(
        GUARD_SCRIPT,
        "  [\n    'services/trade-service/src/test/java/com/aicabinet/trade/e2e/AdminE2ETest.java',",
        "  [\n    'services/trade-service/src/test/java/com/aicabinet/trade/e2e/GhostE2ETest.java',\n"
        "    { job: 'build', why: '占位' }\n  ],\n"
        "  [\n    'services/trade-service/src/test/java/com/aicabinet/trade/e2e/AdminE2ETest.java',",
    )


case("漂移② 清单登记了源码里已不存在的类", True, drift_stale)

# ── 3. 破坏 build 守卫的「按源码自动发现」（把 -name 模式改掉） ──────────
ANCHOR_BUILD = "-name '*Test.java'"
ANCHOR_INTEG = "-name '*IT.java'"
BUILD_COMMENT = "      # 本 job 是 `mvn verify -DskipITs"
INTEG_COMMENT = "#    ⚠️ 本 job 的 -pl 范围变化时这条规则自动跟随，无需改脚本。"


def drift_build_anchor():
    edit(CI_SB, ANCHOR_BUILD, "-name 'NEVER_MATCHES.java'")


case("漂移③ build 守卫删掉命令里的 -name '*Test.java'（自动发现被破坏）", True, drift_build_anchor)


def drift_integ_anchor():
    edit(CI_SB, ANCHOR_INTEG, "-name 'NEVER_MATCHES.java'")


case("漂移④ integration 守卫删掉命令里的 -name '*IT.java'", True, drift_integ_anchor)

# ── 对照：同一输入，把实现换成「不剥注释」 ⇒ 注释里的锚点会把它救成活 ──────
def variant_no_strip():
    # 注释里本来就会写这些模式名（解释判据）；这里显式造出来
    edit(CI_SB, BUILD_COMMENT, f"      # 说明文字里提到 {ANCHOR_BUILD} 是常态\n{BUILD_COMMENT}")
    edit(CI_SB, ANCHOR_BUILD, "-name 'NEVER_MATCHES.java'")
    edit(GUARD_SCRIPT, "const code = stripShellLineComments(raw);", "const code = raw;")


case("对照 不剥注释的实现跑漂移③（应假绿 ⇒ 证明剥注释必要）", False, variant_no_strip)


def variant_no_strip_integ():
    edit(CI_SB, INTEG_COMMENT, f"#    说明文字里提到 {ANCHOR_INTEG} 是常态\n{INTEG_COMMENT}")
    edit(CI_SB, ANCHOR_INTEG, "-name 'NEVER_MATCHES.java'")
    edit(GUARD_SCRIPT, "const code = stripShellLineComments(raw);", "const code = raw;")


case("对照 不剥注释的实现跑漂移④（应假绿）", False, variant_no_strip_integ)

# ── 反假红：只改注释文字（塞满锚点关键词），命令不动 ⇒ 必须仍绿 ─────────────
def antired_comment():
    edit(
        CI_SB,
        "      # 依赖 Docker（Testcontainers）的测试在缺 Docker 时会被**静默跳过**",
        f"      # 反假红对照：{ANCHOR_BUILD} / {ANCHOR_INTEG} / surefire-reports / failsafe-reports",
    )


case("反假红A 注释里塞满锚点关键词，命令未动", False, antired_comment)


def antired_why():
    edit(GUARD_SCRIPT, "why: '同左：surefire + build job 守卫的自动扫'", "why: '同左：@Testcontainers surefire-reports'")


case("反假红B 清单 why 文字里塞锚点关键词", False, antired_why)

reset()

print("=" * 72)
bad = [c for c in CASES if not c[3]]
print(f"A/B 汇总：{len(CASES)} 例，不符合期望 {len(bad)} 例")
for label, expect_red, rc, ok in CASES:
    print(f"  {'OK ' if ok else 'BAD'}  {'红' if expect_red else '绿'}  rc={rc:<3} {label}")
sys.exit(1 if bad else 0)
