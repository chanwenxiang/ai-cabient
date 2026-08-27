#!/usr/bin/env python3
import base64, json, re, urllib.request
from pathlib import Path

token = None
for line in (Path(__file__).resolve().parents[1] / "infra/.env").read_text(encoding="utf-8").splitlines():
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
for i in sorted(issues, key=lambda x: -int(pat.search(x["message"]).group(1)) if pat.search(x["message"]) else 0):
    m = pat.search(i["message"])
    c = m.group(1) if m else "?"
    fname = i["component"].split(":")[-1].split("/")[-1]
    print(f"{c:>3} L{i['line']:>4} {fname}")
