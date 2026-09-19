import sys, pathlib, yaml
job = sys.argv[1]; prefix = sys.argv[2]; out = pathlib.Path(sys.argv[3])
doc = yaml.safe_load(pathlib.Path(".github/workflows/ci.yml").read_text(encoding="utf-8"))
steps = [s for s in doc["jobs"][job]["steps"] if s.get("name", "").startswith(prefix)]
assert len(steps) == 1, f"expected 1 step, got {len(steps)}"
out.write_text(steps[0]["run"], encoding="utf-8", newline="\n")
print(f"OK job={job} step={steps[0]['name']!r} chars={len(steps[0]['run'])}")
