set -euo pipefail
report=services/device-service/target/failsafe-reports/TEST-com.aicabinet.device.mqtt.EmqxSharedSubscriptionIT.xml
if [ ! -f "$report" ]; then
  echo "missing failsafe report: $report（failsafe 没绑到 verify，或该 IT 没被识别）"
  ls -la services/device-service/target/failsafe-reports 2>/dev/null \
    || echo "(failsafe-reports 目录根本不存在 ⇒ IT 一次都没执行)"
  exit 1
fi
n=$(grep -o '<testcase ' "$report" | wc -l || true)
if [ "$n" -lt 1 ]; then
  echo "EmqxSharedSubscriptionIT 执行了 0 条用例 ⇒ 静默跳过（被 disabled 或根本没跑到）"
  exit 1
fi
if grep -qE '<failure|<error' "$report"; then
  echo "集成测试出现 failure/error（mvn 应已失败，这里再判一次）"
  exit 1
fi
echo "ok  EmqxSharedSubscriptionIT  testcases=$n"
