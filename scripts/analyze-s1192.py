#!/usr/bin/env python3
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
        f"componentKeys=ai-cabinet-dev&rules=java:S1192&statuses=OPEN&branch=dev&ps=500&p={page}"
    )
    r = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=headers)))
    issues.extend(r["issues"])
    if len(issues) >= r["total"]:
        break
    page += 1

pat = re.compile(r'literal "([^"]+)"')
lits = Counter()
by_file = Counter()
for i in issues:
    m = pat.search(i["message"])
    if m:
        lits[m.group(1)] += 1
    comp = i["component"].split(":")[-1]
    by_file[comp] += 1

print("Top literals:")
for lit, c in lits.most_common(30):
    print(f"  {c:3} {lit!r}")
print(f"\nTotal issues: {len(issues)}")
print("\nTop files:")
for f, c in by_file.most_common(15):
    print(f"  {c:3} {f}")
