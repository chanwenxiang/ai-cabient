# -*- coding: utf-8 -*-
"""孤儿表定策：重新取证 + 分类（P0-1 批次后 2026-09-19）。

判据口径
--------
- 建表来源：`services/trade-service/src/main/resources/db/migration/V*.sql`（**排除 target/**）。
- 代码引用：`services/**`（java/xml/yml）+ `clients/**`（ts/vue/js）+ `scripts/**`（mjs/py），
  排除 target / node_modules / dist / unpackage。按 snake_case 整词匹配。
- 写者证据（三条任一即算「有写者」）：
  * 实体 `@TableName("t")`
  * mapper XML 里出现 `insert into t` / `update t ` / `delete from t`
  * Java 源码里出现 `INSERT INTO t` / `UPDATE t ` / `DELETE FROM t`
- seed 证据：迁移脚本里出现 `INSERT INTO t`
- 读证据：迁移脚本或代码里出现 `from t` 且非写语句

输出：docs/ORPHAN_TABLE_DISPOSITION.md 的数据段 + 控制台摘要
"""
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIG = ROOT / 'services/trade-service/src/main/resources/db/migration'
SKIP = {'node_modules', 'target', 'dist', 'unpackage', '.git', 'coverage', 'build'}
CODE_DIRS = ['services', 'clients', 'scripts']
CODE_EXT = {'.java', '.xml', '.yml', '.yaml', '.ts', '.vue', '.js', '.mjs', '.py'}

cx_re = re.compile(r'CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?["`]?([a-z_][a-z0-9_]*)["`]?', re.I)


def iter_files(base, exts):
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in SKIP]
        for fn in filenames:
            if Path(fn).suffix.lower() in exts:
                yield Path(dirpath) / fn


# 1. 建表清单
tables = {}
for p in sorted(MIG.glob('V*.sql')):
    txt = p.read_text(encoding='utf-8', errors='replace')
    for m in cx_re.finditer(txt):
        tables.setdefault(m.group(1).lower(), []).append(p.name)
print(f'建表数（去重）: {len(tables)}')

# 2. 代码引用计数 + 写者/读证据
code_blob = {}
for d in CODE_DIRS:
    base = ROOT / d
    if not base.exists():
        continue
    for p in iter_files(base, CODE_EXT):
        try:
            code_blob[p] = p.read_text(encoding='utf-8', errors='replace')
        except Exception:
            pass
print(f'扫描代码文件: {len(code_blob)}')

mig_blob = {p: p.read_text(encoding='utf-8', errors='replace') for p in sorted(MIG.glob('V*.sql'))}

rows = []
for t in sorted(tables):
    tw = re.compile(r'(?<![a-z0-9_])' + re.escape(t) + r'(?![a-z0-9_])')
    ref_files = [str(p.relative_to(ROOT)) for p, s in code_blob.items() if tw.search(s)]
    ref_count = len(ref_files)

    # 实体注解有两种写法：@TableName("t") 与 @TableName(value = "t", autoResultMap = true)。
    # 用「去掉所有空白再找子串」而不是正则：避免转义地狱，且对本仓两种写法都覆盖。
    has_entity = any(
        ('@TableName("%s"' % t) in re.sub(r'\s+', '', src)
        or ("@TableName(value=\"%s\"" % t).replace("'", '"') in re.sub(r'\s+', '', src)
        or ('@TableName(\'%s\'' % t) in re.sub(r'\s+', '', src)
        for src in code_blob.values()
    )
    write_patterns = [
        re.compile(r'insert\s+into\s+' + re.escape(t) + r'\b', re.I),
        re.compile(r'update\s+' + re.escape(t) + r'\s+set\b', re.I),
        re.compile(r'delete\s+from\s+' + re.escape(t) + r'\b', re.I),
    ]
    literal_write = any(any(pat.search(s) for pat in write_patterns) for s in code_blob.values())
    # MyBatis-Plus 的 BaseMapper/BaseTradeMapper 直接提供 insert/updateById/deleteById ⇒
    # 「有实体类」本身即构成写者证据（此前只认字面量 SQL，会把 dispute_ticket 这类误判成「有读无写」）。
    has_writer = literal_write or has_entity

    seed_files = [p.name for p, s in mig_blob.items() if re.search(
        r'insert\s+into\s+' + re.escape(t) + r'\b', s, re.I)]
    read_in_mig = any(re.search(r'from\s+' + re.escape(t) + r'\b', s, re.I) for s in mig_blob.values())
    has_reader = read_in_mig or ref_count > 0

    rows.append(dict(table=t, ref_count=ref_count, ref_files=ref_files,
                     entity=has_entity, writer=has_writer, literal_write=literal_write,
                     seed=len(seed_files), seed_files=seed_files,
                     reader=has_reader, created_in=tables[t]))

zero = [r for r in rows if r['ref_count'] == 0]
print(f'\n零代码引用表: {len(zero)} / {len(rows)}')

# 分类
cat_a, cat_b = [], []
for r in zero:
    if r['seed'] > 0:
        cat_a.append(r)
    else:
        cat_b.append(r)
print(f'  A 有 seed 写入（演示/种子数据表）: {len(cat_a)}')
print(f'  B 无 seed 也无写者（完全孤立建表）: {len(cat_b)}')

print('\n--- A 类（seed-only）---')
for r in cat_a:
    print(f"  {r['table']:<40} seed={r['seed']:<3} 建表于 {','.join(r['created_in'][:2])}")
print('\n--- B 类（无 seed 无写者）---')
for r in cat_b:
    print(f"  {r['table']:<40} 建表于 {','.join(r['created_in'][:2])}")

# 有读无写
readonly = [r for r in rows if r['ref_count'] > 0 and not r['writer'] and not r['entity']]
print(f'\n--- 有引用但无写者证据（含「有读无写」断链候选）: {len(readonly)} ---')
for r in readonly[:60]:
    print(f"  {r['table']:<40} refs={r['ref_count']:<3} seed={r['seed']}")
