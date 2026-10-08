#!/usr/bin/env python3
"""为容器构建打真正的 tar 包（Git Bash 的 tar 输出不可靠）。

🔴 为什么不用 `tar -cf -`：
   Git Bash 的 tar 会在管道里产生 GNU/pax 混合格式，容器内 GNU tar 报
   "This does not look like a tar archive"（实测 2026-10-08）。
   Python 的 tarfile 生成的是标准 POSIX ustar，容器内一定能解开。

用法：python scripts/pack-admin-src.py <输出tar> [排除目录...]
"""
import os
import sys
import tarfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 只送构建 admin 必需的部分：workspace 清单 + 共享包 + admin-vue 自身
INCLUDE = [
    'package.json',
    'pnpm-workspace.yaml',
    'pnpm-lock.yaml',
    '.npmrc',
    'packages',
    'clients/admin-vue',
]
EXCLUDE_DIRS = {'node_modules', 'dist', '.git', '.vite', '.cache'}
EXCLUDE_SUFFIX = ('.log', '.tsbuildinfo', '.abtmp')


def rel_to_root(path: str) -> str:
    """相对仓库根的 POSIX 路径（tar 内部统一用 '/'，与平台无关）。"""
    return os.path.relpath(path, ROOT).replace('\\', '/')


def add(tar: tarfile.TarFile, path: str) -> None:
    """把 path 加入 tar（目录则递归）。

    🔴 arcname **一律**必须是相对 ROOT 的路径。
       实测踩坑：单文件分支曾写 `arcname=path`（绝对路径），tarfile 于是产出
       "ai-generated code/ai-cabinet/package.json" —— 在含空格路径下只留了尾部若干段。
       容器解出来多一层目录 ⇒ pnpm "No projects found in /w"。
       自校验（必需条目 + 顶层白名单）就是为了拦这类退化。
    """
    if os.path.isfile(path):
        tar.add(path, arcname=rel_to_root(path), recursive=False)
        return
    for dirpath, dirnames, filenames in os.walk(path):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
        rel_dir = os.path.relpath(dirpath, ROOT).replace('\\', '/')
        if rel_dir == '.':
            rel_dir = ''
        # 目录条目（保证空目录也能被创建）
        tar.add(dirpath, arcname=rel_dir, recursive=False)
        for fn in filenames:
            if fn.endswith(EXCLUDE_SUFFIX):
                continue
            full = os.path.join(dirpath, fn)
            arc = os.path.relpath(full, ROOT).replace('\\', '/')
            tar.add(full, arcname=arc, recursive=False)


def main() -> int:
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, '.tmp', 'admin-src.tar')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    if os.path.exists(out):
        os.remove(out)

    missing = [p for p in INCLUDE if not os.path.exists(os.path.join(ROOT, p))]
    if missing:
        print(f'❌ 缺少必需路径：{missing}', file=sys.stderr)
        return 1

    # format=GNU_FORMAT 最兼容容器内 GNU tar；不用 gzip（省一次 CPU）
    with tarfile.open(out, 'w', format=tarfile.GNU_FORMAT) as tar:
        for p in INCLUDE:
            add(tar, os.path.join(ROOT, p))

    size = os.path.getsize(out)

    # 🔴 自校验（不能只信「tarfile 写成功了」）：arcname 必须全部是仓库内相对路径。
    #    实测踩坑：目录条目用 os.walk 的 dirpath 直接算 arcname 时，
    #    在含空格的路径（D:\ai-generated code\...）下会退化成只留最后一层，
    #    产出 "ai-generated code/ai-cabinet/package.json" 这种**带上级目录**的 arcname
    #    ⇒ 容器里解出来多一层目录，pnpm 报 "No projects found"。
    with tarfile.open(out, 'r') as t:
        names = t.getnames()
    bad = [n for n in names if n.startswith(('/', '..')) or ':' in n or '\\' in n]
    if bad:
        print(f'❌ 归档内arcname 异常（前 5 条）：{bad[:5]}', file=sys.stderr)
        return 1
    required = {'package.json', 'pnpm-workspace.yaml', 'pnpm-lock.yaml'}
    missing = required - set(names)
    if missing:
        print(f'❌ 归档缺少必需条目：{missing}', file=sys.stderr)
        return 1
    # 顶层必须只有这 4 个 + packages/clients
    tops = {n.split('/')[0] for n in names}
    unexpected = tops - {'package.json', 'pnpm-workspace.yaml', 'pnpm-lock.yaml', '.npmrc', 'packages', 'clients'}
    if unexpected:
        print(f'❌ 归档顶层出现意外目录（说明 arcname 退化了）：{sorted(unexpected)[:5]}', file=sys.stderr)
        return 1

    print(f'✅ 已打包 {out}（{size/1024/1024:.1f} MB, {len(names)} 条目，顶层 {sorted(tops)}）')
    return 0


if __name__ == '__main__':
    sys.exit(main())