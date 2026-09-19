"""
P0-9 A/B（v2）：验证「新增单测」与「既有跨模块契约测试」在真实漂移下各自的响应。

v1 暴露的两个问题（v2 修掉）：
  1. 🔴 判据把「**无报告**」当成「跑了且绿」—— 基线与漂移的区别在于：
     漂移后单测先红 ⇒ Maven reactor 在 device-simulator 中止 ⇒ device-service 从未执行，
     契约测试「没有报告」。v2 把「未跑」单独判为**无效**，永远算不符期望。
  2. 🔴 跨模块漂移的可见性：`mvn test` 只到 test 阶段，device-service 在 reactor 里中止后
     拿不到漂移版。v2 改为显式 `install`（-DskipTests）把漂移版装进本地仓库，
     再**单独**跑 device-service —— 实验结束无条件重装正确版（否则本地仓库会长期留着漂移产物）。

纪律：源码逐字节快照 + sha256 还原校验；产物每次重清；本地仓库每用例开始/结束都归位。
"""

import hashlib
import pathlib
import re
import subprocess
import sys
import time

REPO = pathlib.Path(r"C:\Users\cwx\OneDrive\Desktop\demo\ai-cabinet")
TMP = REPO / ".tmp"
SIM = REPO / "edge/device-simulator/src/main/java/com/aicabinet/simulator/SimulatorSupport.java"
UNIT_XML = (
    REPO
    / "edge/device-simulator/target/surefire-reports/TEST-com.aicabinet.simulator.SimulatorSupportTest.xml"
)
IT_XML = (
    REPO
    / "services/device-service/target/surefire-reports/TEST-com.aicabinet.device.mqtt.EdgeCloudMqttContractTest.xml"
)

ORIG_BYTES = SIM.read_bytes()
ORIG_SHA = hashlib.sha256(ORIG_BYTES).hexdigest()
RESULTS = []


def sha_of(p: pathlib.Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def restore() -> bool:
    SIM.write_bytes(ORIG_BYTES)
    return sha_of(SIM) == ORIG_SHA


def report(p: pathlib.Path):
    """返回 (testcase 数, 失败用例名) 或 None（文件不存在 = 没跑）。"""
    if not p.exists():
        return None
    t = p.read_text(encoding="utf-8", errors="replace")
    cases, failed = 0, []
    for blk in re.split(r"(?=<testcase\b)", t):
        m = re.match(r"<testcase\b([^>]*)>", blk)
        if not m:
            continue
        cases += 1
        nm = re.search(r'name="([^"]*)"', m.group(1))
        if re.search(r"<(failure|error)\b", blk):
            failed.append(nm.group(1) if nm else "?")
    return cases, failed


def clear_reports():
    for p in (UNIT_XML, IT_XML):
        if p.exists():
            p.unlink()


def mvn(args, label):
    quoted = " ".join(f"'{a}'" for a in args)
    out = TMP / f"p09v2-{label}.txt"
    ps = (
        f"Set-Location '{REPO}'; mvn -B {quoted} 2>&1 | Out-File -Encoding utf8 '{out}'; "
        f"\"EXITCODE=$LASTEXITCODE\" | Out-File -Append -Encoding utf8 '{out}'"
    )
    subprocess.run(
        ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
        check=False,
        capture_output=True,
    )
    txt = out.read_text(encoding="utf-8", errors="replace") if out.exists() else ""
    m = re.search(r"EXITCODE=(\d+)", txt)
    return int(m.group(1)) if m else -999


def run_unit(label):
    return mvn(["test", "-pl", "edge/device-simulator", "-am"], f"{label}-unit")


def run_install(label):
    return mvn(["install", "-pl", "edge/device-simulator", "-am", "-DskipTests"], f"{label}-install")


def run_contract(label):
    return mvn(
        [
            "test",
            "-pl",
            "services/device-service",
            "-Dtest=EdgeCloudMqttContractTest",
            "-Dsurefire.failIfNoSpecifiedTests=false",
        ],
        f"{label}-contract",
    )


def state(r):
    """把报告折叠成 红 / 绿 / 未跑。未跑永远算不符期望。"""
    if r is None:
        return "未跑"
    return "红" if r[1] else "绿"


def case(idx, title, mutate, exp_unit, exp_it, need_reinstall=True):
    print(f"\n{'=' * 78}\n【{title}】", flush=True)
    if mutate:
        mutate()
        print(f"  注入后 sha={sha_of(SIM)[:12]}  (基线 {ORIG_SHA[:12]})", flush=True)
    clear_reports()
    rc1 = run_unit(f"c{idx}")
    unit = report(UNIT_XML)
    su = state(unit)

    if need_reinstall:
        run_install(f"c{idx}")  # 把（漂移版）源码装进本地仓库，使 device-service 能看到
    if IT_XML.exists():
        IT_XML.unlink()  # 清掉上一次的契约报告，避免「读到旧报告」当成本次结果
    rc2 = run_contract(f"c{idx}")
    it = report(IT_XML)
    si = state(it)

    back_ok = restore()
    run_install(f"c{idx}-restore")  # 🔴 无条件把正确版装回本地仓库

    print(f"  单测({rc1}) → {su}  {unit[1] if unit and unit[1] else ''}", flush=True)
    print(f"  契约({rc2}) → {si}  {it[1] if it and it[1] else ''}", flush=True)
    print(f"  源码还原={back_ok}", flush=True)

    good = su == exp_unit and si == exp_it and back_ok
    RESULTS.append((title, su, si, back_ok, good, exp_unit, exp_it))
    print(f"  期望：单测={exp_unit} 契约={exp_it} ⇒ {'符合 ✅' if good else '不符 ❌'}", flush=True)
    return good


# ── 漂移 ────────────────────────────────────────────────────────────────────
ACK_MAPOF = """        return Map.of(
                "type", CabinetConstants.MQTT_EVENT_TYPE_ACK,
                "commandId", commandId,
                "success", success,
                "timestamp", timestamp);"""
ACK_LINKEDHASH = """        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", CabinetConstants.MQTT_EVENT_TYPE_ACK);
        payload.put("commandId", commandId);
        payload.put("success", success);
        payload.put("timestamp", timestamp);
        return payload;"""


def patch(old, new):
    src = SIM.read_text(encoding="utf-8")
    assert src.count(old) == 1, f"锚点命中 {src.count(old)} 次：{old[:60]!r}"
    SIM.write_text(src.replace(old, new), encoding="utf-8", newline="\n")


def drift_ack():
    patch(ACK_MAPOF, ACK_LINKEDHASH)


def drift_heartbeat_garbage():
    patch('payload.put("appVersion", appVersion);', 'payload.put("appVer", appVersion);')


def antired_comment():
    patch(
        "    // 🔴 字段名/类型/取值语义与抽取前**完全一致**（无任何有意差异）：",
        "    // 反假红对照：ack 的 Map.of / null⇒NPE / appVersion camelCase 这些词只写在注释里。",
    )


if __name__ == "__main__":
    t0 = time.time()
    print(f"基线 sha256 = {ORIG_SHA}")
    print("\n【前置】先确保本地仓库是**正确版** device-simulator（防上次中断残留漂移版）...", flush=True)
    assert restore(), "源码还原失败，中止"
    run_install("pre")

    print("\n【基线】...", flush=True)
    clear_reports()
    rc_u = run_unit("base")
    unit = report(UNIT_XML)
    rc_c = run_contract("base")
    it = report(IT_XML)
    if state(unit) != "绿" or state(it) != "绿":
        print(f"❌ 基线不绿（单测={state(unit)} 契约={state(it)}），中止")
        restore()
        run_install("base-restore")
        sys.exit(2)
    print(f"✅ 基线绿：单测 testcase={unit[0]}，契约 testcase={it[0]}（EXITCODE {rc_u}/{rc_c}）", flush=True)

    case(
        1,
        "漂移① ack → LinkedHashMap（模拟「顺手优化」；契约侧不受影响）",
        drift_ack,
        "红",
        "绿",
    )
    case(
        2,
        "漂移② heartbeat appVersion → appVer（云端不认的名字；两侧都该红）",
        drift_heartbeat_garbage,
        "红",
        "红",
    )
    case(
        3,
        "反假红 仅改注释（塞满全部关键词），逻辑不动",
        antired_comment,
        "绿",
        "绿",
        need_reinstall=False,
    )

    print(f"\n{'=' * 78}\n汇总（耗时 {time.time() - t0:.0f}s）")
    for title, su, si, rsc, good, eu, ei in RESULTS:
        print(f"  {'✅' if good else '❌'} {title[:50]:50s} 单测={su}(期望{eu}) 契约={si}(期望{ei}) 还原={rsc}")
    bad = [r[0] for r in RESULTS if not r[4]]
    print(
        f"\n结果：{len(RESULTS) - len(bad)}/{len(RESULTS)} 例符合期望｜"
        f"最终源码 sha 一致={sha_of(SIM) == ORIG_SHA}"
    )
    sys.exit(1 if bad else 0)
