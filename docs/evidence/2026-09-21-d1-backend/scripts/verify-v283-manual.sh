#!/usr/bin/env bash
# D1 迁移 V283 的 SQL 语义 + 幂等性验证
#
# 为什么手工执行而不是只靠 `flyway migrate`：
#   容器里的 jar 是旧镜像构建的，V283 不在其中；重建镜像要几分钟。先把 SQL 本身
#   在**同一个 dev PG** 上跑两遍，用最小代价证明「语法正确 + 可重放」。
#   三处语句全部幂等（ADD COLUMN IF NOT EXISTS / ON CONFLICT DO NOTHING /
#   UPDATE ... WHERE min_recharge IS NULL），因此之后 Flyway 正式应用不会冲突。
#
# 用法：bash docs/evidence/2026-09-21-d1-backend/scripts/verify-v283-manual.sh
set -u

cd "$(dirname "$0")/../../../.." || exit 1
MIG="services/trade-service/src/main/resources/db/migration/V283__member_level_min_recharge.sql"
PSQL=(docker exec -i ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -v ON_ERROR_STOP=1)

echo "== 0. 前置：迁移文件存在 =="
test -f "$MIG" && echo "OK $MIG" || { echo "MISSING $MIG"; exit 1; }

echo
echo "== 1. 执行前状态 =="
"${PSQL[@]}" -tA -F'|' -c "select 'rows', count(*) from member_level_rule;"
"${PSQL[@]}" -tA -F'|' -c "select 'has_min_recharge', count(*) from information_schema.columns where table_name='member_level_rule' and column_name='min_recharge';"

echo
echo "== 2. 第 1 次应用 =="
"${PSQL[@]}" < "$MIG"
echo "exit=$?"

echo
echo "== 3. 中间态 =="
"${PSQL[@]}" -tA -F'|' -c "select level_code, min_spent, max_spent, min_recharge, sortorder, status from member_level_rule order by sortorder;"

echo
echo "== 4. 第 2 次应用（幂等性）=="
"${PSQL[@]}" < "$MIG"
echo "exit=$?"

echo
echo "== 5. 最终态（应与中间态逐字段一致）=="
"${PSQL[@]}" -tA -F'|' -c "select level_code, min_spent, max_spent, min_recharge, sortorder, status from member_level_rule order by sortorder;"

echo
echo "== 6. 幂等性判据：行数仍为 4、min_recharge 无 null =="
"${PSQL[@]}" -tA -F'|' -c "select 'rows', count(*) from member_level_rule;"
"${PSQL[@]}" -tA -F'|' -c "select 'null_min_recharge', count(*) from member_level_rule where min_recharge is null;"
