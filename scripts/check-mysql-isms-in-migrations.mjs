#!/usr/bin/env node
/**
 * 门禁：Flyway 执行的 SQL 里不许出现 **MySQL 专有语法**（本仓业务库只有 PostgreSQL）。
 *
 * 事故来源（2026-10-20，本门禁存在的原因）：`V395__sku_pack_quantity.sql` 用了 MySQL 的
 * 行内列注释 `ADD COLUMN pack_qty INT NOT NULL DEFAULT 1 COMMENT '件装数'`。
 * PostgreSQL 没有这个语法 ⇒ Flyway 在 `syntax error at or near "COMMENT"` 处失败 ⇒
 * **所有** `@SpringBootTest` 上下文加载失败（实测 56 个 error），而纯 Mockito 单测全绿 ——
 * 也就是说：一个语法方言错误，只有跑起真库才会暴露，本机跑单测完全看不出。
 *
 * 之前只扫了一次（`python` 临时脚本），没有接线 ⇒ 下一个人还会再犯。本门禁把它钉死。
 *
 * 三条设计取向（与被它守住的失败方式对应）：
 *   1. **先剥注释再匹配**。本仓迁移注释里大量用反引号包裹标识符（markdown 习惯）、
 *      也常引用历史 MySQL 写法；不剥注释 ⇒ 恒红 ⇒ 门禁被人关掉。
 *      代价：注释里的 MySQL 片段不再算数（用例 6 方向）。
 *   2. **匹配整段代码文本，不逐行**。`ALTER TABLE … ADD COLUMN a int AFTER b` 会跨行，
 *      逐行匹配会漏（假阴）。这里把注释就地替换成空格、保留换行，按全篇匹配后再回算行号。
 *   3. **扫描面必须非空**。目录被改名 / 迁移被挪走时，`0 个文件` 会打印「全部干净」——
 *      那是假绿。故文件数低于锚点值直接判红（与 `check-audit-gates-wiring` 同思路）。
 *
 * 不在范围内（已知漏口，勿当成已全覆盖）：
 *   · `infra/xxl-job/*.sql`：**刻意是 MySQL** —— `docker-compose.full.yml` 里 xxl-job 用的是
 *     `mysql:8.0` 容器 + `jdbc:mysql://xxl-job-mysql:3306/xxl_job`，由 xxl-job 官方表结构要求。
 *     本门禁只扫 Flyway 执行的目录，天然不碰它。
 *   · MyBatis `*Mapper.xml` / `@Select` 里的运行期 SQL：方言错在这里 ⇒ 单条查询失败，
 *     而不是整库起不来，失败形态不同，需要另一条门禁（当前实测该面无 MySQL 专有写法）。
 *   · `docs/` 里的证据/示例 SQL：不执行，不扫。
 *
 *   node scripts/check-mysql-isms-in-migrations.mjs
 *   node scripts/check-mysql-isms-in-migrations.test.mjs   # 防绕过自测（会临时写真 Flyway 目录）
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-mysql-isms-in-migrations]';
const DB_DIR = join(root, 'services', 'trade-service', 'src', 'main', 'resources', 'db');

// 锚点：迁移目录里有 389 个 .sql（2026-10-20 实测），db/ 全目录 393 个。
// 只设**下界**：新增迁移是常态，删除到低于下界说明目录被动过 ⇒ 先让门禁变红来问一句。
const MIN_SQL_FILES = 300;

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

/**
 * 判据表：每条都是「PostgreSQL 会直接报错」或「PostgreSQL 根本没有这个关键字」的写法。
 * `why` 说明为什么是 MySQL 专有，`fix` 给出 PG 写法 —— 失败输出必须能让人照着改，
 * 而不是只告诉他又红了。
 */
const PATTERNS = [
  {
    id: 'AUTO_INCREMENT',
    re: /\bAUTO_INCREMENT\b/gi,
    why: 'MySQL 自增列语法',
    fix: 'PG 用 BIGSERIAL / GENERATED … AS IDENTITY'
  },
  {
    id: 'ENGINE/CHARSET',
    re: /\bENGINE\s*=|\bDEFAULT\s+CHARSET\b|\bCHARACTER\s+SET\b|\bROW_FORMAT\b|\bUTF8MB4\b|\bUTF8_GENERAL\b/gi,
    why: 'MySQL 建表尾部选项 / 字符集名',
    fix: '删掉这些子句；PG 的编码在 CREATE DATABASE 上指定（ENCODING "UTF8"）'
  },
  {
    id: 'ON UPDATE CURRENT_TIMESTAMP',
    re: /\bON\s+UPDATE\s+CURRENT_TIMESTAMP\b/gi,
    why: 'MySQL 的自动更新时间列',
    fix: 'PG 用 BEFORE UPDATE 触发器（本仓既有做法）'
  },
  {
    id: 'inline COMMENT',
    re: /\bCOMMENT\s*'/gi,
    why: 'MySQL 的行内列/表注释（🔴 就是 V395 踩的那条）',
    fix: "PG 拆成语句：COMMENT ON COLUMN tbl.col IS '…' / COMMENT ON TABLE tbl IS '…'"
  },
  {
    id: 'backtick 标识符',
    re: /`/g,
    why: 'MySQL 的反引号引用',
    fix: 'PG 只在需要大小写/保留字时用双引号，一般直接去掉'
  },
  {
    id: 'MODIFY/CHANGE COLUMN',
    re: /\bMODIFY\s+COLUMN\b|\bCHANGE\s+COLUMN\b/gi,
    why: 'MySQL 的改列写法（CHANGE 还能顺便改名）',
    fix: 'PG 拆成 ALTER TABLE … ALTER COLUMN … TYPE … / RENAME COLUMN … TO …'
  },
  {
    id: 'INSERT IGNORE / REPLACE INTO / ON DUPLICATE KEY',
    re: /\bINSERT\s+IGNORE\b|\bREPLACE\s+INTO\b|\bON\s+DUPLICATE\s+KEY\b/gi,
    why: 'MySQL 的 upsert / 忽略冲突三件套',
    fix: 'PG 用 INSERT … ON CONFLICT (键) DO UPDATE SET … / DO NOTHING'
  },
  {
    id: 'MySQL 函数',
    re: /\bGROUP_CONCAT\b|\bDATE_FORMAT\b|\bSTR_TO_DATE\b|\bTIMESTAMPDIFF\b|\bDATEDIFF\b|\bIFNULL\b|\bSUBSTRING_INDEX\b|\bLOCATE\s*\(|\bINSTR\s*\(|\bCURDATE\b|\bDATE_ADD\s*\(|\bDATE_SUB\s*\(|\bLAST_DAY\s*\(|\bSQL_CALC_FOUND_ROWS\b/gi,
    why: 'MySQL 专有函数名',
    fix: 'PG 对应：string_agg / to_char / to_date / EXTRACT(EPOCH…) / COALESCE / split_part / strpos / CURRENT_DATE / (ts ± INTERVAL) / date_trunc + 1 month - 1 day'
  },
  {
    id: 'GROUP_CONCAT 的 SEPARATOR',
    re: /\bSEPARATOR\s+'/gi,
    why: 'MySQL GROUP_CONCAT 的分隔符关键字',
    fix: 'PG：string_agg(expr, 分隔符)'
  },
  {
    id: 'REGEXP 运算符',
    re: /\bREGEXP\b\s|!\s*\bREGEXP\b/gi,
    why: 'MySQL 的正则运算符（`\bREGEXP\b` 不会误伤 regexp_replace：下划线是单词字符）',
    fix: 'PG 用 ~ / ~* !~ / !~*'
  },
  {
    id: 'CONVERT(…, USING …) / CAST(… AS SIGNED)',
    re: /\bCONVERT\s*\([^)]*\bUSING\b|\bAS\s+(?:SIGNED|UNSIGNED|NCHAR)\b/gi,
    why: 'MySQL 的类型转换写法',
    fix: 'PG 用 CAST(x AS bigint) / x::text'
  },
  {
    id: 'MySQL 类型',
    re: /\bTINYINT\b|\bMEDIUMINT\b|\bMEDIUMTEXT\b|\bLONGTEXT\b|\bTINYBLOB\b|\bLONGBLOB\b|\bZEROFILL\b|\bUNSIGNED\b|\bDATETIME\b|\bDOUBLE\b(?!\s+PRECISION)|\b(?:INT|BIGINT|SMALLINT|INTEGER)\s*\(\s*\d|\b(?<!AS\s)ENUM\s*\(/gi,
    why: 'MySQL 专有类型 / 显示宽度 / DOUBLE 裸用（PG 必须 DOUBLE PRECISION）',
    fix: 'PG：smallint/int/bigint/text/bytea/timestamp/boolean；numeric(10,2) 带精度是合法的，别误改成裸 numeric'
  },
  {
    id: 'MySQL 排序规则名',
    re: /\bCOLLATE\s+[\w"']*(?:_ci|_bin)\b/gi,
    why: 'MySQL 的 collation 名（utf8mb4_general_ci 之类）',
    fix: 'PG 用 COLLATE "C" / "en_US.utf8"，或干脆依赖库默认'
  },
  {
    id: '列位置 AFTER',
    re: /\bAFTER\s+[\w"`]+\s*[,;)]/gi,
    why: 'MySQL 的 ADD COLUMN … AFTER 位置子句',
    fix: 'PG 不支持列顺序，删掉 AFTER 子句（新列只能加在末尾）'
  },
  {
    id: '索引 hint / ALTER 选项',
    re: /\bFORCE\s+INDEX\b|\bUSE\s+INDEX\b|\bIGNORE\s+INDEX\b|\bALGORITHM\s*=\s*(?:INPLACE|COPY|DEFAULT)\b|\bLOCK\s*=\s*(?:NONE|SHARED|EXCLUSIVE|DEFAULT)\b/gi,
    why: 'MySQL 优化器 hint 与 ALTER 在线 DDL 选项',
    fix: 'PG 由规划器自行决定；在线 DDL 用 CREATE INDEX CONCURRENTLY'
  },
  {
    id: 'LIMIT offset,count',
    re: /\bLIMIT\s+\d+\s*,/gi,
    why: 'MySQL 的 LIMIT 偏移写法',
    fix: 'PG：LIMIT 条数 OFFSET 偏移'
  },
  {
    id: 'DELIMITER',
    re: /\bDELIMITER\b/gi,
    why: 'MySQL 客户端指令（Flyway 不认，会当成语句报错）',
    fix: 'PG 函数体不需要改分隔符；必须写 DO $$ … $$ 时用 dollar-quoting'
  },
  {
    id: 'MySQL 的 IF() 函数',
    re: /\bIF\s*\(/gi,
    why: 'MySQL 的三元函数 IF(cond, a, b)（PG 的 IF 只存在于 PL/pgSQL 的 `IF … THEN`，不带括号）',
    fix: 'PG 用 CASE WHEN … THEN … ELSE … END'
  },
  {
    id: 'ADD CONSTRAINT IF NOT EXISTS',
    re: /\bADD\s+CONSTRAINT\s+IF\s+NOT\s+EXISTS\b|\bRENAME\s+TO\s+IF\b/gi,
    why: 'MySQL 8.0 才有的 ADD CONSTRAINT IF NOT EXISTS（PG 不支持该位置）',
    fix: 'PG 先查 pg_constraint 再 DO 块内条件执行，或直接 ADD CONSTRAINT（幂等性由迁移只跑一次保证）'
  }
];

// ── 锚点自检：目录存在 + 文件数达标（否则「0 命中」是假绿） ────────────────────
if (!existsSync(DB_DIR)) fail(`找不到 Flyway SQL 目录：${DB_DIR}（门禁失效）`);

function collectSql(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const abs = join(dir, name);
    if (statSync(abs).isDirectory()) out.push(...collectSql(abs));
    else if (name.toLowerCase().endsWith('.sql')) out.push(abs);
  }
  return out.sort();
}

const files = collectSql(DB_DIR);
if (files.length < MIN_SQL_FILES) {
  fail(
    `只扫到 ${files.length} 个 .sql（锚点下界 ${MIN_SQL_FILES}，2026-10-20 实测 393 个）——` +
      '目录被移动/重命名会让本门禁恒绿，先查清楚再改锚点'
  );
}

/**
 * 剥注释：`--` 行注释与 `/* … *\/` 块注释就地替换成空格，**保留换行与行号**。
 * 不剥注释的话，本仓迁移注释里成片的反引号（markdown 习惯）会让门禁恒红。
 */
function stripComments(text) {
  const bytes = Buffer.from(text, 'utf8');
  const out = Buffer.alloc(bytes.length, 0x20); // 全空格底座，非注释处再覆盖
  const isBreak = (c) => c === 0x0a || c === 0x0d;
  let i = 0;
  while (i < bytes.length) {
    const c = bytes[i];
    const n = bytes[i + 1];
    if (c === 0x2d /* - */ && n === 0x2d /* - */) {
      while (i < bytes.length && !isBreak(bytes[i])) i += 1;
      continue;
    }
    if (c === 0x2f /* / */ && n === 0x2a /* * */) {
      i += 2;
      while (i < bytes.length && !(bytes[i] === 0x2a && bytes[i + 1] === 0x2f)) i += 1;
      i += 2;
      continue;
    }
    out[i] = c;
    i += 1;
  }
  return out.toString('utf8');
}

function lineOf(text, index) {
  let line = 1;
  for (let k = 0; k < index; k += 1) if (text.charCodeAt(k) === 10) line += 1;
  return line;
}

function lineText(text, index) {
  const start = text.lastIndexOf('\n', index) + 1;
  let end = text.indexOf('\n', index);
  if (end === -1) end = text.length;
  return text.slice(start, end).trim();
}

const problems = [];

for (const abs of files) {
  const rel = relative(root, abs).split(sep).join('/');
  const raw = readFileSync(abs, 'utf8');
  const code = stripComments(raw);
  for (const { id, re, why, fix } of PATTERNS) {
    re.lastIndex = 0;
    for (const m of code.matchAll(re)) {
      problems.push(
        `${rel}:${lineOf(code, m.index)}  [${id}]  ${why}\n` +
          `        片段：${lineText(code, m.index).slice(0, 160)}\n` +
          `        改法：${fix}`
      );
    }
  }
}

if (problems.length) {
  fail(
    `\n  Flyway 执行的 SQL 里有 MySQL 专有语法（PostgreSQL 会直接语法错 ⇒ Flyway 失败 ⇒ ` +
      `所有 @SpringBootTest 红，纯 Mockito 单测看不出）：\n  ${problems.join('\n  ')}\n` +
      `  共 ${problems.length} 处，涉及 ${new Set(problems.map((p) => p.split(':')[0])).size} 个文件。\n`
  );
}

console.log(
  `${TAG} OK：${files.length} 个 Flyway SQL（${relative(root, DB_DIR).split(sep).join('/')}）` +
    `剥注释后按 ${PATTERNS.length} 组方言判据匹配，0 命中`
);
