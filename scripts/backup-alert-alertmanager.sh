#!/usr/bin/env bash
set -Eeuo pipefail

dry=0
if [ "${1:-}" = "--dry-run" ]; then
  dry=1
  shift
fi
[ "$#" -ge 1 ] || { echo "usage: $0 [--dry-run] <LEVEL> <MESSAGE>" >&2; exit 2; }

level="$1"
shift
message="$*"
payload="$(LEVEL="$level" MESSAGE="$message" python3 - <<'PY'
import datetime
import json
import os

level = os.environ["LEVEL"].upper()
severity = "critical" if level in ("CRITICAL", "ERROR", "FATAL") else "warning"
name = "BackupFailed" if severity == "critical" else "BackupWarning"
alert = {
    "labels": {
        "alertname": name,
        "severity": severity,
        "job": "backup",
        "source": "aicabinet",
    },
    "annotations": {"summary": name, "description": os.environ["MESSAGE"]},
    "startsAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
}
print(json.dumps([alert], ensure_ascii=False))
PY
)"

if [ "$dry" = 1 ]; then
  printf '%s\n' "$payload"
  exit 0
fi

curl -fsS --max-time 10 \
  -H 'Content-Type: application/json' \
  -X POST \
  --data "$payload" \
  "${ALERTMANAGER_URL:-http://127.0.0.1:19093}/api/v2/alerts" >/dev/null
