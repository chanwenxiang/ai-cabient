#!/usr/bin/env python3
"""List open java:S3776 (cognitive complexity) issues from local SonarQube."""
import base64
import json
import re
import urllib.request
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
token = None
for line in (ROOT / "infra/.env").read_text(encoding="utf-8").splitlines():
    if line.startswith("SONAR_TOKEN="):
        token = line.split("=", 1)[1].strip()
        break
auth = base64.b64encode(f"{token}:".encode()).decode()
headers = {"Authorization": f"Basic {auth}"}

issues = []
page = 1
while True:
    url = (
        "http://localhost:19002/api/issues/search?"
        f"componentKeys=ai-cabinet-dev&rules=java:S3776&statuses=OPEN&branch=dev&ps=500&p={page}"
    )
    r = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=headers)))
    issues.extend(r["issues"])
    if len(issues) >= r["total"]:
        break
    page += 1

pat = re.compile(r"from (\d+) to")
by_file = Counter(i["component"].split(":")[-1] for i in issues)
print(f"Total S3776: {len(issues)}")
print("\nTop files:")
for f, c in by_file.most_common(15):
    print(f"  {c:3} {f}")

quick = []
for i in issues:
    m = pat.search(i["message"])
    if m and 16 <= int(m.group(1)) <= 20:
        quick.append((int(m.group(1)), i["line"], i["component"].split(":")[-1].split("/")[-1]))

print(f"\nQuick wins (16-20 complexity): {len(quick)}")
for c, line, fname in sorted(quick):
    print(f"  {c:2} L{line:4} {fname}")
