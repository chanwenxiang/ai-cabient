/**
 * 异步子进程工具：门禁脚本里跑 git / python / node 的统一入口。
 *
 * 🔴 为什么要这个模块（2026-10-08 实测，lessons #292）：
 * WorkBuddy 环境下 **Node 同步子进程一律失败** —— `spawnSync` / `execFileSync` /
 * `execSync` 全部返回 `status=null` + `error.code === 'EBUSY'`，且
 * `shell:true/false`、间隔重试、换命令全都复现（诊断脚本 `.tmp/diag-audit-exit*.mjs`）。
 * 根因是同步匿名管道被子进程代理接管后无法回收。
 *
 * 后果不是「脚本崩了」，而是**更坏的东西**：`status === null` 不等于 0，
 * 于是 `if (r.status !== 0)` 把每一条都判成失败 ⇒ 门禁输出「44 个全红」，
 * 但**实际一个检查都没跑成**。假红与假绿同等危险。
 *
 * ⇒ 一律走这里的异步 `spawn`（事件回收，不依赖同步管道），并且：
 *   1. 真实退出码为 0 才算成功；
 *   2. spawn 本身失败（命令不存在等）→ 记`spawnError` + status=1，**fail-closed**，
 *      绝不把「跑不起来」静默当成「没问题」；
 *   3. 附带 stdio 继承模式（inheritStdio），用于只想看输出、不需要捕获的场合。
 */
import { spawn } from 'node:child_process';

/**
 * @param {string} cmd命令
 * @param {string[]} args 参数
 * @param {object} opts
 * @param {string} [opts.cwd] 工作目录
 * @param {boolean} [opts.inheritStdio] true=直接继承父进程 stdio（不捕获输出）
 * @param {boolean} [opts.shell] true=用 shell 解析 `cmd`（**仅当 cmd 是含 `&&` 的命令行串时**；
 *   传数组参数时必须 false，否则 Windows 下引号规则不一致会踩坑）
 * @returns {Promise<{status:number, stdout:string, stderr:string, spawnError:(string|null)}>}
 *   `status` 为 0 表示成功；非 0 表示失败（**永不为 null**，避免调用方误判）
 */
export function runAsync(cmd, args = [], opts = {}) {
  const { cwd, inheritStdio = false, shell = false } = opts;
  return new Promise((resolvePromise) => {
    const child = spawn(cmd, args, {
      cwd,
      shell,
      windowsHide: true,
      stdio: inheritStdio ? 'inherit' : 'pipe'
    });
    let stdout = '';
    let stderr = '';
    child.stdout?.on('data', (d) => {
      stdout += d.toString();
    });
    child.stderr?.on('data', (d) => {
      stderr += d.toString();
    });
    child.on('error', (e) => {
      // 命令根本起不来（如 git 不可用）：判失败并带上原因，绝不静默放行
      resolvePromise({ status: 1, stdout, stderr, spawnError: e.code || e.message });
    });
    child.on('close', (code) => {
      resolvePromise({ status: code === null ? 1 : code, stdout, stderr, spawnError: null });
    });
  });
}

/**
 * 跑一条 git 命令并拿回 stdout（trim 过）。
 *
 * ⚠️ 一律 `shell:false` + 显式参数数组：**不要**用 `shell:true` 拼字符串，
 * 那会让含空格/中文的路径被二次解析，且在 Windows 上引号规则不一致。
 *
 * @returns {Promise<{ok:boolean, out:string, spawnError:(string|null)}>}
 *   `ok=false` 表示 git 不可用或命令失败；调用方**必须**据此 fail-closed，
 *   不可把「git 挂了」当成「没有变更」（那是把工具故障伪装成业务结论）。
 */
export async function gitAsync(args, opts = {}) {
  const r = await runAsync('git', args, { cwd: opts.cwd });
  if (r.spawnError) {
    return {
      ok: false,
      out: `${r.stdout}${r.stderr}spawn failed: ${r.spawnError}`,
      spawnError: r.spawnError
    };
  }
  if (r.status !== 0) {
    return { ok: false, out: r.stdout + r.stderr, spawnError: null };
  }
  return { ok: true, out: r.stdout.trim(), spawnError: null };
}
