set -euo pipefail

# 判据只看 `<testcase>` 元素个数：`*.txt` 的 `Tests run:` 与 XML 根元素的 tests=
# 在 @Nested / IT 类上都会写 0，按它们判会「一条都没跑却照样绿」。

# ① 反向扫：已生成的每份报告都必须真跑过用例、且没有 failure/error。
reports=()
while IFS= read -r r; do reports+=("$r"); done \
  < <(find services edge -path '*/target/failsafe-reports/TEST-*.xml' 2>/dev/null | sort)
if [ "${#reports[@]}" -lt 1 ]; then
  echo "RED  一份 failsafe 报告都没有 ⇒ IT 静默跳过（failsafe 没绑到 verify，或被整体跳过）"
  ls -1d */target/failsafe-reports 2>/dev/null || true
  exit 1
fi
for report in "${reports[@]}"; do
  name=$(basename "$report" .xml)
  n=$(grep -o '<testcase ' "$report" | wc -l || true)
  if [ "$n" -lt 1 ]; then
    echo "RED  $name  执行了 0 条用例 ⇒ 静默跳过（被 disabled 或根本没跑到）"
    exit 1
  fi
  if grep -qE '<failure|<error' "$report"; then
    echo "RED  $name  出现 failure/error（mvn 应已失败，这里再判一次）"
    exit 1
  fi
  echo "ok   $name  testcases=$n"
done

# ② 正向扫（防「新 IT 根本没被发现」）：源码里每个 *IT.java 都必须有对应报告。
#    只遍历已有报告是**不够**的 —— 新 IT 若在 discover 阶段就出局（如 failsafe 的
#    fork classpath 里本模块是 fat jar ⇒ ClassNotFoundException），连报告文件都不会
#    生成，① 会一无所知，换个文件名就能绕过守卫。
#    「在不在本 job 范围内」用 `<module>/target/` 是否存在判断：本 job 是
#    `mvn verify -pl services/device-service -am`，被构建的模块一定有 target/；
#    没被构建的模块（如 trade-service）在 CI 全新检出下没有 target/ ⇒ 跳过，不误红。
#    ⚠️ 本 job 的 -pl 范围变化时这条规则自动跟随，无需改脚本。
missing=0
while IFS= read -r src; do
  mod=${src%%/src/test/java/*}
  [ -d "$mod/target" ] || continue          # 本 job 没构建该模块 ⇒ 不在范围内
  fqcn=${src#*/src/test/java/}
  fqcn=${fqcn%.java}
  fqcn=$(printf '%s' "$fqcn" | tr '/' '.')
  report="$mod/target/failsafe-reports/TEST-$fqcn.xml"
  if [ ! -f "$report" ]; then
    echo "RED  $fqcn  有源码但**没有报告** ⇒ 该 IT 根本没被执行（discover 阶段就出局）"
    ls -1 "$mod/target/failsafe-reports" 2>/dev/null \
      || echo "     (该模块没有 failsafe-reports 目录)"
    missing=$((missing + 1))
    continue
  fi
  if [ "$(grep -o '<testcase ' "$report" | wc -l || true)" -lt 1 ]; then
    echo "RED  $fqcn  报告在但 0 条用例 ⇒ 静默跳过"
    missing=$((missing + 1))
  fi
done < <(find services edge -path '*/src/test/java/*' -name '*IT.java' 2>/dev/null | sort)

if [ "$missing" -gt 0 ]; then
  echo "有 $missing 个 *IT.java 没有被真正执行"
  exit 1
fi
echo "集成测试守卫通过：真跑报告 ${#reports[@]} 份，源码中全部 *IT.java 均有对应报告且无失败"
