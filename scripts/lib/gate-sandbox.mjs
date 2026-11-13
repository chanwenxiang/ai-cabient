/**
 * S8③ couplingDebt 夹具共享库：GATE_ROOT 沙箱 + 门禁 spawn。
 *
 * 用法：createSandbox() 拿沙箱句柄 → copy/copyTree 复制门禁扫描的真实文件 →
 * expectGreen(门禁, root) 跑基线 → write/copyTree 注入缺陷 → expectRed 断言红。
 *
 * 🔴 门禁自身已加 GATE_ROOT 覆写（2026-11-13 couplingDebt 批）：`process.env.GATE_ROOT`
 * 存在时 ROOT=沙箱，否则默认仓根——生产路径行为不变。
 */
import {
  mkdtempSync,
  mkdirSync,
  copyFileSync,
  readdirSync,
  statSync,
  writeFileSync
} from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runAsync } from './async-spawn.mjs';

const LIB_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

export function createSandbox() {
  const root = mkdtempSync(join(tmpdir(), 'gate-sandbox-'));
  const api = {
    root,
    /** 复制单个真实文件（保持相对路径） */
    copy(rel) {
      const dst = join(root, rel);
      mkdirSync(dirname(dst), { recursive: true });
      copyFileSync(join(LIB_ROOT, rel), dst);
    },
    /** 递归复制目录（可按扩展名过滤；跳过 target/node_modules） */
    copyTree(rel, exts) {
      const walk = (srcDir, dstDir) => {
        let entries;
        try {
          entries = readdirSync(srcDir, { withFileTypes: true });
        } catch {
          return;
        }
        for (const e of entries) {
          const src = join(srcDir, e.name);
          const dst = join(dstDir, e.name);
          if (e.isDirectory()) {
            if (['target', 'node_modules', 'static', 'dist'].includes(e.name)) continue;
            walk(src, dst);
          } else if (!exts || exts.some((x) => e.name.endsWith(x))) {
            mkdirSync(dirname(dst), { recursive: true });
            copyFileSync(src, dst);
          }
        }
      };
      walk(join(LIB_ROOT, rel), join(root, rel));
    },
    /**
     * 按文件名自动寻径复制：在源码根（services/clients/scripts/packages/common/infra）里
     * 找到每个文件名的真实位置，按原相对路径复制。适合「门禁读几十个 java 文件」的
     * 夹具——免手工枚举路径（漏一个就基线红）。
     */
    copyByFilenames(
      names,
      searchRoots = ['services', 'clients', 'scripts', 'packages', 'common', 'infra']
    ) {
      const byName = new Map();
      for (const base of searchRoots) {
        const walk = (dir) => {
          let entries;
          try {
            entries = readdirSync(join(LIB_ROOT, base, dir), { withFileTypes: true });
          } catch {
            return;
          }
          for (const e of entries) {
            const relFromBase = dir ? `${dir}/${e.name}` : e.name;
            if (e.isDirectory()) {
              if (['target', 'node_modules', 'static', 'dist'].includes(e.name)) continue;
              walk(relFromBase);
            } else if (!byName.has(e.name)) {
              byName.set(e.name, `${base}/${relFromBase}`);
            }
          }
        };
        walk('');
      }
      const missing = [];
      for (const name of names) {
        const found = byName.get(name);
        if (!found) {
          missing.push(name);
          continue;
        }
        this.copy(found);
      }
      if (missing.length) throw new Error(`沙箱寻径失败，找不到：${missing.join(', ')}`);
    },
    write(rel, content) {
      const dst = join(root, rel);
      mkdirSync(dirname(dst), { recursive: true });
      writeFileSync(dst, content);
    }
  };
  return api;
}

export async function runGate(gateFile, root) {
  return runAsync(process.execPath, [join(LIB_ROOT, 'scripts', gateFile)], {
    env: { GATE_ROOT: root }
  });
}

/** 基线必须绿（沙箱复制的是当前真实文件） */
export async function expectGreen(gateFile, root) {
  const r = await runGate(gateFile, root);
  if (r.status !== 0) {
    throw new Error(
      `${gateFile} 沙箱基线应为绿，实际 exit=${r.status}：${(r.stderr || r.stdout || '').slice(-500)}`
    );
  }
}

/** 注入缺陷后必须红，且红因包含预期关键字（证明红的是注入的那条判据） */
export async function expectRed(gateFile, root, needle) {
  const r = await runGate(gateFile, root);
  if (r.status === 0) {
    throw new Error(`${gateFile} 注入缺陷后仍绿（判据失效）`);
  }
  const out = (r.stderr || '') + (r.stdout || '');
  if (needle && !out.includes(needle)) {
    throw new Error(`${gateFile} 红因未包含「${needle}」：${out.slice(-500)}`);
  }
}
