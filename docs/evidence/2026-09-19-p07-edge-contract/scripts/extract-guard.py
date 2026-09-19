import sys, io, yaml, pathlib
p = pathlib.Path(".github/workflows/ci.yml")
doc = yaml.safe_load(p.read_text(encoding="utf-8"))
job = doc["jobs"]["integration"]
step = [s for s in job["steps"] if s.get("name","").startswith("Verify integration tests ran")]
assert len(step) == 1, len(step)
run = step[0]["run"]
out = pathlib.Path(".tmp/p07-guard.sh")
out.write_text(run, encoding="utf-8", newline="\n")
print("YAML OK  steps=%d  guard_chars=%d" % (len(job["steps"]), len(run)))
