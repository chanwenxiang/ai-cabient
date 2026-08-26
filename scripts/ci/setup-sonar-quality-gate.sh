#!/usr/bin/env bash
# 幂等创建/更新 Sonar「AI Cabinet」质量门禁（GHA self-hosted runner / Linux CI 用）
set -euo pipefail

SONAR_HOST_URL="${SONAR_HOST_URL:-http://sonarqube:9000}"
SONAR_TOKEN="${SONAR_TOKEN:?SONAR_TOKEN is required}"

BASE="${SONAR_HOST_URL%/}"
AUTH="Authorization: Bearer ${SONAR_TOKEN}"

post_form() {
  local path="$1"
  shift
  curl -sfS -X POST -H "$AUTH" "$BASE$path" "$@"
}

echo "==> Ensure quality gate 'AI Cabinet'"
if ! curl -sfS -H "$AUTH" "$BASE/api/qualitygates/list" | grep -q '"name":"AI Cabinet"'; then
  post_form "/api/qualitygates/create" -d "name=AI Cabinet"
fi

while read -r id; do
  [ -n "$id" ] && post_form "/api/qualitygates/delete_condition" -d "id=$id" || true
done < <(curl -sfS -H "$AUTH" "$BASE/api/qualitygates/show?name=AI%20Cabinet" \
  | grep -o '"id":[0-9]*' | cut -d: -f2)

post_form "/api/qualitygates/create_condition" -d "gateName=AI Cabinet" -d "metric=new_vulnerabilities" -d "op=GT" -d "error=0"
post_form "/api/qualitygates/create_condition" -d "gateName=AI Cabinet" -d "metric=new_blocker_violations" -d "op=GT" -d "error=0"
post_form "/api/qualitygates/create_condition" -d "gateName=AI Cabinet" -d "metric=new_security_hotspots_reviewed" -d "op=LT" -d "error=100"
post_form "/api/qualitygates/create_condition" -d "gateName=AI Cabinet" -d "metric=new_duplicated_lines_density" -d "op=GT" -d "error=3"
post_form "/api/qualitygates/create_condition" -d "gateName=AI Cabinet" -d "metric=new_coverage" -d "op=LT" -d "error=20"

for project in ai-cabinet-dev ai-cabinet-main; do
  post_form "/api/qualitygates/select" -d "projectKey=$project" -d "gateName=AI Cabinet" || true
  echo "bound $project"
done

echo "Quality gate ready: AI Cabinet (vuln=0, blocker=0, hotspots=100%, dup<=3%, coverage>=20%)"
