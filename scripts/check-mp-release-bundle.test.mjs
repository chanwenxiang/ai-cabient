#!/usr/bin/env node
/**
 * `check-mp-release-bundle.mjs` 的负向夹具（S8④：新门禁必须带能红的用例）。
 *
 * 这条门禁挡的是 2026-11-19 的线上事故：dev 构建与 release 构建**共用**
 * `dist/build/mp-weixin`，跑过 dev 之后从同一目录上传 ⇒ 线上 API 基址变成
 * `http://127.0.0.1:8080`，真机全站失败，而构建日志全绿。
 *
 * 夹具只喂内存材料（evaluateBundle 纯函数），不落盘、不 spawn 子进程
 * （Windows 下 Node 递归 spawn 同 exe 会 EBUSY）。
 *
 *   node scripts/check-mp-release-bundle.test.mjs
 */
import { evaluateBundle } from './check-mp-release-bundle.mjs';

const TAG = '[check-mp-release-bundle.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

const RELEASE_MARKER = {
  kind: 'release',
  distDir: 'dist/build/mp-weixin',
  markedAt: '2026-11-19T04:00:00Z'
};
const DEV_MARKER = {
  kind: 'dev',
  distDir: 'dist/build/mp-weixin',
  markedAt: '2026-11-19T03:00:00Z'
};
const GOOD_CONFIG = { appid: 'wx6dacefb3671de630', setting: { urlCheck: true } };

function bundle(patch = {}) {
  return {
    hasMarker: true,
    marker: RELEASE_MARKER,
    files: [{ rel: 'common/vendor.js', src: 'var base="https://cabinet.example.com";' }],
    config: GOOD_CONFIG,
    ...patch
  };
}

// 1) 正常 release 包 ⇒ 绿
assert(evaluateBundle(bundle()).length === 0, '合格 release 包被误判');

// 2) dev 标记 ⇒ 红，且文案要点名「不是发布包」
{
  const f = evaluateBundle(bundle({ marker: DEV_MARKER }));
  assert(f.length > 0, 'dev 标记未判红');
  assert(
    f.some((x) => x.includes('不是发布包')),
    `应点名不是发布包：${f.join(' | ')}`
  );
}

// 3) 缺标记 ⇒ 红（防止有人删掉标记绕过）
{
  const f = evaluateBundle(bundle({ hasMarker: false, marker: null }));
  assert(
    f.some((x) => x.includes('.build-kind')),
    `缺标记未判红：${f.join(' | ')}`
  );
}

// 4) 标记损坏（JSON 解析失败，marker=null）⇒ 红
{
  const f = evaluateBundle(bundle({ hasMarker: true, marker: null }));
  assert(
    f.some((x) => x.includes('无法解析')),
    `标记损坏未判红：${f.join(' | ')}`
  );
}

// 5) 产物里有带 scheme 的回环基址 ⇒ 红
{
  const f = evaluateBundle(
    bundle({
      files: [
        {
          rel: 'common/vendor.js',
          src: 'var s="https://cabinet.example.com"; var d="http://127.0.0.1:8080";'
        }
      ]
    })
  );
  assert(
    f.some((x) => x.includes('回环')),
    `回环基址未判红：${f.join(' | ')}`
  );
}

// 6) 只有文案里的 localhost 字样（不带 scheme）⇒ 不许误判
{
  const f = evaluateBundle(
    bundle({
      files: [
        {
          rel: 'pages/index/index.js',
          src: 'text:"真机访问不了电脑的 localhost"; re=/localhost|127\\.0\\.0\\.1/;'
        }
      ]
    })
  );
  assert(f.length === 0, `文案里的 localhost 被误判：${f.join(' | ')}`);
}

// 7) urlCheck=false ⇒ 红
{
  const f = evaluateBundle(
    bundle({ config: { appid: 'wx6dacefb3671de630', setting: { urlCheck: false } } })
  );
  assert(
    f.some((x) => x.includes('urlCheck')),
    `urlCheck=false 未判红：${f.join(' | ')}`
  );
}

// 8) 试玩号 appid ⇒ 红
{
  const f = evaluateBundle(
    bundle({ config: { appid: 'touristappid', setting: { urlCheck: true } } })
  );
  assert(
    f.some((x) => x.includes('appid')),
    `touristappid 未判红：${f.join(' | ')}`
  );
}

// 9) 缺 project.config.json ⇒ 红
{
  const f = evaluateBundle(bundle({ config: null }));
  assert(
    f.some((x) => x.includes('project.config.json')),
    `缺 project.config.json 未判红：${f.join(' | ')}`
  );
}

console.log(`${TAG} OK：${checks} 项断言全过`);
