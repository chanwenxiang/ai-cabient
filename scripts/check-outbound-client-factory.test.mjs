#!/usr/bin/env node
/**
 * `check-outbound-client-factory.mjs` 的负向用例：闸门必须**能红**（S8③）。
 *
 *   node scripts/check-outbound-client-factory.test.mjs
 */
import { readFile } from 'node:fs/promises';
import { scanFile, evaluate, stripCommentsAndStrings } from './check-outbound-client-factory.mjs';

const TAG = '[check-outbound-client-factory.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const errText = (errors) => errors.join(' | ');

const FACTORY =
  'services/common/common-core/src/main/java/com/aicabinet/common/http/OutboundHttp.java';
const BIZ = 'services/trade-service/src/main/java/com/aicabinet/trade/payment/SomeClient.java';
/** 闸门基线：与 scripts/outbound-client-factory-allowlist.json 一致（两条出站栈都在此收敛）。 */
const FULL = { builder: 2, simpleFactory: 1, bufferingFactory: 1, jdkClient: 1 };
const ALLOW = { [FACTORY]: { ...FULL, reason: '闸门本体' } };
const findings = (counts, jdkIssues = []) => ({ bareCreate: 0, counts, jdkIssues });

// 1) 裸 create() 一定被抓（R20-4/R21-1 的原始形态，含 R22 清点时新挖的两处）
{
  const s = scanFile(`public SomeClient() { this.restClient = RestClient.create(); }`, BIZ);
  assert(s.bareCreate === 1, `裸 create 应命中 1，实际 ${s.bareCreate}`);
  const { errors } = evaluate({ [BIZ]: s }, ALLOW);
  assert(errors.length === 1, `裸 create 应判红，实际 ${errors.length}`);
  assert(errText(errors).includes('RestClient.create'), `红因应点名裸建：${errText(errors)}`);
}

// 2) 业务类自拼 SimpleClientHttpRequestFactory 也判红（「在本地补两行超时」正是本族反复的写法）
{
  const s = scanFile(
    `var f = new SimpleClientHttpRequestFactory(); f.setReadTimeout(10_000); return RestClient.builder().requestFactory(f).build();`,
    BIZ
  );
  assert(
    s.counts.simpleFactory === 1 && s.counts.builder === 1,
    `应各命中 1：${JSON.stringify(s.counts)}`
  );
  const { errors } = evaluate({ [BIZ]: s }, ALLOW);
  assert(errors.length === 2, `未登记文件的两处共享模式应都红，实际 ${errText(errors)}`);
}

// 3) 注释/字符串里的历史说明不误报（否则没人敢在代码里写「原先 RestClient.create()」）
{
  const s = scanFile(
    `// R21-1：原先 RestClient.create() 无任何超时\n/** RestClient.builder() 的历史 */\nString hint = "RestClient.create()";\nthis.restClient = OutboundHttp.publicApi().build();`,
    BIZ
  );
  assert(
    s.bareCreate === 0 && s.counts.builder === 0,
    `注释与字符串不该计入：${JSON.stringify(s)}`
  );
  assert(
    !stripCommentsAndStrings(`/* RestClient.create() */`).includes('RestClient.create'),
    '注释应被剥掉'
  );
}

// 4) 双向棘轮：闸门本体命中数**变少**同样判红（防止把 OutboundHttp 拆成空壳后门禁跟着失效）
{
  assert(evaluate({ [FACTORY]: findings(FULL) }, ALLOW).errors.length === 0, '与基线相等应绿');

  const fewer = evaluate(
    { [FACTORY]: findings({ builder: 1, simpleFactory: 0, bufferingFactory: 1, jdkClient: 1 }) },
    ALLOW
  ).errors;
  assert(fewer.length === 2, `少命中须红两处，实际 ${errText(fewer)}`);
  assert(errText(fewer).includes('闸门被拆'), `少命中的红因要写明风险：${errText(fewer)}`);

  const more = evaluate({ [FACTORY]: findings({ ...FULL, builder: 3 }) }, ALLOW).errors;
  assert(more.length === 1 && errText(more).includes('新增绕过点'), `多命中须红：${errText(more)}`);

  const jdkGone = evaluate({ [FACTORY]: findings({ ...FULL, jdkClient: 0 }) }, ALLOW).errors;
  assert(jdkGone.length === 1, `第二条栈的构造点消失也要红：${errText(jdkGone)}`);
}

// 5) 新闸门文件必须先登记（allowlist 之外出现任何受管模式即红）
{
  const s = findings({ builder: 1, simpleFactory: 1, bufferingFactory: 0, jdkClient: 0 });
  const { errors } = evaluate({ 'services/x/NewHttp.java': s }, ALLOW);
  assert(errors.length === 2, `未登记文件须红：${errText(errors)}`);
}

// 6) 第二条出站栈（java.net.http.HttpClient）：档位写在方法体内＝每次新建客户端，必须红
{
  const perCall = scanFile(
    `public String ask() { HttpClient c = OutboundHttp.jdk(2000).build(); return c.toString(); }`,
    BIZ
  );
  assert(perCall.jdkIssues.length === 1, `方法体内新建 HttpClient 须红，实际 ${perCall.jdkIssues.length}`);
  assert(perCall.jdkIssues[0].includes('每调用一次就新建'), `红因要点明资源形态：${perCall.jdkIssues[0]}`);

  const asField = scanFile(
    `private final HttpClient httpClient = OutboundHttp.jdk(OutboundHttp.CONNECT_GEOCODE_MS).build();`,
    BIZ
  );
  assert(asField.jdkIssues.length === 0, `实例字段形态不应红：${asField.jdkIssues.join()}`);

  const asStaticField = scanFile(
    `private static final HttpClient HTTP = OutboundHttp.jdk(OutboundHttp.CONNECT_HUB_MS)
        .followRedirects(HttpClient.Redirect.NEVER).build();`,
    BIZ
  );
  assert(asStaticField.jdkIssues.length === 0, `static final 形态不应红：${asStaticField.jdkIssues.join()}`);
}

// 7) 闸门本体自己也不能退化：newBuilder 不带 connectTimeout、或改用 newHttpClient 都要红
{
  const noConnect = scanFile(
    `public static HttpClient.Builder jdk(int ms) { return HttpClient.newBuilder().build(); }`,
    FACTORY
  );
  assert(
    noConnect.jdkIssues.some((i) => i.includes('connectTimeout')),
    `闸门缺 connectTimeout 须红：${noConnect.jdkIssues.join()}`
  );
  const zeroTimeoutApi = scanFile(
    `public static HttpClient.Builder jdk(int ms) { return HttpClient.newHttpClient(); }`,
    FACTORY
  );
  assert(
    zeroTimeoutApi.jdkIssues.some((i) => i.includes('newHttpClient')),
    `闸门用 newHttpClient（零超时）须红：${zeroTimeoutApi.jdkIssues.join()}`
  );
}

// 8) 第三条栈（裸 HttpURLConnection）：connect / read 少哪一行就点哪一行
{
  const both = scanFile(
    `HttpURLConnection c = (HttpURLConnection) url.openConnection();
     c.setConnectTimeout(10_000); c.setReadTimeout(10_000);`,
    BIZ
  );
  assert(both.counts.urlConnection === 1, `openConnection 应命中 1，实际 ${both.counts.urlConnection}`);
  assert(both.jdkIssues.length === 0, `双设超时不应红：${both.jdkIssues.join()}`);

  const onlyConnect = scanFile(
    `HttpURLConnection c = (HttpURLConnection) url.openConnection(); c.setConnectTimeout(10_000);`,
    BIZ
  );
  assert(onlyConnect.jdkIssues.length === 1, `只设 connect 须红，实际 ${onlyConnect.jdkIssues.length}`);
  assert(
    onlyConnect.jdkIssues[0].includes('setReadTimeout'),
    `红因要点明缺 read（响应阶段无限等待）：${onlyConnect.jdkIssues[0]}`
  );

  const none = scanFile(
    `HttpURLConnection c = (HttpURLConnection) url.openConnection(); c.setRequestMethod("POST");`,
    BIZ
  );
  assert(none.jdkIssues.length === 1, `双缺须红一条汇总，实际 ${none.jdkIssues.length}`);
  assert(
    none.jdkIssues[0].includes('setConnectTimeout') && none.jdkIssues[0].includes('setReadTimeout'),
    `双缺时两行都要点名：${none.jdkIssues[0]}`
  );

  // 只把 openConnection 写进注释，不该被算成受管构造点
  const commentOnly = scanFile(
    `// 历史：这里曾用 HttpURLConnection#openConnection 且没设 setReadTimeout\nthis.restClient = OutboundHttp.publicApi().build();`,
    BIZ
  );
  assert(commentOnly.counts.urlConnection === 0, `注释不应计入：${commentOnly.counts.urlConnection}`);
  assert(commentOnly.jdkIssues.length === 0, `注释形态不应红：${commentOnly.jdkIssues.join()}`);
}

// 9) 真实仓面必须是绿的（把「现在长什么样」钉成断言；改名或回退都会在这里红）
{
  const src = await readFile(
    new URL(
      '../services/common/common-core/src/main/java/com/aicabinet/common/http/OutboundHttp.java',
      import.meta.url
    ),
    'utf8'
  );
  const s = scanFile(src, FACTORY);
  assert(s.bareCreate === 0, '闸门本体不得用裸 create()');
  assert(s.counts.builder === FULL.builder, `闸门 builder 命中应恰为 ${FULL.builder}，实际 ${s.counts.builder}`);
  assert(
    s.counts.jdkClient === FULL.jdkClient,
    `HttpClient.newBuilder 应只在闸门出现 ${FULL.jdkClient} 次，实际 ${s.counts.jdkClient}`
  );
  assert(s.jdkIssues.length === 0, `闸门本体应合规：${s.jdkIssues.join()}`);
  assert(/requirePositive\s*\(/.test(src), '闸门本体必须保留正数校验');
}

console.log(`${TAG} OK：${checks} 项断言全过`);
