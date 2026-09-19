#!/usr/bin/env python3
"""A/B 漂移证明：识别结果落库（recognition_result）的四条核心判据真的会红。

纪律（沿用本仓既定做法）：
- 只认 surefire XML 里 <testcase> 元素的个数与失败名单，不认 "Tests run:" 文本。
- 每轮跑前删除 XML —— 「无产物 ≠ 绿」，残留 XML 会把上一轮结果读成这一轮的。
- 每轮无条件还原正确版（finally），并做字节级一致性回验。
- 基线不绿即中止（否则后面的红没有意义）。
- 字节级读写，绝不让 Python 顺手把 LF 换成 CRLF。
- 每轮断言「恰好只有预期的那条用例红」——多红一条都说明判据之间有耦合，需查明。

漂移：
  A 移除「会话级去重」闸      → 预期 persist_sessionAlreadyHasResult_skips 红
  B 移除「task_id 去重」闸    → 预期 persist_sameTaskIdTwice_skipsSecondWrite 红
  C 移除 model_version 列宽闸 → 预期 persist_modelVersionOverColumnWidth_invalidAndNeverTouchesDb 红
  D 摘掉结算侧写入钩子        → 预期 recognitionApplied_persistsResultOnce 红
  E items 恒序列化成空数组    → 预期 persist_itemsJsonUsesQuantityKeyAndNotQty 红
"""
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(r"C:\Users\cwx\OneDrive\Desktop\demo\ai-cabinet")
SVC = ROOT / "services/trade-service"
WRITER = SVC / "src/main/java/com/aicabinet/trade/service/RecognitionResultWriter.java"
SETTLE = SVC / "src/main/java/com/aicabinet/trade/service/SettlementRecognitionService.java"
REPORTS = SVC / "target/surefire-reports"
LOG = Path(r"C:\Users\cwx\AppData\Local\Temp\mvn-ab-recognition.log")

WRITER_TEST = "RecognitionResultWriterTest"
SETTLE_TEST = "SettlementDisputeTest"

# kind=block: 从 marker 起整段 if 块（到下一个 8 空格缩进 '}' ）替换
# kind=line : 单行替换
DRIFTS = [
    dict(
        name="A 移除会话级去重",
        f=WRITER,
        kind="block",
        marker=b"        if (mapper.findBySessionId(sessionId).isPresent()) {",
        repl=b"        // drift A: session-level dedupe removed\n",
        test=WRITER_TEST,
        expect_red="persist_sessionAlreadyHasResult_skips",
    ),
    dict(
        name="B 移除 task_id 去重",
        f=WRITER,
        kind="block",
        marker=b"        if (mapper.findById(taskId).isPresent()) {",
        repl=b"        // drift B: taskId dedupe removed\n",
        test=WRITER_TEST,
        expect_red="persist_sameTaskIdTwice_skipsSecondWrite",
    ),
    dict(
        name="C 移除 model_version 列宽闸",
        f=WRITER,
        kind="block",
        marker=b"        if (modelVersion != null && modelVersion.length() > VisionRecognitionResultDto.MODEL_VERSION_MAX_LENGTH) {",
        repl=b"        // drift C: modelVersion column-width guard removed\n",
        test=WRITER_TEST,
        expect_red="persist_modelVersionOverColumnWidth_invalidAndNeverTouchesDb",
    ),
    dict(
        name="D 摘掉结算侧写入钩子",
        f=SETTLE,
        kind="line",
        marker=b"        recordRecognitionResult(session, recognition);\n",
        repl=b"        // drift D: persistence hook removed\n",
        test=SETTLE_TEST,
        expect_red="recognitionApplied_persistsResultOnce",
    ),
    dict(
        name="E items 恒序列化为空数组",
        f=WRITER,
        kind="line",
        marker=b"        return objectMapper.writeValueAsString(items);\n",
        repl=b"        return EMPTY_ITEMS_JSON; // drift E\n",
        test=WRITER_TEST,
        expect_red="persist_itemsJsonUsesQuantityKeyAndNotQty",
    ),
]


def run_tests(test_class: str) -> tuple[int, int, list[str]]:
    for p in REPORTS.glob("TEST-*.xml"):
        p.unlink()
    cmd = (
        "$ErrorActionPreference='Continue'; "
        f"Set-Location '{ROOT}'; "
        "& mvn -B test -pl services/trade-service "
        f"'-Dtest={test_class}' '-Dsurefire.failIfNoSpecifiedTests=false' "
        f"*>&1 | Out-File -Encoding utf8 '{LOG}'; "
        f"\"EXITCODE=$LASTEXITCODE\" | Out-File -Append -Encoding utf8 '{LOG}';"
    )
    subprocess.run(["powershell", "-NoProfile", "-Command", cmd], timeout=1800, check=False)

    xmls = list(REPORTS.glob(f"TEST-com.aicabinet.trade.service.{test_class}.xml"))
    if not xmls:
        tail = ""
        if LOG.exists():
            tail = "\n".join(LOG.read_text(encoding="utf-8", errors="replace").splitlines()[-25:])
        raise RuntimeError(
            f"没有生成 {test_class} 的 surefire XML（多半是编译失败）—— 不能读成绿。日志尾部：\n{tail}"
        )
    root = ET.parse(xmls[0]).getroot()
    cases = root.findall("testcase")
    failed = [
        c.get("name") for c in cases
        if c.find("failure") is not None or c.find("error") is not None
    ]
    return len(cases), len(failed), failed


def apply_drift(data: bytes, kind: str, marker: bytes, repl: bytes) -> bytes:
    # 本仓 core.autocrlf=true ⇒ 工作副本多为 CRLF（索引仍是 LF）。锚点/替换串按文件实际换行符适配，
    # 否则 LF 锚点在 CRLF 文件里根本匹配不到（会变成「锚点未命中」而不是「判据红」）。
    nl = b"\r\n" if b"\r\n" in data else b"\n"
    marker = marker.replace(b"\n", nl)
    repl = repl.replace(b"\n", nl)
    idx = data.find(marker)
    if idx < 0:
        raise RuntimeError(f"漂移锚点未命中：{marker[:60]!r}")
    if kind == "line":
        return data[:idx] + repl + data[idx + len(marker):]
    end_marker = nl + b"        }" + nl
    end = data.find(end_marker, idx)
    if end < 0:
        raise RuntimeError("漂移块结束锚点未命中")
    return data[:idx] + repl + data[end + len(end_marker):]


def eol_profile(data: bytes) -> str:
    crlf = data.count(b"\r\n")
    lf = data.count(b"\n") - crlf
    if crlf and lf:
        return f"mixed(crlf={crlf},lf={lf})"
    return "crlf" if crlf else "lf"


def main() -> int:
    originals = {d["f"]: d["f"].read_bytes() for d in DRIFTS}
    for f, b in originals.items():
        print(f"   [eol] {f.name} = {eol_profile(b)}", flush=True)

    print("== 基线（应全绿）==", flush=True)
    baseline = {}
    for cls in (WRITER_TEST, SETTLE_TEST):
        total, failed, names = run_tests(cls)
        baseline[cls] = (total, failed)
        print(f"   {cls}: testcase={total} 失败={failed} {names}", flush=True)
        if failed:
            print("!! 基线不绿，中止（后面的红没有意义）")
            return 2

    results = []
    for d in DRIFTS:
        print(f"\n== 漂移 {d['name']}（预期仅 {d['expect_red']} 红）==", flush=True)
        f: Path = d["f"]
        try:
            f.write_bytes(apply_drift(f.read_bytes(), d["kind"], d["marker"], d["repl"]))
            total, failed, names = run_tests(d["test"])
            ok = d["expect_red"] in names
            results.append((d["name"], total, failed, names, ok))
            print(f"   {d['test']}: testcase={total} 失败={failed} 名单={names}", flush=True)
            print(f"   => {'判据会红，符合预期' if ok else '！！预期用例没有红，判据无效'}", flush=True)
        finally:
            f.write_bytes(originals[f])
            restored = f.read_bytes() == originals[f]
            if not restored:
                print(f"!! {f.name} 还原后与原始不一致！", flush=True)
                return 3

    print("\n== 汇总 ==")
    bad = [r for r in results if not r[4]]
    for name, total, failed, names, ok in results:
        print(f"   [{'OK' if ok else 'BAD'}] {name} → testcase={total} 失败={failed} {names}")
    print("\n字节级还原：", "全部一致" if not bad else "见上方 !!")
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
