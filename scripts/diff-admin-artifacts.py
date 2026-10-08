#!/usr/bin/env python3
"""
只读：比对「容器重建产物」与「git HEAD 里提交的产物」。
用法：python scripts/diff-admin-artifacts.py <容器产物目录>

不修改任何仓库文件（铁律：诊断脚本不得污染工作区）。
输出：仅列出有差异的文件（新增 / 删除 / 内容不同），并对内容不同的文件给出大小与哈希。
"""
import hashlib
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REL = "services/trade-service/src/main/resources/static/admin"

# 🔴 必须与 CI 的判据对齐：`git status --porcelain` **不报被 gitignore 的文件**。
#    runtime-config.json 含高德 key，.gitignore:233 明确排除 ⇒ CI 字节比对看不见它。
#    本脚本直接拿文件系统列表与 git HEAD 比，若不过滤它，每次都会报一个假「新增」。
IGNORE_REL = {"runtime-config.json"}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def is_ignored(rel):
    """rel 是相对 static/admin 的路径。走 git check-ignore 与 CI 判据同源。"""
    if rel in IGNORE_REL:
        return True
    r = subprocess.run(
        ["git", "check-ignore", "-q", "--", f"{REL}/{rel}"],
        cwd=ROOT, capture_output=True,
    )
    return r.returncode == 0


def scan(d):
    out = {}
    for dirpath, _dirs, files in os.walk(d):
        for fn in files:
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, d).replace("\\", "/")
            if is_ignored(rel):
                continue
            out[rel] = p
    return out


def head_tree():
    """列出 git HEAD 中该目录下的所有文件（含被删除/新增判定）。"""
    r = subprocess.run(
        ["git", "ls-tree", "-r", "--name-only", "HEAD", "--", REL],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if r.returncode != 0:
        print("git ls-tree 失败:", r.stderr[:300])
        return {}
    return {line[len(REL) + 1:].replace("\\", "/") for line in r.stdout.splitlines() if line.strip()}


def blob_hash(rel):
    r = subprocess.run(
        ["git", "rev-parse", f"HEAD:{REL}/{rel}"],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return r.stdout.strip() if r.returncode == 0 else None


def git_blob_bytes(rel):
    r = subprocess.run(
        ["git", "cat-file", "-p", f"HEAD:{REL}/{rel}"],
        cwd=ROOT, capture_output=True,
    )
    return r.stdout if r.returncode == 0 else None


def main():
    if len(sys.argv) < 2:
        print("用法: python scripts/diff-admin-artifacts.py <容器产物目录>")
        return 2
    fresh_dir = sys.argv[1]
    if not os.path.isdir(fresh_dir):
        print(f"目录不存在: {fresh_dir}")
        return 2

    fresh = scan(fresh_dir)
    head = head_tree()

    only_fresh = sorted(set(fresh) - head)
    only_head = sorted(head - set(fresh))
    changed = []
    for rel in sorted(set(fresh) & head):
        want = git_blob_bytes(rel)
        if want is None:
            changed.append((rel, "(无法读取 HEAD blob)"))
            continue
        got = open(fresh[rel], "rb").read()
        if hashlib.sha256(want).hexdigest() != hashlib.sha256(got).hexdigest():
            changed.append((rel, f"{len(want)}B -> {len(got)}B"))

    print(f"文件数：HEAD={len(head)}  容器重建={len(fresh)}")
    print(f"内容不同={len(changed)}  仅容器有(新增)={len(only_fresh)}  仅HEAD有(缺失)={len(only_head)}")
    if not (changed or only_fresh or only_head):
        print("\n✅ 完全一致 —— 若 CI 仍红，则差异来自构建环境而非文件内容（见下方排查提示）")
        return 0
    if changed:
        print("\n== 内容不同 ==")
        for rel, note in changed[:40]:
            print(f"  M {rel}  ({note})")
        if len(changed) > 40:
            print(f"  ... 另 {len(changed) - 40} 个")
    if only_fresh:
        print("\n== 仅容器重建有（应提交）==")
        for rel in only_fresh[:40]:
            print(f"  A {rel}")
        if len(only_fresh) > 40:
            print(f"  ... 另 {len(only_fresh) - 40} 个")
    if only_head:
        print("\n== 仅 HEAD 有（重建后消失，应删除）==")
        for rel in only_head[:40]:
            print(f"  D {rel}")
        if len(only_head) > 40:
            print(f"  ... 另 {len(only_head) - 40} 个")
    return 1


if __name__ == "__main__":
    sys.exit(main())
