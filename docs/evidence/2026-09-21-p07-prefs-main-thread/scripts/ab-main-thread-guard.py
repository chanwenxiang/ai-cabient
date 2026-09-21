#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P0-7 子项① A/B：证明 `PrefsJsonQueueMainThreadTest` 的**两向**断言都不是装饰。

为什么必须两向：
  - 只测「主线程写 ⇒ 告警」：把检测删掉就红，但把检测写成恒 true 仍绿 ⇒ 漏掉误报；
  - 只测「后台写 ⇒ 不告警」：恒 true 会红，但把检测删掉仍绿 ⇒ 漏掉真回归。
  所以两侧各注入一次漂移，各自只应让**对应用例**变红。

沙箱：整份 `edge/android-app` 复制到 %TEMP%/aicabinet-android-ab（非 OneDrive），
仓库文件**一字不改**（脚本首尾各取一次仓库文件 sha256 自证）。
`edge/**` 磁盘是 CRLF ⇒ 多行锚点先把 CRLF 归一再匹配（否则命中 0 次会被当成
「没匹配」，见 EDGE-ANDROID-GRADLE.md §6）。

用法：
  python docs/evidence/2026-09-21-p07-prefs-main-thread/scripts/ab-main-thread-guard.py
退出码：0 = A/B 全部符合预期；1 = 有状态不符合预期或沙箱有问题。
"""

import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
SRC_APP = REPO_ROOT / "edge" / "android-app"
QUEUE_REL = Path("app/src/main/java/com/aicabinet/edge/queue/PrefsJsonQueue.kt")

TEST_CLASS = "com.aicabinet.edge.queue.PrefsJsonQueueMainThreadTest"

GRADLE_BIN_DIR = Path.home() / (
    ".gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin"
)
# 🔴 同目录下的 `gradle` 是 **POSIX sh 脚本**，Python 在 Windows 上直接 spawn 会
# `OSError: [WinError 193] %1 不是有效的 Win32 应用程序`。批处理入口 `gradle.bat`
# 必须经 `cmd /c` 调用（直接跑 .bat 同样 193）。
GRADLE_BAT = GRADLE_BIN_DIR / "gradle.bat"

_localtmp = os.environ.get("LOCALAPPDATA") or os.environ.get("TEMP") or tempfile.gettempdir()
TMP = Path(str(_localtmp).replace("\\", "/"))
SDK = Path(os.environ.get("ANDROID_SDK", TMP / "Temp/aicabinet-android-sdk"))
AB_DIR = Path(os.environ.get("AB_DIR", TMP / "Temp/aicabinet-android-ab"))

# 真实实现（与其在源码里的逐字形态一致；每次 patch 都用**仓库原文**重新锚定）
ORIG = """        internal fun isOnMainThread(): Boolean {
            val current = Looper.myLooper() ?: return false
            return current == Looper.getMainLooper()
        }"""

# 漂移①：检测永不触发 —— 应让「主线程写必须被检测到」变红
DRIFT_NEVER = """        internal fun isOnMainThread(): Boolean {
            return false
        }"""

# 漂移②：检测恒真 —— 应让「后台线程写不得误报」变红
DRIFT_ALWAYS = """        internal fun isOnMainThread(): Boolean {
            return true
        }"""

STATES = [
    ("baseline", "基线（无注入）", ORIG, True),
    ("drift-never", "漂移① 检测恒 false", DRIFT_NEVER, False),
    ("drift-always", "漂移② 检测恒 true", DRIFT_ALWAYS, False),
]


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def sync_sandbox() -> None:
    if AB_DIR.exists():
        shutil.rmtree(AB_DIR)
        if AB_DIR.exists():  # 清空必须确证，否则「是否重新生成」失去判据
            raise SystemExit("RED  无法清空沙箱 %s" % AB_DIR)
    shutil.copytree(SRC_APP, AB_DIR, ignore=shutil.ignore_patterns(".gradle", "build"))
    (AB_DIR / "local.properties").write_text(
        "sdk.dir=%s\n" % str(SDK).replace("\\", "/"), encoding="utf-8"
    )


def write_drifted(body: str) -> None:
    """从**仓库原文**重新锚定，保证每个状态互不污染。"""
    raw = (SRC_APP / QUEUE_REL).read_bytes()
    crlf = b"\r\n" in raw
    text = raw.decode("utf-8").replace("\r\n", "\n")
    hits = text.count(ORIG)
    if hits != 1:
        raise SystemExit("RED  锚点命中 %d 次（期望 1）—— 源码已漂，A/B 失去意义" % hits)
    out = text.replace(ORIG, body).replace("\n", "\r\n") if crlf else text.replace(ORIG, body)
    (AB_DIR / QUEUE_REL).write_bytes(out.encode("utf-8"))


def run_tests(tag: str, logdir: Path) -> bool:
    """判据＝XML 里 <testcase> 个数与 <failure>/<error>，不看 gradle 退出码。"""
    log = logdir / ("ab-%s.log" % tag)
    with log.open("w", encoding="utf-8", errors="replace") as fh:
        p = subprocess.run(
            [
                "cmd",
                "/c",
                str(GRADLE_BAT),
                ":app:testMockDebugUnitTest",
                "--tests",
                TEST_CLASS,
                "--console=plain",
            ],
            cwd=str(AB_DIR),
            stdout=fh,
            stderr=subprocess.STDOUT,
            shell=False,
        )
    report = AB_DIR / ("app/build/test-results/testMockDebugUnitTest/TEST-%s.xml" % TEST_CLASS)
    if not report.exists():
        print("      testcase=0（无报告）  gradle_exit=%d ⇒ 判红" % p.returncode)
        return False
    xml = report.read_text(encoding="utf-8", errors="replace")
    cases = xml.count("<testcase ")
    bad = len(re.findall(r"<failure|<error", xml))
    print("      testcase=%d  failure/error=%d  gradle_exit=%d" % (cases, bad, p.returncode))
    return cases > 0 and bad == 0


def main() -> int:
    if not GRADLE_BAT.exists():
        print("RED  找不到 gradle 8.9 的 gradle.bat: %s" % GRADLE_BAT)
        return 1
    if not (SDK / "platforms/android-34/android.jar").exists():
        print("RED  SDK 缺 android.jar: %s" % SDK)
        return 1

    repo_file = SRC_APP / QUEUE_REL
    before = sha(repo_file)

    print("== 沙箱：%s ==" % AB_DIR)
    sync_sandbox()
    logdir = AB_DIR
    ok = True

    for tag, name, body, expect_green in STATES:
        write_drifted(body)
        print("\n-- %s（期望 %s）" % (name, "绿" if expect_green else "红"))
        green = run_tests(tag, logdir)
        matched = green == expect_green
        ok = ok and matched
        print("      实测 %s ⇒ %s" % ("绿" if green else "红", "符合预期" if matched else "**不符合预期**"))

    after = sha(repo_file)
    print("\n== 仓库侧自证 ==")
    print("  %s" % repo_file)
    print("  sha256 before = %s" % before)
    print("  sha256 after  = %s" % after)
    if before != after:
        print("  RED  仓库文件被改动了！A/B 不成立")
        return 1
    print("  ok   仓库文件一字未改")

    print("\nA/B 结论：%s" % ("全部符合预期" if ok else "存在不符合预期的状态"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
