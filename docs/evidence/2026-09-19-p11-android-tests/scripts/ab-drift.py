#!/usr/bin/env python3
"""edge/android-app 单测的 A/B 注入漂移验证。

做法：把「改坏 main 源码」注入到 **临时构建副本**（不是仓库），逐条跑
`:app:testMockDebugUnitTest`，用 JUnit XML 里的 <testcase> 元素判定：

  * expect_red=True  —— 必须整场红，且**红的用例名里包含期望的那条**；
                        若整场绿 = 该用例没守住（漂移③）；若红了但红的不是它 = 判据落错地方。
  * expect_red=False —— 只改注释等语义等价改动，必须**仍然绿**（防「见字符串就红」的假红）。

跑完逐字节校验所有被改文件 sha256 回到基线（CRLF 原样，故全程 read_bytes/write_bytes）。

判据用「期望用例名的**子串**集合」，不用整份 XML 全等 —— 全等会被无关用例名变化搞脏。

退出码 0 = 全部符合期望；1 = 有不符合。
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BUILD = Path(os.environ.get("BUILD_DIR", r"C:\Users\cwx\AppData\Local\Temp\aicabinet-android-build"))
GRADLE_BAT = Path(
    os.environ.get(
        "GRADLE_BAT",
        r"C:\Users\cwx\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat",
    )
)
TEST_TASK = ":app:testMockDebugUnitTest"
REPORT_DIR = BUILD / "app" / "build" / "test-results" / "testMockDebugUnitTest"

SRC = BUILD / "app" / "src" / "main" / "java" / "com" / "aicabinet" / "edge"
F_QUEUE = SRC / "mqtt" / "OutboundMqttQueue.kt"
F_CHZH = SRC / "hal" / "chzh" / "ChzhLockDriver.kt"
F_VIDEO = SRC / "video" / "VideoClipJson.kt"
F_CFG = SRC / "config" / "EdgeRuntimeConfig.kt"
F_REC = SRC / "video" / "RecordingResult.kt"
F_CIPHER = SRC / "config" / "KeystoreCipher.kt"
F_PREFS = SRC / "queue" / "PrefsJsonQueue.kt"


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def read(p: Path) -> bytes:
    return p.read_bytes()


def write(p: Path, b: bytes) -> None:
    p.write_bytes(b)  # 不用 write_text：Windows 上它会把 \n 变成 \r\n


def run_tests() -> tuple[int, list[str], str]:
    """跑一次测试，返回 (退出码, 失败用例名列表, 原始输出尾部)."""
    if REPORT_DIR.exists():
        shutil.rmtree(REPORT_DIR)
    proc = subprocess.run(
        ["cmd", "/c", str(GRADLE_BAT), TEST_TASK, "--console=plain"],
        cwd=str(BUILD),
        capture_output=True,
    )
    out = (proc.stdout or b"").decode("utf-8", "replace") + (proc.stderr or b"").decode("utf-8", "replace")

    failing: list[str] = []
    if REPORT_DIR.exists():
        for xml in sorted(REPORT_DIR.glob("TEST-*.xml")):
            try:
                root = ET.fromstring(xml.read_bytes().decode("utf-8", "replace"))
            except ET.ParseError as e:  # 报告坏了要当红，不能静默
                failing.append(f"<PARSE-ERROR {xml.name}: {e}>")
                continue
            for tc in root.iter("testcase"):
                if tc.find("failure") is not None or tc.find("error") is not None:
                    failing.append(tc.get("name", "?"))
    return proc.returncode, failing, out


def apply_mutation(path: Path, old: str, new: str) -> bytes:
    """替换前先把原文（bytes）交回调用方做还原；断言锚点唯一。

    ⚠️ edge/** 在磁盘上是 CRLF，而脚本里的锚点是按 LF 手写的多行串 ——
    先把 CRLF 归一成 LF 再匹配，否则多行锚点永远匹配不上（会静默变成 0 次）。
    写入用 LF、还原用原始 bytes，故构建副本的行尾变化不影响仓库，也不影响还原的逐字节一致。
    """
    raw = read(path)
    text = raw.decode("utf-8").replace("\r\n", "\n")
    cnt = text.count(old)
    assert cnt == 1, f"{path.name}: 锚点出现 {cnt} 次（应为 1）：{old[:60]!r}"
    write(path, text.replace(old, new).encode("utf-8"))
    return raw


def main() -> int:
    assert BUILD.exists(), f"构建副本不存在：{BUILD}"
    assert F_QUEUE.exists(), f"源码不在预期位置：{F_QUEUE}"

    # 基线快照（用于最后逐字节校验还原）
    touched = [F_QUEUE, F_CHZH, F_VIDEO, F_CFG, F_REC, F_CIPHER, F_PREFS]
    baseline = {p: (read(p), sha(read(p))) for p in touched}

    cases = []

    def case(name, path, old, new, expect_red, expect_tests):
        cases.append(
            dict(name=name, path=path, old=old, new=new, expect_red=expect_red, expect=expect_tests)
        )

    # ── D1：把 DOOR 从关键类型白名单里删掉 ────────────────────────────────
    case(
        "D1 CRITICAL_TYPES 去掉 DOOR ⇒ 红",
        F_QUEUE,
        'private val CRITICAL_TYPES = setOf("DOOR", "ACK", "ALERT")',
        'private val CRITICAL_TYPES = setOf("ACK", "ALERT")',
        True,
        ["DOOR 类型的 payload 视为关键"],
    )
    # ── D2：取消 payload.type 判定，一律回退 topic 子串 ───────────────────
    case(
        "D2 取消 payload.type 分支（只按 topic 判）⇒ 红",
        F_QUEUE,
        """            if (!type.isNullOrBlank()) {
                return type.uppercase() in CRITICAL_TYPES
            }
            return isCriticalTopic(topic)""",
        """            return isCriticalTopic(topic)""",
        True,
        ["DOOR 类型的 payload 视为关键"],
    )
    # ── D3：把门磁解析的 when 分支顺序对调 ────────────────────────────────
    case(
        "D3 门磁解析调换分支顺序（打开优先）⇒ 红",
        F_CHZH,
        """                upper.contains("DOOR=C") || upper.contains("DOOR=0") || upper.contains("CLOSED") ->
                    DoorState.CLOSED
                upper.contains("DOOR=O") || upper.contains("DOOR=1") || upper.contains("OPEN") ->
                    DoorState.OPEN""",
        """                upper.contains("DOOR=O") || upper.contains("DOOR=1") || upper.contains("OPEN") ->
                    DoorState.OPEN
                upper.contains("DOOR=C") || upper.contains("DOOR=0") || upper.contains("CLOSED") ->
                    DoorState.CLOSED""",
        True,
        ["关闭分支优先于打开分支"],
    )
    # ── D4：把 capturedAt 写成常量 0 ──────────────────────────────────────
    # ⚠️ 这条用例**改过一次**。第一版漂移是「把 now 挪进 map，逐条取
    #    System.currentTimeMillis()」—— 结果**整场仍绿**：同一毫秒内两次取值相同，
    #    于是原来的「两条 capturedAt 相等」断言恒真（失效形态③，判据无法失败）。
    #    据此把那条判据改写为「必须落在本次调用窗口内」，并把漂移换成常量 0：
    #    它才能证明现存的判据**真的会红**。首版结果留档 ab-drift-v1-D4-vacuous.txt。
    case(
        "D4 capturedAt 写成常量 0 ⇒ 红（首版漂移证明旧判据恒真，见 README）",
        F_VIDEO,
        '                "capturedAt" to now',
        '                "capturedAt" to 0L',
        True,
        ["capturedAt 落在本次调用窗口内"],
    )
    # ── D5：给 isPlaceholderDeviceId 顺手加 trim ──────────────────────────
    case(
        "D5 isPlaceholderDeviceId 加 trim ⇒ 红（分层契约被改）",
        F_CFG,
        """    fun isPlaceholderDeviceId(deviceId: String): Boolean =
        deviceId.isBlank() || deviceId.equals("CAB-001", ignoreCase = true)""",
        """    fun isPlaceholderDeviceId(deviceId: String): Boolean =
        deviceId.trim().isBlank() || deviceId.trim().equals("CAB-001", ignoreCase = true)""",
        True,
        ["带空白的 CAB-001 不算占位（trim 责任在调用方）"],
    )
    # ── D6：fusionMode 边界 >= 2 改成 > 2 ─────────────────────────────────
    case(
        "D6 fusionMode 边界改成 > 2 ⇒ 红",
        F_REC,
        'val fusionMode: String = if (clips.size >= 2) "MULTI" else "SINGLE"',
        'val fusionMode: String = if (clips.size > 2) "MULTI" else "SINGLE"',
        True,
        ["两路及以上为 MULTI 且主文件取第一路"],
    )
    # ── D7：密文前缀版本号 v1 → v2 ────────────────────────────────────────
    case(
        "D7 密文前缀 enc:v1: → enc:v2: ⇒ 红",
        F_CIPHER,
        'const val PREFIX = "enc:v1:"',
        'const val PREFIX = "enc:v2:"',
        True,
        [
            "带前缀的负载判为密文",
            "前缀完整但负载为空仍判为密文（长度校验不在这里）",
            "版本前缀不匹配时按明文处理而非尝试解密",
        ],
    )
    # ══════════════════════════════════════════════════════════════════════
    # D8–D14：P0-12（Robolectric 批次）新增用例的注入漂移。
    # 这批判据全部落在**真 SharedPreferences / 真 enqueue / 真 drain** 上，
    # 所以必须注入到 main 源码里才有效（注入测试文件等于自己改判据，不算验证）。
    # ══════════════════════════════════════════════════════════════════════

    # ── D8：getString 去掉「空白 ⇒ 默认」回退 ─────────────────────────────
    case(
        "D8 getString 去掉空白回退（空白当有效值）⇒ 红",
        F_CFG,
        """    private fun getString(context: Context, key: String, default: String): String =
        prefs(context).getString(key, default)?.trim()?.takeIf { it.isNotEmpty() } ?: default""",
        """    private fun getString(context: Context, key: String, default: String): String =
        prefs(context).getString(key, default) ?: default""",
        True,
        ["空白值回退默认而不是当成空字符串", "字符串读取会 trim", "空白 deviceId 回退默认"],
    )
    # ── D9：出站队列容量去掉 coerceIn 夹紧 ────────────────────────────────
    case(
        "D9 mqttOutboundMaxItems 去掉 coerceIn ⇒ 红",
        F_CFG,
        '        getInt(context, "mqtt_outbound_max_items", 500).coerceIn(50, 5_000)',
        '        getInt(context, "mqtt_outbound_max_items", 500)',
        True,
        ["出站队列容量按下界夹紧", "出站队列容量按上界夹紧"],
    )
    # ── D10：队列满时不再挑非关键，一律丢第一条 ───────────────────────────
    case(
        "D10 enqueue 丢弃策略改成恒丢第 0 条 ⇒ 红",
        F_QUEUE,
        """                val dropIndex = pending.indexOfFirst { !isCriticalMessage(it.topic, it.payload) }
                    .takeIf { it >= 0 } ?: 0""",
        """                val dropIndex = 0""",
        True,
        ["超过上限时优先丢弃非关键消息_关键门事件必须留下"],
    )
    # ── D11：重试边界 < 改成 <=（少重试一轮）──────────────────────────────
    case(
        "D11 drain 的 attempts < maxAttempts 改成 <= ⇒ 红",
        F_QUEUE,
        "                    if (message.attempts < maxAttempts) {",
        "                    if (message.attempts <= maxAttempts) {",
        True,
        ["达到重试上限时放弃并回调告警_恰好一次"],
    )
    # ── D12：PrefsJsonQueue 去掉反序列化兜底（坏 JSON 直接抛）─────────────
    case(
        "D12 loadMutable 去掉 runCatching 兜底 ⇒ 红",
        F_PREFS,
        """        return runCatching { mapper.readValue(json, typeRef).toMutableList() }
            .onFailure { Log.w(tag, "queue decode failed: ${it.message}") }
            .getOrElse { mutableListOf() }""",
        """        return mapper.readValue(json, typeRef).toMutableList()""",
        True,
        ["损坏JSON_退化为空队列而不是抛异常", "类型不匹配的JSON_同样退化为空队列"],
    )
    # ── D13：ensureDeviceId 的 mock 分支取反 ──────────────────────────────
    case(
        "D13 ensureDeviceId 的 mock 分支取反（mock 档也生成并落盘）⇒ 红",
        F_CFG,
        "        if (BuildConfig.USE_MOCK_DRIVER) {",
        "        if (!BuildConfig.USE_MOCK_DRIVER) {",
        True,
        ["ensureDeviceId 在 mock 档下保留占位号且不落盘"],
    )
    # ── D14：把 getOrCreateKey() 提到长度校验之前 ─────────────────────────
    case(
        "D14 decrypt 把取密钥提到长度校验之前 ⇒ 红（顺序契约被改）",
        F_CIPHER,
        """        val data = Base64.decode(encoded.removePrefix(PREFIX), Base64.NO_WRAP)
        if (data.size <= IV_LENGTH_BYTES) {""",
        """        getOrCreateKey()
        val data = Base64.decode(encoded.removePrefix(PREFIX), Base64.NO_WRAP)
        if (data.size <= IV_LENGTH_BYTES) {""",
        True,
        ["decrypt_负载过短在触碰密钥前就拒绝"],
    )
    # ── D15：丢弃兜底从「最早」改成「最新」─────────────────────────────────
    # 这条专治「队列全是关键消息时丢弃最早的一条」可能是个**空判据**：
    # 全关键时 `indexOfFirst{!critical}` 返回 -1 ⇒ takeIf 得 null ⇒ 落到 `?: 0`。
    # 只有把兜底取值改成别的，才能证明那条用例真的钉住了「丢最早」而不是恰好通过。
    case(
        "D15 dropIndex 兜底 ?: 0 改成 ?: pending.size - 1 ⇒ 红",
        F_QUEUE,
        """                val dropIndex = pending.indexOfFirst { !isCriticalMessage(it.topic, it.payload) }
                    .takeIf { it >= 0 } ?: 0""",
        """                val dropIndex = pending.indexOfFirst { !isCriticalMessage(it.topic, it.payload) }
                    .takeIf { it >= 0 } ?: pending.size - 1""",
        True,
        ["队列全是关键消息时丢弃最早的一条"],
    )
    # ── R1：只加注释（语义等价）⇒ 必须仍绿 ────────────────────────────────
    case(
        "R1 仅加注释（语义等价）⇒ 必须仍绿",
        F_QUEUE,
        """        fun isCriticalMessage(topic: String, payload: String): Boolean {""",
        """        // drift-free: 这里只加一行注释，判据不该受影响
        fun isCriticalMessage(topic: String, payload: String): Boolean {""",
        False,
        [],
    )
    # ── R2：只插空行（纯格式）⇒ 必须仍绿（防「见改动就红」）───────────────
    case(
        "R2 仅插入空行（纯格式）⇒ 必须仍绿",
        F_PREFS,
        """    private fun save(items: List<T>) {""",
        """

    private fun save(items: List<T>) {""",
        False,
        [],
    )

    results = []

    # 预检：所有锚点必须唯一命中，且**不写盘**。锚点写错（缩进/CRLF/错字）时
    # 立刻失败，不必等 8 次 gradle 跑完才发现。
    preflight_bad = []
    for c in cases:
        text = read(c["path"]).decode("utf-8").replace("\r\n", "\n")
        n = text.count(c["old"])
        if n != 1:
            preflight_bad.append(f"{c['name']}  ← {c['path'].name} 锚点命中 {n} 次")
    if preflight_bad:
        print("预检失败（锚点不唯一）：")
        for b in preflight_bad:
            print("  " + b)
        return 1
    print(f"预检通过：{len(cases)} 个锚点各命中 1 次\n")

    for c in cases:
        original = apply_mutation(c["path"], c["old"], c["new"])
        try:
            rc, failing, out = run_tests()
        finally:
            write(c["path"], original)

        if c["expect_red"]:
            ok = rc != 0 and all(
                any(exp in f for f in failing) for exp in c["expect"]
            )
            detail = f"exit={rc} 失败用例={failing}"
            if rc == 0:
                detail += "  ← 形态③：注入漂移后仍绿"
            elif not all(any(e in f for f in failing) for e in c["expect"]):
                detail += f"  ← 红了但没红在预期用例（期望含：{c['expect']}）"
        else:
            ok = rc == 0 and not failing
            detail = f"exit={rc} 失败用例={failing}"
        results.append((c["name"], ok, detail))
        print(f"{'PASS' if ok else 'FAIL'}  {c['name']}\n      {detail}")

    print("\n=== 逐字节还原校验 ===")
    restore_ok = True
    for p, (raw, h) in baseline.items():
        now_h = sha(read(p))
        same = now_h == h
        restore_ok &= same
        print(f"{'ok ' if same else 'BAD'}  {p.name}  sha256={'一致' if same else f'{h} -> {now_h}'}")

    n_ok = sum(1 for _, ok, _ in results if ok)
    print(f"\n=== 结论：{n_ok}/{len(results)} 符合期望；还原 {'一致' if restore_ok else '不一致'} ===")
    return 0 if (n_ok == len(results) and restore_ok) else 1


if __name__ == "__main__":
    sys.exit(main())
