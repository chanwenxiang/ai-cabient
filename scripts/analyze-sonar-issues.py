#!/usr/bin/env python3
"""Summarize open SonarQube issues by rule, type, and quality impact."""
import argparse
import base64
import json
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_token() -> str:
    for line in (ROOT / "infra/.env").read_text(encoding="utf-8").splitlines():
        if line.startswith("SONAR_TOKEN="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("SONAR_TOKEN not found in infra/.env")


def fetch_issues(host: str, token: str, project: str, branch: str, rule: str | None) -> list[dict]:
    auth = base64.b64encode(f"{token}:".encode()).decode()
    headers = {"Authorization": f"Basic {auth}"}
    issues: list[dict] = []
    page = 1
    while True:
        params = {
            "componentKeys": project,
            "statuses": "OPEN",
            "branch": branch,
            "ps": "500",
            "p": str(page),
        }
        if rule:
            params["rules"] = rule
        url = f"{host}/api/issues/search?{urllib.parse.urlencode(params)}"
        req = urllib.request.Request(url, headers=headers)
        data = json.load(urllib.request.urlopen(req))
        issues.extend(data["issues"])
        if len(issues) >= data["total"]:
            break
        page += 1
    return issues


def fetch_facets(host: str, token: str, project: str, branch: str) -> dict[str, Counter]:
    auth = base64.b64encode(f"{token}:".encode()).decode()
    headers = {"Authorization": f"Basic {auth}"}
    facets = "types,severities,rules,impactSoftwareQualities"
    params = urllib.parse.urlencode({
        "componentKeys": project,
        "statuses": "OPEN",
        "branch": branch,
        "facets": facets,
        "ps": "1",
    })
    url = f"{host}/api/issues/search?{params}"
    req = urllib.request.Request(url, headers=headers)
    data = json.load(urllib.request.urlopen(req))
    out: dict[str, Counter] = {}
    for facet in data.get("facets", []):
        out[facet["property"]] = Counter(
            {v["val"]: v["count"] for v in facet["values"] if v["count"] > 0}
        )
    out["_total"] = Counter({"OPEN": data["total"]})
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description="Summarize SonarQube open issues")
    parser.add_argument("--host", default="http://localhost:19002")
    parser.add_argument("--project", default="ai-cabinet-dev")
    parser.add_argument("--branch", default="dev")
    parser.add_argument("--rule", help="Filter to a single rule key, e.g. java:S1192")
    parser.add_argument("--top", type=int, default=25, help="Top N rules to print")
    args = parser.parse_args()

    token = load_token()
    facets = fetch_facets(args.host, token, args.project, args.branch)
    total = facets["_total"]["OPEN"]
    print(f"Project: {args.project}  branch: {args.branch}")
    print(f"Total OPEN issues: {total}")
    print()

    print("By type:")
    for t, c in facets.get("types", Counter()).most_common():
        print(f"  {c:4} {t}")
    print()

    print("By impact:")
    for q, c in facets.get("impactSoftwareQualities", Counter()).most_common():
        print(f"  {c:4} {q}")
    print()

    print("By severity:")
    for s, c in facets.get("severities", Counter()).most_common():
        print(f"  {c:4} {s}")
    print()

    print(f"Top {args.top} rules:")
    for rule, c in facets.get("rules", Counter()).most_common(args.top):
        print(f"  {c:4} {rule}")
    print()

    if args.rule:
        issues = fetch_issues(args.host, token, args.project, args.branch, args.rule)
        print(f"Details for {args.rule} ({len(issues)}):")
        by_file = Counter(i["component"].split(":")[-1].split("/")[-1] for i in issues)
        for fname, c in by_file.most_common(15):
            print(f"  {c:3} {fname}")
        print()
        for i in sorted(issues, key=lambda x: (x["component"], x.get("line", 0)))[:30]:
            line = i.get("line", "?")
            fname = i["component"].split(":")[-1].split("/")[-1]
            msg = i["message"][:80].replace("\n", " ")
            print(f"  L{line:4} {fname}: {msg}")


if __name__ == "__main__":
    main()
