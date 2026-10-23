#!/usr/bin/env node
/**
 * R5 自采自训的**厂商侧手动序列**（V1.13 §4.3.2 → §4.3.14，外加 §4.4.5/4.4.6 只读）。
 *
 * 为什么要有这个脚本：我方后台没有采集向导（G-5 只做只读），§4.3.3–4.3.14 十二条编排
 * 零实现；而「进入采集模式 / 退出 / 触发学习 / 进度」走我方 admin（JiangyiGatherService，
 * 会建 jiangyi_training_ticket）。**不要把 commitTrainingTenantOne 直接打给厂商**——
 * finishNotifyId 是我们的一次性凭据，直连厂商会让回调因「无 PENDING ticket」被丢弃
 * （JiangyiGatherService.handleFinishNotify 只认库里的 ticket）。
 *
 * 凭据：只从环境变量或本机 dev 容器读，**不打印**（token 只在内存里）。
 *   JIANGYI_API_BASE_URL        默认 https://lingshouapi.hunanjysmart.com
 *   JIANGYI_MERCHANT_MOBILE / JIANGYI_MERCHANT_PASSWORD
 *   JIANGYI_IDENTIFIER          柜机厂商设备编号（登记时填的 identifier，如 CQYB10720）
 *   JIANGYI_GATHER_PHONE        endAll 的 phoneNum（厂商「上传完毕通知」用）
 *
 * 用法（默认只读；写操作必须显式 --yes）：
 *   node scripts/jiangyi-gather-r5.mjs doctor
 *   node scripts/jiangyi-gather-r5.mjs list
 *   node scripts/jiangyi-gather-r5.mjs prep --product-id 41175 --name 康师傅冰红茶柠檬口味茶饮品 \
 *        --brand 康师傅 --specs 310ml --category bottle --yes
 *   node scripts/jiangyi-gather-r5.mjs ready --gather-id 235
 *   node scripts/jiangyi-gather-r5.mjs start --gather-id 235 --yes
 *   node scripts/jiangyi-gather-r5.mjs error --gather-id 235
 *   node scripts/jiangyi-gather-r5.mjs end-one --gather-id 235 --yes
 *   node scripts/jiangyi-gather-r5.mjs products
 *   node scripts/jiangyi-gather-r5.mjs go-on
 *   node scripts/jiangyi-gather-r5.mjs end-all --yes
 *   node scripts/jiangyi-gather-r5.mjs upload-info
 *   node scripts/jiangyi-gather-r5.mjs forgo-upload --yes
 *   node scripts/jiangyi-gather-r5.mjs go-on-upload --yes
 *   node scripts/jiangyi-gather-r5.mjs clear-batch --yes
 *   node scripts/jiangyi-gather-r5.mjs studying
 *   node scripts/jiangyi-gather-r5.mjs gather-check
 *   node scripts/jiangyi-gather-r5.mjs models
 *
 * 分步判据见 docs/JIANGYI_INTERFACE_CHECKLIST.md §9B.7（一轮填一行），本文件只负责打请求。
 */
import { execFileSync } from 'node:child_process';

const BASE = (process.env.JIANGYI_API_BASE_URL || 'https://lingshouapi.hunanjysmart.com').replace(
  /\/+$/,
  ''
);

function fromContainer(name) {
  try {
    return execFileSync('docker', ['exec', 'ai-cabinet-trade-service-1', 'printenv', name], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore']
    }).trim();
  } catch {
    return '';
  }
}

const MOBILE = process.env.JIANGYI_MERCHANT_MOBILE || fromContainer('JIANGYI_MERCHANT_MOBILE');
const PASSWORD =
  process.env.JIANGYI_MERCHANT_PASSWORD || fromContainer('JIANGYI_MERCHANT_PASSWORD');
const IDENTIFIER = process.env.JIANGYI_IDENTIFIER || '';
const PHONE = process.env.JIANGYI_GATHER_PHONE || '';

const argv = process.argv.slice(2);
const cmd = argv[0];
const flag = (name) => {
  const i = argv.indexOf(`--${name}`);
  if (i < 0) return undefined;
  const v = argv[i + 1];
  return v && !v.startsWith('--') ? v : '';
};
const has = (name) => argv.includes(`--${name}`);
const yes = has('yes');
const json = has('json');

function die(msg) {
  console.error(`[gather-r5] ${msg}`);
  process.exit(1);
}
function need(name, value) {
  if (!value) die(`缺少 --${name}${name === 'gather-id' ? '（§4.3.3 回包里的 id）' : ''}`);
  return value;
}
function needYes(what) {
  if (!yes) die(`${what} 会改厂商侧/柜机状态：确认后加 --yes 重跑`);
}

let token = null;
async function login() {
  if (!MOBILE || !PASSWORD)
    die(
      '缺商户凭据：设 JIANGYI_MERCHANT_MOBILE / JIANGYI_MERCHANT_PASSWORD（或起 dev 容器供读取）'
    );
  const res = await fetch(`${BASE}/merchant/authentication/login2`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mobile: MOBILE, password: PASSWORD })
  });
  const text = await res.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    die(`login2 非 JSON：http=${res.status} body=${text.slice(0, 200)}`);
  }
  if (res.status !== 200 || body?.status !== 200 || typeof body.data !== 'string') {
    die(`login2 失败：http=${res.status} status=${body?.status} msg=${body?.msg ?? ''}`); // 绝不打印凭据/token
  }
  token = body.data;
}

async function post(path, payload, { auth = true } = {}) {
  if (auth && !token) await login();
  const headers = { 'Content-Type': 'application/json' };
  if (auth && token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers,
    body: JSON.stringify(payload ?? {})
  });
  const text = await res.text();
  let body = null;
  try {
    body = JSON.parse(text);
  } catch {}
  return { http: res.status, body, text };
}

/** 统一打印：⚠️ 只看 envelope 的 status（文档口径），http 200 不代表成功。 */
function show(label, r) {
  const env = r.body ?? {};
  const ok = r.http === 200 && env.status === 200;
  const data = env.data;
  const summary =
    data === null || data === undefined
      ? 'data=null'
      : Array.isArray(data)
        ? `data=array(${data.length})`
        : typeof data === 'object'
          ? `data=${JSON.stringify(data).slice(0, 400)}`
          : `data=${String(data).slice(0, 200)}`;
  console.log(
    `${ok ? 'OK ' : 'ERR'} ${label} http=${r.http} status=${env.status ?? '-'} msg=${env.msg ?? ''} ${summary}`
  );
  if (json && r.body) console.log(JSON.stringify(r.body, null, 2));
  return env;
}

const idBody = (gatherId) => ({ identifier: need('identifier', IDENTIFIER), id: Number(gatherId) });

/** §4.3.2 / §4.3.3 之外的只读入口：0 = 成功，非 0 = 失败（供 shell 判断）。 */
async function main() {
  switch (cmd) {
    case 'doctor': {
      // H-24：文档 OCR 把大写 I 写成小写 l；只读端点做 200/404 对撞，防止照抄打空。
      const good = await post('/merchant/selfhelpGatherProduct/uploadInfoNew', {
        identifier: IDENTIFIER
      });
      show('uploadInfoNew(正确拼写)', good);
      const bad = await post('/merchant/selfhelpGatherProduct/uploadlnfoNew', {
        identifier: IDENTIFIER
      });
      console.log(
        `期望 404 的 OCR 拼写 uploadlnfoNew → http=${bad.http} status=${bad.body?.status ?? '-'}（404 才证明我们没在打错路径）`
      );
      const ctrl = await post('/merchant/stdSku/getList', {
        name: '可口可乐',
        page: 1,
        pageSize: 1
      });
      show('控制组 stdSku/getList', ctrl);
      console.log(
        '注意：§4.3.3 productInfo 无法无副作用对撞（成功即建采集任务），按正确拼写 productInfo 打，404 时先查 H-24。'
      );
      return;
    }
    case 'list':
      show(
        '§4.3.2 waitGatherProduct',
        await post('/merchant/selfhelpGatherProduct/waitGatherProduct', {})
      );
      return;
    case 'prep': {
      needYes('§4.3.3 采集准备（会建厂商侧采集任务）');
      const body = {
        identifier: need('identifier', IDENTIFIER),
        productId: Number(need('product-id', flag('product-id'))),
        name: need('name', flag('name')),
        brand: need('brand', flag('brand')),
        specs: need('specs', flag('specs')),
        category: need('category', flag('category')),
        barCodeSource: 'manual'
      };
      const env = show(
        '§4.3.3 productInfo',
        await post('/merchant/selfhelpGatherProduct/productInfo', body)
      );
      console.log(
        '下一步：ready --gather-id <回包里的 id>；该 id 也是 §4.3.4–4.3.7 的采集商品信息 id。'
      );
      return env;
    }
    case 'ready':
      show(
        '§4.3.4 startGather',
        await post(
          '/merchant/selfhelpGatherProduct/startGather',
          idBody(need('gather-id', flag('gather-id')))
        )
      );
      console.log(
        '判据：cameraStatus=yes 且 configParamStatus=yes 且 start=yes；任一 no 先别确认开始（startGather 是查询，不会开门）。'
      );
      return;
    case 'start':
      needYes('§4.3.5 开始采集（会让柜机进入采集，采购期间购物 409）');
      show(
        '§4.3.5 confirmStart',
        await post(
          '/merchant/selfhelpGatherProduct/confirmStart',
          idBody(need('gather-id', flag('gather-id')))
        )
      );
      console.log(
        '判据：状态开始后现场应看到柜门/采集提示；同时我方 nginx 应出现 device/uploadDoorState（G-6 的开门判据）。'
      );
      return;
    case 'error':
      show(
        '§4.3.6 gatherError',
        await post('/merchant/selfhelpGatherProduct/gatherError', {
          id: Number(need('gather-id', flag('gather-id')))
        })
      );
      console.log('注意：文档明确「图片不足 2000 张也返回 no，不算错误」——no ≠ 张数够。');
      return;
    case 'end-one':
      needYes('§4.3.7 结束当次商品采集');
      show(
        '§4.3.7 endOne',
        await post(
          '/merchant/selfhelpGatherProduct/endOne',
          idBody(need('gather-id', flag('gather-id')))
        )
      );
      return;
    case 'products':
      show(
        '§4.3.8 gatherProducts',
        await post('/merchant/selfhelpGatherProduct/gatherProducts', {
          identifier: need('identifier', IDENTIFIER)
        })
      );
      return;
    case 'go-on':
      show(
        '§4.3.9 goOnGather',
        await post('/merchant/selfhelpGatherProduct/goOnGather', {
          identifier: need('identifier', IDENTIFIER)
        })
      );
      console.log(
        '判据：yes=可继续下一个商品；wait=等设备；no=不可继续（看 gatherError/上传状态）。'
      );
      return;
    case 'end-all':
      needYes('§4.3.10 完成此批次采集（之后无法再往本批次补商品）');
      show(
        '§4.3.10 endAll',
        await post('/merchant/selfhelpGatherProduct/endAll', {
          identifier: need('identifier', IDENTIFIER),
          phoneNum: need('phone', PHONE)
        })
      );
      return;
    case 'upload-info':
      show(
        '§4.3.11 uploadInfoNew',
        await post('/merchant/selfhelpGatherProduct/uploadInfoNew', {
          identifier: need('identifier', IDENTIFIER)
        })
      );
      return;
    case 'forgo-upload':
      needYes('§4.3.12 放弃上传（本批次图片不再上传）');
      show(
        '§4.3.12 forgoUploadNew',
        await post('/merchant/selfhelpGatherProduct/forgoUploadNew', {
          identifier: need('identifier', IDENTIFIER)
        })
      );
      return;
    case 'go-on-upload':
      needYes('§4.3.13 继续上传');
      show(
        '§4.3.13 goOnUploadNew',
        await post('/merchant/selfhelpGatherProduct/goOnUploadNew', {
          identifier: need('identifier', IDENTIFIER)
        })
      );
      return;
    case 'clear-batch':
      needYes('§4.3.14 清除采集批次标记（上传状态无 wait 时才可调）');
      show(
        '§4.3.14 deleteBatchNoNew',
        await post('/merchant/selfhelpGatherProduct/deleteBatchNoNew', {
          identifier: need('identifier', IDENTIFIER)
        })
      );
      return;
    case 'studying':
      show(
        '§4.4.5 studyingProduct',
        await post('/merchant/selfhelpGatherProduct/studyingProduct', {})
      );
      return;
    case 'gather-check':
      show(
        '§4.4.6 gatherCheck(null=全部)',
        await post('/merchant/selfhelpGatherProduct/gatherCheck', { productIds: null })
      );
      console.log('⚠️ 必须发 productIds:null；传 [] 厂商 MyBatis 回 555。');
      return;
    case 'models':
      show('§4.4.4 getModelFileByApi', await post('/merchant/modelFile/getModelFileByApi', {}));
      return;
    default:
      console.log(
        `未知子命令：${cmd ?? '(空)'}\n见文件顶部用法；只读命令：doctor/list/ready/error/products/go-on/upload-info/studying/gather-check/models`
      );
      process.exit(2);
  }
}

main().catch((e) => die(String(e?.message || e)));
