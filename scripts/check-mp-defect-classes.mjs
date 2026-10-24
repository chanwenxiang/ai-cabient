#!/usr/bin/env node
/**
 * 小程序端「复现型缺陷类」静态门禁 —— 守着那些**修过不止一次**的形态。
 *
 * 背景（2026-10-23 第五轮审计复盘）：同一缺陷类在五轮审计里反复出货，根因是
 * 每轮只修「实例」不修「模式」——分页在途死锁修了 orders/splits 两处，
 * 第三轮 disputes 又漏；切换上下文写穿修了 tax，ops-config 同型再现；
 * 组件内页面级 onHide 在 mp-weixin 空转踩了两次。**修实例不入口岸，
 * 第 N+1 次出现只是时间问题**，故把每个类的「结构特征」固化成扫描判据。
 *
 * 2026-10-24 二轮扩展（回应用户：「还有哪些复现过的缺陷类没落地门禁」）：
 * 把五轮审计 + 10-23 收尾批次里**其余复现类**全部落成判据。扩展门禁首跑即
 * 抓到 13 处上几轮没修干净的同类（见各类 why），修净后才绿——再次证明
 * 「修实例不修模式」漏的是系统性的一角，不是个别手误。
 *
 * 2026-10-24 三轮扩展（孤产素材清理险情）：清理 static 孤产时按文件名 grep
 * 判死图标，漏掉 menuIcon('x') 动态拼接形态（详见 P 类）⇒ 新增 P 类存在性判据。
 *
 * ── 判据全部取自本仓真实事故，非假想 ─────────────────────────────────────
 *   A. 分页在途状态死锁：文件置位 `loadingMore.value = true`，却没有任何
 *      **无条件**复位（所有复位都挂在 `seq === loadSeq` 条件上）——load() 递增
 *      seq 后迟到 loadMore 的 finally 被跳过，loadingMore 永真，分页死锁。
 *      判据：必须存在一行 `loadingMore.value = false` 且该行不含 seq 相等判断。
 *      【orders/splits/disputes 三连，第四轮回归一次】
 *   B. 组件内注册页面级生命周期：非 pages/ 文件 import onShow/onHide/onUnload
 *      却不配对 onPageShow/onPageHide（mp-weixin 组件实例里页面级钩子永不触发，
 *      本仓 use-auto-refresh.ts 注释有实测记录）；onUnload 必须同时 import
 *      vue 的 onUnmounted 兜底卸载。【banner 埋点空转踩两次】
 *   C. 切换上下文不清受保护状态：文件含 `onMerchantPick|onMerchantChange|
 *      switchTaxMerchant|switchMerchant` 切换函数，却不在任何切换相关函数体里
 *      同步清空数据 ref——异步加载完成前的保存窗口会把旧上下文数据写进新上下文
 *      （跨商户写穿，第五轮 M-2/M-3 实锤）。【tax/ops-config/WalletPage 三处】
 *   D. 原始平台 errMsg 直出为用户可见错误：`new Error(err?.errMsg…)` /
 *      `showError(err?.errMsg…)`——绕过本地化封装，把 `requestPayment:fail cancel`
 *      这类英文平台串直接弹给用户。【支付取消英文串】
 *   E. 端点路径裸插值：endpoints.ts 路径段 `${param}` 未包 encodeURIComponent
 *      （查询串位置 `?x=${n}` 豁免——数字参数无注入面）。【endpoints 全端】
 *   F. 在途门闩三类形态（防重/防并发，第四轮feedback双提交、第二轮M1/M2、
 *      第三轮拍照上传、第四轮盘点上传连续出货）：
 *      F1 只写不读的 ref 门闩：本文件 `const x = ref(false)` 且 `x.value = true`，
 *         但全文再无任何读取 ⇒ 防重实际不存在（M1 row.saving 同族）。
 *      F2 只写不读的属性门闩：`.saving = true`（ submitting/inFlight/uploading/
 *         loading 同族）赋值后全文无读取。
 *      F3 门闩晚于鉴权 await：函数里 `await ensure*Auth|ensureLogin|checkLogin`
 *         出现在 `guard.value = true` **之前**——鉴权握手窗口内可二次提交
 *         （feedback.vue 双提交原型；修法=门闩前置、失败分支复位）。
 *      F4 选图/上传入口无门闩：函数体调用 uni.chooseImage/chooseMedia/
 *         safeChooseImage* 但在调用点之前没有任何在途判官/置位（*Once 内联
 *         单发函数豁免——重试包装器有意如此）。【拍照上传连点破「最多5张」】
 *   G. 静默截断：v-for 渲染的 computed 里 `.slice(0, N≥10)` 而文件没有任何
 *      「仅展示前 N 项/完整数据」类提示——用户以为看到的是全量。
 *      【销售明细 slice(0,12)、points/messages 100 条两连】
 *   H. 翻页污染：loadMore/fetchPage 函数体直接 `list.value = list.value.concat(…)`
 *      追加，却没有任何 seq/超期守卫——load() 在途时切 tab/自动刷新，迟到旧页
 *      会 append 到新列表上。【tally 翻页污染，第五轮】
 *   I. Blob URL 不回收：文件有 `URL.createObjectURL` 而无 `revokeObjectURL`。
 *      【verify 签约 blob 泄漏，第四轮】
 *   J. 死导入：import 的命名绑定（含 `type`/别名）在文件内再无任何引用。
 *      【video 死 import、home.vue 未用解构、consumer-api 死导出三连】
 *   K. 刷新失败即登出：`handleUnauthorized(` 调用处上下文没有任何鉴权状态判据
 *      （isMpAuthFailure/isXxxLoggedIn/401/403/AUTH_EXPIRED）——弱网超时会被
 *      当成会话失效误杀。【第四轮留置项，已修；门禁防复辟】
 *   L. 硬编码版本号：代码里出现 `v1.2.3` 字面量（应由构建注入）。
 *      【第二轮 L4 版本号硬编码】
 *   M. 订阅消息模板 ID 硬编码：≥38 位不透明 ID 字面量——模板 ID 必须走后端
 *      公开配置下发，硬编码=后端一改前端 100% 静默失效。
 *      【第五轮 consumer 订阅消息全静默】
 *   N. 失败负缓存钉死：catch 回调里把 `cache = {}`/`= []`（真值空表）写进缓存，
 *      且文件没有 TTL/负缓存时间戳机制——一次网络抖动把开关永久钉死在失败态。
 *      【10-23 consumer feature-flags 已修；商户端同型未修，本次扩展门禁抓到】
 *   O. 无界缓存：模块级 `const xCache = new Map()` 而文件没有任何上限/驱逐机制。
 *      【争议证据缓存无上限，第四轮留置项】
 *   P. 引用静态资产存在性（悬空引用）：代码/JSON 里字面引用 `/static/<path>.<ext>`、
 *      `menuIcon('name')` 参数、动态 `menuIcon(非字面量)` 调用方的同文件 icon:/mark:
 *      字面量——三者指向的资产必须存在。删孤产素材时按「文件名 grep 零引用」判死，
 *      漏掉 `menuIcon('member')` 这类动态拼接形态，删完静态还在、运行时全 404
 *      （2026-10-24 实证：28+21 个菜单图标误判，人工抽查才拦下）。【本次新增】
 *   Q. 订阅调用不在 tap 上下文：requestSubscribeMessage 族调用必须从模板
 *      @click/@tap 可达（含一级被调者），且宿主体内调用点之前不得有 await
 *      （手势过期同样静默失败）。2026-10-24 三处同族收口：账单订阅 finishSession
 *      异步链（留置②）+ 两处争议订阅（DISPUTED 分支/onShow 恢复路径）。【本次新增】
 *
 * 注释剥离（铁律）：JS/TS 块注释与行注释、Vue HTML 注释一律**等长 blank 成空白
 * 且保留换行**——注释里描述缺陷的 prose 不得命中判据，且报出的 file:line
 * 仍可用 `grep -n` 核对（不塌行）。
 *
 * 防恒真/恒假：扫描文件数 < MIN_FILES ⇒ 红；SELFTEST_CASES 对每个类用构造
 * 样本验证「该命中的命中、不该命中的不命中」。
 *
 *   node scripts/check-mp-defect-classes.mjs
 */
import { existsSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-mp-defect-classes]';

const MIN_FILES = 60;

const APPS = [
  { name: 'consumer-mp', src: 'clients/consumer-mp/src' },
  { name: 'merchant-mp', src: 'clients/merchant-mp/src' }
];

const SKIP_DIR = new Set(['node_modules', 'dist', 'unpackage', '.git', 'coverage', '.tmp']);

function walk(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out; // 目录在扫描中途被移除（并发会话/构建清理）：跳过，不炸整趟扫描
  }
  for (const name of entries) {
    if (SKIP_DIR.has(name)) continue; // 编译产物/依赖树混进扫描面会把判据打穿（见 SKIP_DIR 定义）
    const p = join(dir, name);
    let st;
    try {
      st = statSync(p);
    } catch {
      continue; // TOCTOU：readdirSync 列到后、statSync 前被删（preflight 实证会 ENOENT 崩）
    }
    if (st.isDirectory()) walk(p, out);
    else if (/\.(vue|ts)$/.test(name) && !name.endsWith('.d.ts')) out.push(p);
  }
  return out;
}

/**
 * 等长 blank 注释（保留换行——多行 HTML 注释若把 \n 也换成空格会塌行，
 * 报出的 file:line 将整体上移，recharge.vue 实测偏移 28 行）：
 * - HTML 注释 <!-- ... -->
 * - JS 块注释 /* ... *​/（不跨字符串，实现从简：门禁判据均为代码形态，
 *   字符串里出现判据词的概率为零，且注释 prose 已被 blank）
 * - 行注释 // ...（仅整行或以空白开头，避免误伤 URL 中的 //）
 */
function stripComments(src) {
  let out = src.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '));
  out = out.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
  out = out.replace(/(^|\s)\/\/[^\n]*/g, (m, p1) => p1 + ' '.repeat(m.length - p1.length));
  return out;
}

function linesOf(code) {
  return code.split(/\r?\n/);
}

/** TOCTOU 安全读：文件在「收集→读取」间隙被删（并发会话/构建清理，preflight 实证）时返回 null，调用方跳过该文件。 */
function readSource(f) {
  try {
    return stripComments(readFileSync(f, 'utf8'));
  } catch {
    return null;
  }
}

function findLine(code, re) {
  const lines = linesOf(code);
  for (let i = 0; i < lines.length; i++) if (re.test(lines[i])) return i + 1;
  return -1;
}

/** 抽取 import ... from '@dcloudio/uni-app' 的命名导入集合（按文件）。 */
function uniNamedImports(code) {
  const names = new Set();
  const re = /import\s*\{([^}]*)\}\s*from\s*'@dcloudio\/uni-app'/g;
  let m;
  while ((m = re.exec(code))) {
    for (const part of m[1].split(',')) {
      const n = part
        .trim()
        .split(/\s+as\s+/)[0]
        .trim();
      if (n) names.add(n);
    }
  }
  return names;
}

/** 取函数体（从 `function name(` 所在行到配对的 `}`）。
 *  支持 export / async / 嵌套缩进声明；括号从声明行起配对，遇到自身收尾即止。 */
function functionBodies(code, nameRe) {
  const lines = linesOf(code);
  const bodies = [];
  for (let i = 0; i < lines.length; i++) {
    const fm = lines[i].match(
      new RegExp(`^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(${nameRe})\\s*\\(`)
    );
    if (!fm) continue;
    let depth = 0;
    let started = false;
    const body = [];
    for (let j = i; j < lines.length; j++) {
      body.push(lines[j]);
      for (const ch of lines[j]) {
        if (ch === '{') {
          depth++;
          started = true;
        }
        if (ch === '}') depth--;
      }
      if (started && depth <= 0) break;
    }
    bodies.push({ name: fm[1], line: i + 1, text: body.join('\n') });
  }
  return bodies;
}

/** 所有函数体（不限名字），行号从 1 起。 */
function allFunctionBodies(code) {
  return functionBodies(code, '\\w+');
}

/**
 * computed 体（圆括号配对，从 `const NAME = computed(` 到配平的 `)`）。
 * 行式正则会被行内 `)` 提前截断（recharge.vue visibleRecords 实证），必须配对扫描。
 */
function computedBodies(code) {
  const lines = linesOf(code);
  const out = [];
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^\s*(?:const|let)\s+(\w+)\s*(?::[^=]+)?=\s*computed\(/);
    if (!m) continue;
    // 从 declared 行起按圆括号深度配对（字符串里的括号从简：判据形态不含）
    let depth = 0;
    let started = false;
    const body = [];
    for (let j = i; j < lines.length; j++) {
      body.push(lines[j]);
      for (const ch of lines[j]) {
        if (ch === '(') {
          depth++;
          started = true;
        }
        if (ch === ')') depth--;
      }
      if (started && depth <= 0) break;
    }
    out.push({ name: m[1], line: i + 1, text: body.join('\n') });
  }
  return out;
}

/** 门闩类标识符（防重/防并发/在途）的名字形态。 */
const GUARD_NAME =
  '(?:\\w*(?:[Ii]nFlight|inflight|[Aa]dding|[Uu]ploading|[Ss]aving|[Ss]ubmitting|[Ll]oading|[Pp]icking|busy)\\w*)';
const AUTH_AWAIT_RE = /await\s+(?:ensure\w*Auth|ensure\w*Login|ensureLogin|checkLogin)\b/;
const NOTICE_RE =
  /仅显示|仅展示|前\s*\d+\s*项|完整数据|完整记录|完整列表|查看更多|加载更多|导出|截断/;
const AUTH_STATUS_RE =
  /isMpAuthFailure|isConsumerLoggedIn|isMerchantLoggedIn|401|403|AUTH_EXPIRED|AuthExpired|authFailure/i;

const problems = [];
function report(cls, file, line, why) {
  problems.push({ cls, file: relative(root, file), line, why });
}

// ── A. 分页在途状态死锁 ───────────────────────────────────────────────────
function checkPagination(files) {
  for (const f of files) {
    if (!f.endsWith('.vue')) continue;
    const code = readSource(f);
    if (code === null) continue;
    const setLine = findLine(code, /loadingMore\.value\s*=\s*true/);
    if (setLine < 0) continue;
    const lines = linesOf(code);
    const hasUnconditionalReset = lines.some(
      (l) => /loadingMore\.value\s*=\s*false/.test(l) && !/(===|!==)\s*\w*[Ss]eq/.test(l)
    );
    if (!hasUnconditionalReset) {
      report(
        'A',
        f,
        setLine,
        'loadingMore 只有 seq 条件复位：load()/切 tab 使迟到 loadMore 的 finally 跳过复位 ⇒ 分页死锁。修法：load() 入口加无条件 loadingMore.value = false'
      );
    }
  }
}

// ── B. 组件内页面级生命周期钩子 ───────────────────────────────────────────
function checkComponentHooks(files) {
  for (const f of files) {
    const rel = relative(root, f).replace(/\\/g, '/');
    // pages/ 下的页面实例注册页面级钩子合法；App.vue 是应用级生命周期，合法
    if (rel.includes('/src/pages/') || rel.endsWith('/src/App.vue')) continue;
    const code = readSource(f);
    if (code === null) continue;
    const names = uniNamedImports(code);
    if (names.size === 0) continue;
    if (names.has('onShow') && !names.has('onPageShow')) {
      report(
        'B',
        f,
        findLine(code, /import\s*\{[^}]*onShow[^}]*\}\s*from\s*'@dcloudio\/uni-app'/),
        '组件 import onShow 但无 onPageShow：mp-weixin 组件实例里 onShow 永不触发（use-auto-refresh.ts 实测结论）。双注册或只用 onPageShow'
      );
    }
    if (names.has('onHide') && !names.has('onPageHide')) {
      report(
        'B',
        f,
        findLine(code, /import\s*\{[^}]*onHide[^}]*\}\s*from\s*'@dcloudio\/uni-app'/),
        '组件 import onHide 但无 onPageHide：组件不随页面隐藏卸载，页面级 onHide 在组件里空转。双注册或只用 onPageHide'
      );
    }
    if (names.has('onUnload') && !/import\s*\{[^}]*onUnmounted[^}]*\}\s*from\s*'vue'/.test(code)) {
      report(
        'B',
        f,
        findLine(code, /import\s*\{[^}]*onUnload[^}]*\}\s*from\s*'@dcloudio\/uni-app'/),
        '组件 import onUnload 但无 vue onUnmounted 兜底：组件销毁不走页面 onUnload。卸载清理用 onUnmounted'
      );
    }
  }
}

// ── C. 切换上下文不清受保护状态 ───────────────────────────────────────────
const SWITCH_FN_RE = 'onMerchantPick|onMerchantChange|switchTaxMerchant|switchMerchant';
function checkContextSwitch(files) {
  for (const f of files) {
    const code = readSource(f);
    if (code === null) continue;
    if (!new RegExp(`function\\s+(?:${SWITCH_FN_RE})\\s*\\(`).test(code)) continue;
    const bodies = functionBodies(code, SWITCH_FN_RE);
    // 任何受保护状态的清空都算（不限名字）：ref 置空/置空数组/空表单工厂。
    // 原判据只认 taxForm|cfg|form|list|overview|rows|items 六个名字，
    // WalletPage 靠 overview 才过的关、pendingRequestNo 这类名字不认——
    // 别的 AI 新写 withdrrawForm 之类的切换就会误红，故放宽为「有清空即过」。
    const clears = bodies.filter((b) =>
      /\w+\.value\s*=\s*(?:null|''|\[\]|empty\w*\(|void 0)/.test(b.text)
    );
    if (clears.length === 0) {
      report(
        'C',
        f,
        bodies[0]?.line ?? 1,
        '商户/上下文切换函数未同步清空受保护数据 ref：异步加载完成前的保存窗口会把旧上下文数据写进新上下文（跨商户写穿）。修法：切换时立即清空+加载门闩'
      );
    }
  }
}

// ── D. 原始平台 errMsg 直出 ───────────────────────────────────────────────
function checkRawErrMsg(files) {
  const re =
    /new Error\(\s*(?:err\??\.errMsg|errMsg|String\(\s*err\b)|showError\(\s*(?:err\??\.errMsg|errMsg)\b/;
  for (const f of files) {
    const code = readSource(f);
    if (code === null) continue;
    const line = findLine(code, re);
    if (line > 0) {
      report(
        'D',
        f,
        line,
        '原始平台 errMsg 直接构造用户可见错误：绕过本地化封装（requestPayment:fail cancel 类英文串会弹给用户）。走 formatMpRequestError/isMpAuthFailure 分流'
      );
    }
  }
}

// ── E. 端点路径裸插值 ─────────────────────────────────────────────────────
function checkEndpointInterpolation(files) {
  for (const f of files) {
    if (!/endpoints\.ts$/.test(f)) continue;
    const code = readSource(f);
    if (code === null) continue;
    const lines = linesOf(code);
    for (let i = 0; i < lines.length; i++) {
      const l = lines[i];
      // 只查反引号模板里的 ${...}；前一个非空字符是 `/`（路径段）且未包 encodeURIComponent ⇒ 红
      const re = /(\/\s*)\$\{([^}]*)\}/g;
      let m;
      while ((m = re.exec(l))) {
        const expr = m[2].trim();
        if (expr.startsWith('encodeURIComponent')) continue;
        // API_PREFIX 之类的常量前缀不出现在路径段中（前面是反引号），此处天然不命中
        report(
          'E',
          f,
          i + 1,
          `端点路径段裸插值 \${${expr}}：未包 encodeURIComponent（查询串 ?x=${'${n}'} 豁免——数字参数无注入面）`
        );
      }
    }
  }
}

// ── F1. 只写不读的 ref 门闩 ──────────────────────────────────────────────
function checkWriteOnlyRefGuard(files) {
  for (const f of files) {
    const code = readSource(f);
    if (code === null) continue;
    const declRe = new RegExp(`\\b(?:const|let)\\s+(${GUARD_NAME})\\s*=\\s*ref\\(`, 'g');
    let m;
    while ((m = declRe.exec(code))) {
      const name = m[1];
      if (!new RegExp(`\\b${name}\\.value\\s*=\\s*true`).test(code)) continue;
      // 读取次数：声明行本身计 1（`const x = ref(false)` 不是 .value = 形态）；
      // 只声明+赋值、没有任何条件/绑定读取 ⇒ 防重实际不存在
      const reads = (code.match(new RegExp(`\\b${name}\\b(?!\\s*\\.value\\s*=)`, 'g')) || [])
        .length;
      if (reads <= 1) {
        report(
          'F1',
          f,
          findLine(code, new RegExp(`\\b${name}\\s*=\\s*ref\\(`)),
          `门闩 ${name} 只写不读：置位后没有任何条件/绑定读取 ⇒ 防重形同虚设（M1 row.saving 同族）。把 ${name} 接进 :disabled/:loading 或 if 守卫`
        );
      }
    }
  }
}

// ── F2. 只写不读的属性门闩 ───────────────────────────────────────────────
function checkWriteOnlyPropGuard(files) {
  for (const f of files) {
    const code = readSource(f);
    if (code === null) continue;
    const re = new RegExp(`\\.(${GUARD_NAME})\\s*=\\s*true`, 'g');
    const seen = new Set();
    let m;
    while ((m = re.exec(code))) {
      const prop = m[1];
      if (seen.has(prop)) continue;
      seen.add(prop);
      const reads = (code.match(new RegExp(`\\.${prop}\\b(?!\\s*=)`, 'g')) || []).length;
      if (reads === 0) {
        report(
          'F2',
          f,
          findLine(code, new RegExp(`\\.${prop}\\s*=\\s*true`)),
          `属性门闩 .${prop} 只写不读：置 true 后全文没有任何读取 ⇒ 双击并发双写（M1 原型）。把 .${prop} 接进按钮 disabled 或入口 if 守卫`
        );
      }
    }
  }
}

// ── F3. 门闩晚于鉴权 await ───────────────────────────────────────────────
function checkGuardAfterAuthAwait(files) {
  for (const f of files) {
    const code = readSource(f);
    if (code === null) continue;
    const lines = linesOf(code);
    for (let i = 0; i < lines.length; i++) {
      const gm = lines[i].match(new RegExp(`^\\s*(?:${GUARD_NAME})(?:\\.value)?\\s*=\\s*true`));
      if (!gm) continue;
      const back = lines.slice(Math.max(0, i - 12), i).join('\n');
      if (AUTH_AWAIT_RE.test(back)) {
        report(
          'F3',
          f,
          i + 1,
          '门闩置位晚于鉴权 await：ensureAuth/ensureLogin 握手窗口内按钮未置灰，可二次提交（feedback.vue 双提交原型）。修法：门闩前置，鉴权失败分支复位'
        );
      }
    }
  }
}

// ── F4. 选图/上传入口无门闩 ──────────────────────────────────────────────
const PICK_CALL_RE = /uni\.(?:chooseImage|chooseMedia)\s*\(|safeChooseImage/;
function checkPickWithoutGuard(files) {
  for (const f of files) {
    // safe-uni-call.ts 是共享选图封装本体：取消/拒绝分流已内建，门闩责任在调用方
    // （addEvidence/chooseEvidence 等入口），封装自身不设闩——既有约定，豁免。
    if (f.replace(/\\/g, '/').endsWith('/safe-uni-call.ts')) continue;
    const code = readSource(f);
    if (code === null) continue;
    for (const b of allFunctionBodies(code)) {
      // *Once 内联单发函数豁免：重试包装器（uploadXxxOnce）有意不做门闩
      if (/Once$/.test(b.name)) continue;
      const blines = linesOf(b.text);
      const pickIdx = blines.findIndex((l) => PICK_CALL_RE.test(l));
      if (pickIdx < 0) continue;
      const before = blines.slice(0, pickIdx).join('\n');
      const hasEarlyReturn = new RegExp(`if\\s*\\([^)]*\\b${GUARD_NAME}\\b[^)]*\\)\\s*return`).test(
        before
      );
      const hasSet = new RegExp(`\\b${GUARD_NAME}\\b\\s*(?:\\.value\\s*)?=\\s*true`).test(before);
      if (!hasEarlyReturn && !hasSet) {
        report(
          'F4',
          f,
          b.line,
          `选图/上传入口 ${b.name} 无在途门闩：调用 chooseImage/chooseMedia 前没有判官/置位，连点可破「最多 N 张」并重复上传（第三轮拍照上传、第四轮盘点上传同族）。修法：入口 if (xInFlight) return + 置位/finally 复位`
        );
      }
    }
  }
}

// ── G. 静默截断 ──────────────────────────────────────────────────────────
function checkSilentTruncation(files) {
  for (const f of files) {
    if (!f.endsWith('.vue')) continue;
    const code = readSource(f);
    if (code === null) continue;
    const tpl = code.match(/<template>[\s\S]*?<\/template>/);
    if (!tpl) continue;
    const notice = NOTICE_RE.test(code);
    if (notice) continue;
    // ① v-for 直接渲染的 computed 里 slice(0, N>=10)
    const vForNames = new Set();
    const vre = /v-for="[^"]*?\bin\s+(\w+)/g;
    let vm;
    while ((vm = vre.exec(tpl[0]))) vForNames.add(vm[1]);
    for (const c of computedBodies(code)) {
      if (!vForNames.has(c.name)) continue;
      const sm = c.text.match(/\.slice\(0,\s*(\d+)\)/);
      if (sm && Number(sm[1]) >= 10) {
        report(
          'G',
          f,
          c.line,
          `v-for 渲染的 ${c.name} 静默截断 slice(0,${sm[1]})：无「仅展示前 N 项/完整数据」提示，用户以为看到全量（销售明细 slice(0,12)、points/messages 100 条同族）`
        );
      }
    }
    // ② 模板表达式里直接 slice(0, N>=10)（行号换算回全文件坐标）
    const tplOffset = code.slice(0, code.indexOf(tpl[0])).split('\n').length - 1;
    const tline = findLine(tpl[0], /\.slice\(0,\s*(?:[1-9]\d{1,}|\d{2,})\)/);
    if (tline > 0) {
      report(
        'G',
        f,
        tplOffset + tline,
        '模板表达式静默截断 slice(0, N≥10)：无任何提示，用户以为看到全量'
      );
    }
  }
}

// ── H. 翻页污染（追加无超期守卫） ─────────────────────────────────────────
function checkPaginationPollution(files) {
  for (const f of files) {
    const code = readSource(f);
    if (code === null) continue;
    for (const b of functionBodies(code, '\\w*(?:loadMore|fetchPage|loadNext)\\w*')) {
      const appendRe = /(\w+)\.value\s*=\s*(?:\1\.value\.concat|\[\s*\.\.\.\1\.value)/;
      const hasAppend = appendRe.test(b.text) || /\.value\.push\(/.test(b.text);
      if (!hasAppend) continue;
      if (/seq/i.test(b.text)) continue;
      report(
        'H',
        f,
        b.line,
        `${b.name} 追加分页数据无 seq 超期守卫：load()/切 tab/自动刷新在途时，迟到旧页会 append 到新列表（tally 翻页污染同族）。修法：const seq = ++loadSeq + if (seq !== loadSeq) return`
      );
    }
  }
}

// ── I. Blob URL 不回收 ───────────────────────────────────────────────────
function checkBlobLeak(files) {
  for (const f of files) {
    const code = readSource(f);
    if (code === null) continue;
    if (!code.includes('createObjectURL')) continue;
    if (code.includes('revokeObjectURL')) continue;
    report(
      'I',
      f,
      findLine(code, /createObjectURL/),
      'URL.createObjectURL 无对应 revokeObjectURL：blob 句柄泄漏（verify 签约 blob 同族）。用后延迟回收'
    );
  }
}

// ── J. 死导入 ────────────────────────────────────────────────────────────
function checkDeadImports(files) {
  for (const f of files) {
    const code = readSource(f);
    if (code === null) continue;
    const re = /import\s*\{([^}]*)\}\s*from\s*['"][^'"]+['"]/g;
    let m;
    while ((m = re.exec(code))) {
      const rest = code.slice(0, m.index) + code.slice(m.index + m[0].length);
      for (const part of m[1].split(',')) {
        let name = part.trim().replace(/^type\s+/, '');
        name = name
          .split(/\s+as\s+/)
          .pop()
          .split(':')
          .pop()
          .trim();
        if (!name || name === 'default' || name.startsWith('.')) continue;
        const uses = (rest.match(new RegExp(`\\b${name}\\b`, 'g')) || []).length;
        if (uses === 0) {
          report(
            'J',
            f,
            // 零引用 ⇒ 首个出现处就是 import 语句自身（多行 import 也不会 -1）
            findLine(code, new RegExp(`\\b${name}\\b`)),
            `死导入 ${name}：import 后文件内再无任何引用（video 死 import、consumer-api 死导出同族）。删掉，别让 vue-tsc/打包替你养着`
          );
        }
      }
    }
  }
}

// ── K. 刷新失败即登出 ────────────────────────────────────────────────────
function checkRefreshLogout(files) {
  for (const f of files) {
    const code = readSource(f);
    if (code === null) continue;
    const lines = linesOf(code);
    for (let i = 0; i < lines.length; i++) {
      const l = lines[i];
      // 只认调用点，不认声明：`export function handleUnauthorized(message?)` 与
      // 接口方法 `handleUnauthorized(message?): Error;` 曾造成误报。
      const isCall =
        /(?:^|[^\w.])handleUnauthorized\s*\(\s*\)/.test(l) ||
        /\.\s*handleUnauthorized\s*\(/.test(l) ||
        /\bthrow\s+handleUnauthorized\s*\(/.test(l);
      if (!isCall) continue;
      const ctx = lines.slice(Math.max(0, i - 12), i + 3).join('\n');
      if (!AUTH_STATUS_RE.test(ctx)) {
        report(
          'K',
          f,
          i + 1,
          'handleUnauthorized 调用处无鉴权状态判据：弱网超时/传输层失败会被当成会话失效误杀（第四轮留置项原型）。只在明确 401/403/AUTH_EXPIRED 后才登出'
        );
      }
    }
  }
}

// ── L. 硬编码版本号 ──────────────────────────────────────────────────────
function checkHardcodedVersion(files) {
  for (const f of files) {
    const code = readSource(f);
    if (code === null) continue;
    const line = findLine(code, /\bv\d+\.\d+\.\d+\b/);
    if (line > 0) {
      report(
        'L',
        f,
        line,
        '硬编码版本号 vX.Y.Z：应由构建注入（第二轮 L4）。前端展示版本走运行时配置/manifest'
      );
    }
  }
}

// ── M. 订阅消息模板 ID 硬编码 ────────────────────────────────────────────
function checkTemplateIdLiteral(files) {
  for (const f of files) {
    const code = readSource(f);
    if (code === null) continue;
    const lines = linesOf(code);
    for (let i = 0; i < lines.length; i++) {
      const m = lines[i].match(/['"`]([A-Za-z0-9_-]{38,})['"`]/);
      if (m) {
        report(
          'M',
          f,
          i + 1,
          `不透明 ID 字面量（${m[1].length} 位）：订阅消息模板 ID 一类必须走后端公开配置下发——硬编码=后端一改前端 100% 静默失效（第五轮 consumer 订阅消息全静默同族）`
        );
      }
    }
  }
}

// ── N. 失败负缓存钉死 ────────────────────────────────────────────────────
function checkNegativeCache(files) {
  for (const f of files) {
    const code = readSource(f);
    if (code === null) continue;
    const lines = linesOf(code);
    for (let i = 0; i < lines.length; i++) {
      if (!/\.catch\(|catch\s*\(/.test(lines[i])) continue;
      // catch 体：向后最多 12 行或到配平的 }
      let depth = 0;
      let started = false;
      const body = [];
      for (let j = i; j < Math.min(lines.length, i + 12); j++) {
        body.push(lines[j]);
        for (const ch of lines[j]) {
          if (ch === '{' || ch === '(') {
            depth++;
            started = true;
          }
          if (ch === '}' || ch === ')') depth--;
        }
        if (started && depth <= 0) break;
      }
      const t = body.join('\n');
      const pin = t.match(/(\w*[Cc]ache\w*)\s*=\s*(?:\{\}|\[\])/);
      if (!pin) continue;
      // 文件级 TTL/负缓存时间戳机制在手 ⇒ 有意设计（consumer feature-flags 修后形态）
      if (/FAILURE_RETRY|lastFailureAt|negativeCache|NEGATIVE_CACHE|\bTTL\b|retryAfter/i.test(code))
        continue;
      report(
        'N',
        f,
        i + 1,
        `catch 里 ${pin[1]} = {} / [] 写进缓存：真值空表会把失败态永久钉死（一次网络抖动⇒整个进程生命周期无重试）。10-23 consumer feature-flags 同族已修；改负缓存时间戳或 cache = null`
      );
    }
  }
}

// ── O. 无界缓存 ──────────────────────────────────────────────────────────
function checkUnboundedCache(files) {
  for (const f of files) {
    const code = readSource(f);
    if (code === null) continue;
    const lines = linesOf(code);
    // 豁免判据必须精确：早期用 /MAX|LIMIT|CAP|BOUND/i 被 captureTaxBaseline（cap）、
    // maxlength（max）误命中 ⇒ 几乎所有文件都被豁免。现只认「常量赋数字」与「驱逐调用」。
    const hasBound =
      /\b(?:MAX|LIMIT|CAP|BOUND|SIZE)_?\w*\s*=\s*\d|\.shift\(\)|\.delete\(|evict|Fifo|FIFO/i.test(
        code
      );
    if (hasBound) continue;
    for (let i = 0; i < lines.length; i++) {
      const m = lines[i].match(
        /^\s*(?:export\s+)?(?:const|let)\s+(\w*[Cc]ache\w*)\s*(?::[^=]+)?=\s*new Map\(/
      );
      if (!m) continue;
      report(
        'O',
        f,
        i + 1,
        `模块级缓存 ${m[1]} = new Map() 无上限/驱逐：长会话无界增长（争议证据缓存无上限同族）。套 BoundedFifoCache 或补淘汰`
      );
    }
  }
}

// ── P. 引用静态资产存在性（悬空引用）────────────────────────────────────────
/**
 * 2026-10-24 新增。来源是一次真实险情：清理孤产素材时按「文件名 grep 零引用」
 * 判死了 28+21 个菜单图标，但 mine/member 页用 `menuIcon('member')` **动态拼接**
 * `/static/menu/member.png`——basename grep 看不见调用形态，删完静态里还在、
 * 运行时全是 404。靠构建后人工抽查才拦下（恢复 20 个文件）。
 * ⇒ 删素材/改图标名/挪 static 目录后，**引用侧必须有机检**，三个机械形态：
 *   P1 字面量：代码/JSON 里引用的 `/static/<path>.<ext>` 必须存在；
 *   P2 菜单图标助手：`menuIcon('name')` 的字面参数 ⇒ static/menu/name.png 必须存在；
 *   P2b 动态调用方的同文件数据：文件里出现 `menuIcon(非字面量)` 时，同文件
 *      `icon:`/`mark:` 字符串字面量所指标的资产必须存在（member 权益行）。
 * 已知边界（刻意不收，防误伤）：跨文件配置（merchant-nav.ts 的 icon 值经
 * mine.vue 的 menuIcon(item.icon) 消费）由 merchant-nav.test.ts 的存在性断言钉住；
 * 字符串拼接路径（'/static/x/' + name）不机检。测试文件不扫。
 */
const STATIC_LIT_RE = /['"`](\/?static\/[A-Za-z0-9._\-/]+\.(?:png|jpe?g|svg|webp|gif|mp4))['"`]/g;
const MENU_ICON_ARG_RE = /menuIcon\(\s*'([a-z][a-z0-9-]*)'\s*\)/g;
const MENU_ICON_DYNAMIC_RE = /menuIcon\(\s*(?!['"`])/;
const ICON_DATA_LIT_RE = /(?:icon|mark)\s*:\s*'([a-z][a-z0-9-]*)'/g;

function checkAssetExists(files, appSrc) {
  for (const f of files) {
    if (/\.test\.ts$/.test(f)) continue;
    const code = readSource(f);
    if (code === null) continue;
    const lineAt = (idx) => code.slice(0, idx).split('\n').length;
    const miss = (assetRel, idx, why) => {
      problems.push({ cls: 'P', file: relative(root, f), line: lineAt(idx), why });
    };
    const exists = (assetRel) => existsSync(join(appSrc, assetRel));

    // P1 字面量引用
    let m;
    STATIC_LIT_RE.lastIndex = 0;
    while ((m = STATIC_LIT_RE.exec(code))) {
      const sm = m[1].match(/(?:^|\/)static\//);
      const assetRel = m[1].slice(sm.index);
      if (!exists(assetRel)) {
        miss(assetRel, m.index, `引用不存在的静态资产 ${assetRel}`);
      }
    }
    // P2 menuIcon 字面参数
    MENU_ICON_ARG_RE.lastIndex = 0;
    while ((m = MENU_ICON_ARG_RE.exec(code))) {
      const assetRel = `static/menu/${m[1]}.png`;
      if (!exists(assetRel)) {
        miss(assetRel, m.index, `menuIcon('${m[1]}') 指向不存在的 ${assetRel}`);
      }
    }
    // P2b 动态调用方的同文件 icon:/mark: 字面量
    if (MENU_ICON_DYNAMIC_RE.test(code)) {
      ICON_DATA_LIT_RE.lastIndex = 0;
      while ((m = ICON_DATA_LIT_RE.exec(code))) {
        const assetRel = `static/menu/${m[1]}.png`;
        if (!exists(assetRel)) {
          miss(assetRel, m.index, `菜单数据 '${m[1]}' 指向不存在的 ${assetRel}`);
        }
      }
    }
  }
}

// ── Q. 订阅调用不在 tap 上下文 ────────────────────────────────────────────
/**
 * 2026-10-24 三轮同族收口成类：微信 requestSubscribeMessage 必须在用户点击
 * 上下文中调用才稳定弹窗，异步链/生命周期路径上部分基础库版本**静默失败**——
 * ① index.vue finishSession 账单订阅（留置②，已移结果页按钮）；
 * ② index.vue 两处 requestDisputeSubscribe（finishSession DISPUTED 分支 + onShow
 *    恢复路径，本类落地时同步删除，通知收敛到结果页按钮/消息页横幅）。
 * 判据（机械两支）：
 *  Q1 可达性：调用宿主函数必须能从模板 @click/@tap 绑定到达（绑定表达式内
 *     函数名 + 其体内一级被调者视为可达入口；两级以上包装不豁免——故意从紧）。
 *  Q2 手势过期：宿主体内调用点**之前**存在 await（直接前缀 `await fn(` 除外）——
 *    挂起后手势上下文已丢，同样静默失败。不同分支里的 await 也会命中（保守判）。
 * 豁免：shared-uni/notify.ts（订阅封装本体，uni.requestSubscribeMessage 唯一定义点）。
 * 已知窄域：模块顶层（无函数宿主）的调用不扫（现实调用点全在函数体内）。
 */
const SUBSCRIBE_CALL_RE =
  /\b(?:uni\.)?request(?:SubscribeMessage|OrderSubscribe|MerchantSubscribe)\s*\(/g;
const TAP_BINDING_RE = /@(?:click|tap)(?:\.[\w-]+)*="([^"]+)"/g;

function checkSubscribeTapContext(files) {
  for (const f of files) {
    if (/\.test\.ts$/.test(f)) continue;
    if (f.replace(/\\/g, '/').endsWith('packages/shared-uni/src/notify.ts')) continue;
    const code = readSource(f);
    if (code === null) continue;
    if (!SUBSCRIBE_CALL_RE.test(code)) continue;
    SUBSCRIBE_CALL_RE.lastIndex = 0;

    // Q1 可达集：模板 tap 绑定表达式内的函数名
    const tapBound = new Set();
    if (f.endsWith('.vue')) {
      const tpl = code.match(/<template>[\s\S]*<\/template>/);
      if (tpl) {
        let bm;
        TAP_BINDING_RE.lastIndex = 0;
        while ((bm = TAP_BINDING_RE.exec(tpl[0]))) {
          // 裸标识符（@click="onSubscribe"）与调用形态（@click="submitPay(x)"）都要收
          for (const cm of bm[1].matchAll(/\b([A-Za-z_]\w*)\b/g)) tapBound.add(cm[1]);
        }
      }
    }
    // 一级展开：tap 可达函数体里调用的其他本地函数（onSubscribeBill → requestOrderSubscribe 形态）
    const bodies = allFunctionBodies(code);
    const reachable = new Set(tapBound);
    for (const b of bodies) {
      if (!tapBound.has(b.name)) continue;
      for (const cm of b.text.matchAll(/\b([A-Za-z_]\w*)\s*\(/g)) reachable.add(cm[1]);
    }

    for (const b of bodies) {
      const cre = new RegExp(SUBSCRIBE_CALL_RE.source, 'g');
      let m;
      while ((m = cre.exec(b.text))) {
        const isReachable = reachable.has(b.name);
        // Q2：调用前有悬空 await（去掉紧贴调用的 `await ` 前缀后再判）
        const prefix = b.text.slice(0, m.index).replace(/await\s*$/, '');
        const gestureExpired = isReachable && /\bawait\b/.test(prefix);
        if (!isReachable || gestureExpired) {
          const line = b.line + b.text.slice(0, m.index).split('\n').length - 1;
          report(
            'Q',
            f,
            line,
            !isReachable
              ? `订阅调用 ${b.name} 不可从模板 @click/@tap 到达：requestSubscribeMessage 要求用户点击上下文，异步链/生命周期路径上部分基础库版本会静默失败（留置②与争议订阅同族）。修法：移到结果页/消息页等 tap 入口`
              : `订阅调用前存在 await（${b.name} 体内调用点之前有异步挂起）：用户手势上下文已过期，弹窗会静默失败。修法：订阅调用前不得有任何 await`
          );
        }
      }
    }
  }
}

// ── 汇总 ──────────────────────────────────────────────────────────────────
const allFiles = [];
for (const app of APPS) allFiles.push(...walk(join(root, app.src)));
allFiles.push(...walk(join(root, 'packages/shared-uni/src')));

if (allFiles.length < MIN_FILES) {
  console.error(
    `${TAG} FAIL: 只扫到 ${allFiles.length} 个文件（< ${MIN_FILES}），路径表达式已失效`
  );
  process.exit(1);
}

checkPagination(allFiles);
checkComponentHooks(allFiles);
checkContextSwitch(allFiles);
checkRawErrMsg(allFiles);
checkEndpointInterpolation(allFiles);
checkWriteOnlyRefGuard(allFiles);
checkWriteOnlyPropGuard(allFiles);
checkGuardAfterAuthAwait(allFiles);
checkPickWithoutGuard(allFiles);
checkSilentTruncation(allFiles);
checkPaginationPollution(allFiles);
checkBlobLeak(allFiles);
checkDeadImports(allFiles);
checkRefreshLogout(allFiles);
checkHardcodedVersion(allFiles);
checkTemplateIdLiteral(allFiles);
checkNegativeCache(allFiles);
checkUnboundedCache(allFiles);
checkSubscribeTapContext(allFiles);

// P 类按 app 解析静态目录（/static/... 在哪个包的 src 下找哪个包）；
// shared-uni 是被两端共同打包的共享层，保守地对两端各查一遍。
for (const app of APPS) {
  const appFiles = walk(join(root, app.src));
  checkAssetExists(appFiles, join(root, app.src));
  checkAssetExists(walk(join(root, 'packages/shared-uni/src')), join(root, app.src));
}
// pages.json 的 tabBar iconPath 也是字面引用，walk 只收 .vue/.ts，单独补扫
for (const app of APPS) {
  const pj = join(root, app.src, 'pages.json');
  if (existsSync(pj)) checkAssetExists([pj], join(root, app.src));
}

// ── 自检：构造样本验证每个类「该命中/不命中」────────────────────────────
const SELFTEST_CASES = [
  {
    cls: 'A',
    code: 'loadingMore.value = true;\n} finally { if (seq === loadSeq) loadingMore.value = false; }',
    hit: true
  },
  {
    cls: 'A',
    code: 'loadingMore.value = true;\n} finally { loadingMore.value = false; }',
    hit: false
  },
  {
    cls: 'A',
    code: '// loadingMore.value = true; 只是注释',
    hit: false
  },
  {
    cls: 'B',
    code: "import { onHide } from '@dcloudio/uni-app';",
    hit: true
  },
  {
    cls: 'B',
    code: "import { onHide, onPageHide } from '@dcloudio/uni-app';",
    hit: false
  },
  {
    cls: 'B',
    code: "// import { onShow } from '@dcloudio/uni-app'; 注释",
    hit: false
  },
  {
    cls: 'C',
    code: 'function onMerchantPick(e) { merchantId.value = next; void load(); }',
    hit: true
  },
  {
    cls: 'C',
    code: 'function onMerchantPick(e) { merchantId.value = next; cfg.value = null; }',
    hit: false
  },
  {
    cls: 'C',
    code: 'function onMerchantPick(e) { merchantId.value = next; withdrawForm.value = emptyForm(); }',
    hit: false
  },
  {
    cls: 'D',
    code: 'fail: (err) => reject(new Error(err?.errMsg || "x"))',
    hit: true
  },
  {
    cls: 'D',
    code: 'fail: (err) => reject(new Error("已取消支付"))',
    hit: false
  },
  {
    cls: 'E',
    code: 'orderDetail: (id) => `/orders/${id}`',
    hit: true
  },
  {
    cls: 'E',
    code: 'orderDetail: (id) => `/orders/${encodeURIComponent(id)}`',
    hit: false
  },
  {
    cls: 'E',
    code: 'orders: (page) => `/orders?page=${page}`',
    hit: false
  },
  // ── F1 只写不读 ref 门闩 ──
  {
    cls: 'F1',
    code: 'const saving = ref(false);\nfunction save() { saving.value = true; }',
    hit: true
  },
  {
    cls: 'F1',
    code: 'const saving = ref(false);\nfunction save() { saving.value = true; }\n// :disabled="saving" 模板读取',
    hit: true
  },
  {
    cls: 'F1',
    code: 'const saving = ref(false);\nfunction save() { if (saving.value) return; saving.value = true; }',
    hit: false
  },
  // ── F2 只写不读属性门闩 ──
  {
    cls: 'F2',
    code: 'function saveRow(row) { row.saving = true; }',
    hit: true
  },
  {
    cls: 'F2',
    code: 'function saveRow(row) { if (row.saving) return; row.saving = true; }',
    hit: false
  },
  // ── F3 门闩晚于鉴权 await ──
  {
    cls: 'F3',
    code: 'async function submit() {\n  if (!(await ensureConsumerAuth())) return;\n  submitting.value = true;\n}',
    hit: true
  },
  {
    cls: 'F3',
    code: 'async function submit() {\n  submitting.value = true;\n  if (!(await ensureConsumerAuth())) { submitting.value = false; return; }\n}',
    hit: false
  },
  // ── F4 选图无门闩 ──
  {
    cls: 'F4',
    code: 'function pick() {\n  uni.chooseImage({ count: 1 });\n}',
    hit: true
  },
  {
    cls: 'F4',
    code: 'function pick() {\n  if (picking.value) return;\n  uni.chooseImage({ count: 1 });\n}',
    hit: false
  },
  {
    cls: 'F4',
    code: 'function uploadOnce(p) {\n  uni.uploadFile({ url: u, filePath: p });\n}',
    hit: false
  },
  // ── G 静默截断 ──
  {
    cls: 'G',
    code: '<template><view v-for="r in visible">{{ r }}</view></template>\n<script>\nconst visible = computed(() => rows.value.slice(0, 20));\n</script>',
    hit: true
  },
  {
    cls: 'G',
    code: '<template><view v-for="r in visible">{{ r }}</view><text>仅展示前 20 条</text></template>\n<script>\nconst visible = computed(() => rows.value.slice(0, 20));\n</script>',
    hit: false
  },
  {
    cls: 'G',
    code: '<template><view v-for="r in visible">{{ r }}</view></template>\n<script>\nconst visible = computed(() => rows.value.slice(0, 5));\n</script>',
    hit: false
  },
  // ── H 翻页污染 ──
  {
    cls: 'H',
    code: 'async function loadMore() {\n  const items = await api();\n  list.value = list.value.concat(items);\n}',
    hit: true
  },
  {
    cls: 'H',
    code: 'async function loadMore() {\n  const seq = ++loadSeq;\n  const items = await api();\n  if (seq !== loadSeq) return;\n  list.value = list.value.concat(items);\n}',
    hit: false
  },
  // ── I blob 泄漏 ──
  {
    cls: 'I',
    code: 'const url = URL.createObjectURL(blob);',
    hit: true
  },
  {
    cls: 'I',
    code: 'const url = URL.createObjectURL(blob);\nsetTimeout(() => URL.revokeObjectURL(url), 60000);',
    hit: false
  },
  // ── J 死导入 ──
  {
    cls: 'J',
    code: "import { showError, showSuccess } from '@/utils/notify';\nfunction x() { showSuccess('ok'); }",
    hit: true
  },
  {
    cls: 'J',
    code: "import { showError } from '@/utils/notify';\nfunction x() { showError('x'); }",
    hit: false
  },
  {
    cls: 'J',
    code: "import { type Foo, showError } from '@/utils/notify';\nfunction x(v: Foo) { showError('x'); }",
    hit: false
  },
  // ── K 刷新失败即登出 ──
  {
    cls: 'K',
    code: 'refresh().catch(() => { handleUnauthorized(); });',
    hit: true
  },
  {
    cls: 'K',
    code: 'refresh().catch((e) => { if (isMpAuthFailure(e)) handleUnauthorized(); });',
    hit: false
  },
  // ── L 硬编码版本 ──
  {
    cls: 'L',
    code: "const VERSION = 'v1.0.0';",
    hit: true
  },
  {
    cls: 'L',
    code: 'const VERSION = runtimeVersion;',
    hit: false
  },
  // ── M 模板 ID 硬编码 ──
  {
    cls: 'M',
    code: "const TPL = 'a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0';",
    hit: true
  },
  {
    cls: 'M',
    code: 'const TPL = config.wechatSubscribeTemplateId;',
    hit: false
  },
  // ── N 负缓存钉死 ──
  {
    cls: 'N',
    code: 'api().catch(() => { flagCache = {}; return flagCache; });',
    hit: true
  },
  {
    cls: 'N',
    code: 'api().catch(() => { lastFailureAt = Date.now(); return {}; });',
    hit: false
  },
  // ── O 无界缓存 ──
  {
    cls: 'O',
    code: 'const seenCache = new Map();',
    hit: true
  },
  {
    cls: 'O',
    code: 'const seenCache = new Map();\nconst MAX_ENTRIES = 50;',
    hit: false
  },
  // ── P 引用静态资产存在性 ──
  {
    cls: 'P',
    code: "const icon = '/static/menu/probe-missing-icon.png';",
    hit: true
  },
  {
    cls: 'P',
    code: "const icon = '/static/menu/notice.png';",
    hit: false
  },
  {
    cls: 'P',
    code: "// const icon = '/static/menu/probe-missing-icon.png'; 注释不算引用",
    hit: false
  },
  {
    cls: 'P',
    code: "const src = menuIcon('probe-missing-icon');",
    hit: true
  },
  {
    cls: 'P',
    code: "const src = menuIcon('notice');",
    hit: false
  },
  {
    cls: 'P',
    code: "const rows = [{ mark: 'probe-missing-icon' }];\nconst src = menuIcon(b.mark);",
    hit: true
  },
  {
    cls: 'P',
    code: "const rows = [{ mark: 'member' }];\nconst src = menuIcon(b.mark);",
    hit: false
  },
  // ── Q 订阅调用 tap 上下文 ──
  {
    cls: 'Q',
    code: 'function restoreSession() {\n  void requestOrderSubscribe([]);\n}',
    hit: true
  },
  {
    cls: 'Q',
    code: '<template>\n  <button @click="onSubscribe">订阅</button>\n</template>\n<script>\nfunction onSubscribe() {\n  requestOrderSubscribe([]);\n}\n</script>',
    hit: false
  },
  {
    cls: 'Q',
    code: '<template>\n  <button @click="onBind">绑定</button>\n</template>\nfunction onBind() {\n  await ensureLogin();\n  requestMerchantSubscribe([]);\n}',
    hit: true
  },
  {
    cls: 'Q',
    code: '<template>\n  <button @click="onSave">保存</button>\n</template>\nfunction onSave() {\n  const sub = await requestMerchantSubscribe([]);\n}',
    hit: false
  },
  {
    cls: 'Q',
    code: '// 参考 requestOrderSubscribe 的用法\nfunction other() {\n  noop();\n}',
    hit: false
  }
];

const selftestFailures = [];
const probeDir = join(root, '.tmp');
if (!existsSync(probeDir)) {
  const { mkdirSync } = await import('node:fs');
  mkdirSync(probeDir, { recursive: true });
}
for (const c of SELFTEST_CASES) {
  // E 类判据只作用于 endpoints.ts，探针必须同名（.tmp 被 walk 跳过，不会进真实扫描）
  const probePath = join(probeDir, c.cls === 'E' ? 'endpoints.ts' : 'defect-class-probe.vue');
  writeFileSync(probePath, c.code);
  const single = [probePath];
  const before = problems.length;
  if (c.cls === 'A') checkPagination(single);
  if (c.cls === 'B') checkComponentHooks(single);
  if (c.cls === 'C') checkContextSwitch(single);
  if (c.cls === 'D') checkRawErrMsg(single);
  if (c.cls === 'E') checkEndpointInterpolation(single);
  if (c.cls === 'F1') checkWriteOnlyRefGuard(single);
  if (c.cls === 'F2') checkWriteOnlyPropGuard(single);
  if (c.cls === 'F3') checkGuardAfterAuthAwait(single);
  if (c.cls === 'F4') checkPickWithoutGuard(single);
  if (c.cls === 'G') checkSilentTruncation(single);
  if (c.cls === 'H') checkPaginationPollution(single);
  if (c.cls === 'I') checkBlobLeak(single);
  if (c.cls === 'J') checkDeadImports(single);
  if (c.cls === 'K') checkRefreshLogout(single);
  if (c.cls === 'L') checkHardcodedVersion(single);
  if (c.cls === 'M') checkTemplateIdLiteral(single);
  if (c.cls === 'N') checkNegativeCache(single);
  if (c.cls === 'O') checkUnboundedCache(single);
  if (c.cls === 'Q') checkSubscribeTapContext(single);
  if (c.cls === 'P') checkAssetExists(single, join(root, 'clients/consumer-mp/src'));
  const fired = problems.length > before;
  if (fired !== c.hit) {
    selftestFailures.push(
      `类 ${c.cls} 样本期望 ${c.hit ? '命中' : '不命中'}，实际 ${fired ? '命中' : '不命中'}: ${c.code.slice(0, 60)}`
    );
  }
  problems.length = before;
}
// 探针清理：🔴 不要在循环内逐例 rmSync——WorkBuddy 的 node-safe-delete-shim
// （NODE_OPTIONS 预载）按 turn 累计 node 侧删除次数，preflight 链 60+ 门禁各自 rm，
// 轮到本门禁越 50 阈值即抛 SAFE_DELETE_BULK_CONFIRM_REQUIRED（2026-10-24 实证 4 连崩）。
// 探针都在 gitignored .tmp 且下次运行覆写，残留无害；结尾一次清理、失败仅告警。
for (const leftover of ['defect-class-probe.vue', 'endpoints.ts']) {
  try {
    rmSync(join(probeDir, leftover), { force: true });
  } catch (e) {
    console.warn(`${TAG} WARN: 探针清理失败（无害，.tmp 已 gitignore）: ${e?.message ?? e}`);
  }
}

if (selftestFailures.length > 0) {
  console.error(`${TAG} FAIL: 自检样本与预期不符（判据可能恒真/恒假）：`);
  for (const s of selftestFailures) console.error(`  - ${s}`);
  process.exit(1);
}

if (problems.length > 0) {
  console.error(`${TAG} FAIL: 复现型缺陷类命中 ${problems.length} 处：`);
  for (const p of problems) {
    console.error(`  [${p.cls}] ${p.file}:${p.line}`);
    console.error(`      ${p.why}`);
  }
  process.exit(1);
}

console.log(
  `${TAG} OK: ${allFiles.length} 个文件，A 分页死锁 / B 组件生命周期 / C 切换上下文 / D 原始 errMsg / E 端点插值 / F 在途门闩(F1-F4) / G 静默截断 / H 翻页污染 / I blob 泄漏 / J 死导入 / K 刷新失败登出 / L 硬编码版本 / M 模板 ID / N 负缓存钉死 / O 无界缓存 / P 资产悬空引用 / Q 订阅tap上下文 十七个类全部零命中（自检 ${SELFTEST_CASES.length} 例通过）`
);
