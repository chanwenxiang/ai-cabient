#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""OpenAPI / 后端控制器 / 客户端字面路径的**三方漂移**分析（只读，离线可跑）。

用法：
    python scripts/devops/openapi-drift.py

回答三个问题（各自列路径 + 出处）：
  1. 客户端写了 `/api/v2/...` 但**后端控制器里没有** → 打错的路径 / 已删接口；
  2. 客户端写了但**生成的 OpenAPI 里没有** → 契约未覆盖（客户端的类型是手工写的而不是生成的）；
  3. 后端控制器有但**生成的 OpenAPI 里没有** → `openapi.ts` 落后于后端（需按 CI 同路径重生成）。

⚠️ 判据是**文本比对**（把 `{xxx}` 归一成 `{param}`），不是编译期检查 ⇒ 会有别名/前缀拼接造成的
假阳性（`SKIP` 列表就是为了压掉已知的这类噪声）。**它是排查工具，不是门禁**：输出需要人判读，
所以没有退出码约定、也没接进 `check:audit-gates`。

背景：2026-09-18 补货签到改动加过 DTO 字段，`ci.yml:184`（`OPENAPI_CHECK_REGEN=1` + 要求
`generated/` 无 git diff）会因生成物落后而红 —— 排查这类漂移时用的就是本脚本。
"""
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# OpenAPI paths from generated types
openapi = (ROOT / "packages/shared-types/src/generated/openapi.ts").read_text(
    encoding="utf-8", errors="ignore"
)
oa_paths = set(re.findall(r'"(/api/v2/[^"]+)":', openapi))
oa_paths_norm = {re.sub(r"\{[^}]+\}", "{param}", p.rstrip("/")) for p in oa_paths}

# Backend controller paths
backend = defaultdict(set)
for p in (ROOT / "services/trade-service/src/main/java").rglob("*Controller.java"):
    t = p.read_text(encoding="utf-8", errors="ignore")
    bases = re.findall(r'@RequestMapping\("([^"]+)"\)', t)
    base = bases[0] if bases else ""
    for m in re.finditer(
        r"@(Get|Post|Put|Patch|Delete)Mapping(?:\(\s*(?:value\s*=\s*)?\"([^\"]*)\"[^)]*\))?",
        t,
    ):
        sub = m.group(2) or ""
        full = (base.rstrip("/") + "/" + sub.lstrip("/")).replace("//", "/").rstrip("/")
        full = re.sub(r"\{[^}]+\}", "{param}", full)
        backend[full].add(m.group(1).upper())

# Client literal paths
client_paths = defaultdict(set)
for root in [
    "clients/consumer-mp/src",
    "clients/merchant-mp/src",
    "packages/shared-api/src",
]:
    for p in (ROOT / root).rglob("*"):
        if p.suffix not in (".ts", ".vue", ".js"):
            continue
        t = p.read_text(encoding="utf-8", errors="ignore")
        for m in re.finditer(r"['`](/api/v2/[^'`\?]+)", t):
            path = m.group(1).split("?")[0]
            path = re.sub(r"\$\{[^}]+\}", "{param}", path).rstrip("/")
            client_paths[path].add(str(p.relative_to(ROOT)))

SKIP = [
    "logout",
    "dev/payment",
    "dev/mock",
    "mock",
    "springdoc",
    "ota",
    "vision",
    "cidr",
    "ad-campaign",
    "bigscreen",
    "externalnotification",
    "devicepresence",
    "merchantscope",
    "approval",
    "balancerefund",
    "/o/",
]


def match_set(path, candidates):
    for c in candidates:
        bp_re = "^" + re.escape(c).replace(r"\{param\}", "[^/]+") + "$"
        if re.match(bp_re, path):
            return c
    return None


print("=== CLIENT path not in backend ===")
for path in sorted(client_paths):
    pl = path.lower()
    if any(s in pl for s in SKIP):
        continue
    if not match_set(path, backend):
        print(path, "->", list(client_paths[path])[:1])

print("\n=== CLIENT path not in OpenAPI ===")
for path in sorted(client_paths):
    pl = path.lower()
    if any(s in pl for s in SKIP):
        continue
    if not match_set(path, oa_paths_norm):
        print(path, "->", list(client_paths[path])[:1])

print("\n=== BACKEND path not in OpenAPI (sample) ===")
count = 0
for path in sorted(backend):
    pl = path.lower()
    if any(s in pl for s in SKIP):
        continue
    if not match_set(path, oa_paths_norm):
        count += 1
        if count <= 15:
            print(path, backend[path])
print(f"... total backend-not-openapi: {count}")
