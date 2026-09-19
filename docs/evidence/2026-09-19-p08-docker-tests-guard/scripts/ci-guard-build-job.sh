set -euo pipefail

found=0
while IFS= read -r src; do
  # 只认真正的注解行（行首，允许缩进）——注释里提到 @Testcontainers 不算。
  grep -qE '^[[:space:]]*@Testcontainers' "$src" || continue
  mod=${src%%/src/test/java/*}
  [ -d "$mod/target" ] || continue      # 本 job 没构建该模块 ⇒ 不在范围内
  fqcn=${src#*/src/test/java/}
  fqcn=${fqcn%.java}
  fqcn=$(printf '%s' "$fqcn" | tr '/' '.')
  report="$mod/target/surefire-reports/TEST-$fqcn.xml"
  if [ ! -f "$report" ]; then
    echo "RED  $fqcn  有源码但**没有 surefire 报告** ⇒ 该测试根本没执行"
    ls -1 "$mod/target/surefire-reports" 2>/dev/null | head -5 || true
    exit 1
  fi
  n=$(grep -o '<testcase ' "$report" | wc -l || true)
  if [ "$n" -lt 1 ]; then
    echo "RED  $fqcn  testcases=0 ⇒ 静默跳过（Docker 不可用 / 容器起不来 / 被 disabled）"
    exit 1
  fi
  if grep -qE '<failure|<error' "$report"; then
    echo "RED  $fqcn  出现 failure/error（mvn 应已失败，这里再判一次）"
    exit 1
  fi
  echo "ok   $fqcn  testcases=$n"
  found=$((found + 1))
done < <(find services -path '*/src/test/java/*' -name '*Test.java' | sort)

# 护栏：扫描逻辑自己失效时不能「零发现即通过」（否则判据恒真）。
if [ "$found" -lt 1 ]; then
  echo "RED  一个带 @Testcontainers 的 *Test.java 都没扫到 ⇒ 扫描逻辑失效（源码里明明有）"
  exit 1
fi

# 权限漂移守卫不带 @Testcontainers，但它是 RBAC 的核心判据，单独钉住。
report=services/trade-service/target/surefire-reports/TEST-com.aicabinet.trade.auth.PermissionCodeDriftTest.xml
if [ ! -f "$report" ]; then
  echo "RED  PermissionCodeDriftTest 没有 surefire 报告"
  exit 1
fi
n=$(grep -o '<testcase ' "$report" | wc -l || true)
if [ "$n" -lt 1 ]; then
  echo "RED  PermissionCodeDriftTest testcases=0 ⇒ 静默跳过"
  exit 1
fi
if grep -qE '<failure|<error' "$report"; then
  echo "RED  PermissionCodeDriftTest 出现 failure/error"
  exit 1
fi
echo "ok   com.aicabinet.trade.auth.PermissionCodeDriftTest  testcases=$n"

echo "Docker-backed 测试守卫通过：$found 个 @Testcontainers 类真跑"
