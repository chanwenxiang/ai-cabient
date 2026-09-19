#!/usr/bin/env bash
# P0-7 CI 守卫 A/B 负向验证驱动。
# 目的：证明 (a) 新守卫在真漂移下必红；(b) 旧守卫（只盯 EmqxSharedSubscriptionIT 单文件）会漏掉该漂移。
# 一切实验在非 OneDrive 沙箱里做，绝不触碰真仓库工作区。
export PATH="/usr/bin:/bin:$PATH"

REPO="C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet"
SB="/c/Users/cwx/AppData/Local/Temp/p07-guard-sandbox"
GUARD="$REPO/.tmp/p07-guard.sh"       # 从改后 ci.yml 抽出的新守卫
OLD="$REPO/.tmp/p07-old-guard.sh"     # 从 git HEAD ci.yml 抽出的旧守卫
DS="services/device-service"
FR="$DS/target/failsafe-reports"
SRC="$DS/src/test/java/com/aicabinet/device/mqtt"

reset_sb() {
  rm -rf "$SB"
  mkdir -p "$SB/$FR" "$SB/$SRC"
  cp "$REPO/$FR/TEST-com.aicabinet.device.mqtt.EdgeCloudMqttIntegrationIT.xml" \
     "$REPO/$FR/TEST-com.aicabinet.device.mqtt.EmqxSharedSubscriptionIT.xml" \
     "$SB/$FR/"
  touch "$SB/$SRC/EmqxSharedSubscriptionIT.java" \
        "$SB/$SRC/EdgeCloudMqttIntegrationIT.java"
}

scenario() {           # $1=名称 $2=变异命令 $3=守卫脚本（expect 输出 rc）
  local name="$1" mut="$2" g="$3" out rc
  reset_sb
  ( cd "$SB" && eval "$mut" )
  out=$( cd "$SB" && bash "$g" 2>&1 ); rc=$?
  printf '%-44s %-9s rc=%d\n' "$name" "$(basename "$g" .sh | sed 's/p07-//')" "$rc"
  printf '%s\n' "$out" | sed 's/^/        | /'
}

echo "=========== P0-7 CI 守卫 A/B ==========="

echo; echo "--- 基线（期望：新/旧守卫都绿）---"
scenario "baseline" "true" "$GUARD"
scenario "baseline" "true" "$OLD"

echo; echo "--- 漂移①：新增 IT 源码但报告不存在（本轮要堵的洞）---"
M1="touch $SRC/BrandNewIT.java"
scenario "drift1 new-IT-no-report" "$M1" "$GUARD"
scenario "drift1 new-IT-no-report" "$M1" "$OLD"

echo; echo "--- 漂移②：报告在但 0 条用例（被 disabled/静默跳过）---"
M2="sed -i 's/<testcase /<xcase /g' $FR/TEST-com.aicabinet.device.mqtt.EdgeCloudMqttIntegrationIT.xml"
scenario "drift2 report-with-0-testcases" "$M2" "$GUARD"
scenario "drift2 report-with-0-testcases" "$M2" "$OLD"

echo; echo "--- 漂移③：报告里出现 failure ---"
M3="sed -i 's#</testcase>#<failure message=\"boom\"/></testcase>#' $FR/TEST-com.aicabinet.device.mqtt.EmqxSharedSubscriptionIT.xml"
scenario "drift3 report-with-failure" "$M3" "$GUARD"
scenario "drift3 report-with-failure" "$M3" "$OLD"

echo; echo "--- 漂移④：一份报告都没有 ---"
M4="rm -f $FR/TEST-*.xml"
scenario "drift4 no-report-at-all" "$M4" "$GUARD"
scenario "drift4 no-report-at-all" "$M4" "$OLD"

echo; echo "--- 漂移⑤：edge 模块（-am 闭包内）的 IT 没被跑 ---"
M7="mkdir -p edge/device-simulator/target edge/device-simulator/src/test/java/com/aicabinet/simulator && touch edge/device-simulator/src/test/java/com/aicabinet/simulator/EdgeScopeIT.java"
scenario "drift5 edge-module-IT-no-report" "$M7" "$GUARD"

echo; echo "--- 反假红⑤：本 job 未构建的模块里存在 IT 源码（不应误红）---"
M5="mkdir -p services/trade-service/src/test/java/com/aicabinet/trade && touch services/trade-service/src/test/java/com/aicabinet/trade/OutOfScopeIT.java"
scenario "anti-false-red out-of-scope-IT" "$M5" "$GUARD"

echo; echo "--- 反假红⑥：已构建模块里 IT 源码与报告齐备（DeviceSimulator 类型改名场景）---"
M6="mv $SRC/EdgeCloudMqttIntegrationIT.java $SRC/EdgeCloudMqttContractIT.java; mv $FR/TEST-com.aicabinet.device.mqtt.EdgeCloudMqttIntegrationIT.xml $FR/TEST-com.aicabinet.device.mqtt.EdgeCloudMqttContractIT.xml"
scenario "anti-false-red renamed-IT" "$M6" "$GUARD"

rm -rf "$SB"
echo; echo "=========== done ==========="
