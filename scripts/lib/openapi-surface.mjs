/**
 * OpenAPI 面漂移风险判定（MA-392，2026-11-22 CI #1479 实炸后的本地盲区收口）。
 *
 * 实锤链：MA-384 给 `JiangyiMappingAutoBindService.DeviceNote`（service 内嵌 record，
 * springdoc 反射进 spec）加 `mappingVerified` 字段，未重生成 `packages/shared-types/src/
 * generated/openapi.ts` —— 本地 pre-push 全绿（原触发面只认 Controller/common-core/
 * generated 三种路径名），CI build job 起真 jar 抓 /v3/api-docs 才红。
 *
 * 为什么不用内容交叉判据：实测 0/289 个非控制器 record 名在控制器文本中被引用
 * （springdoc 按返回类型图反射，不是文本引用），本地无 jar 即无真值 ⇒ 本门禁不做
 * 「检测漂移」，只做「强制显式决策」：
 *   - fire：改了 trade-service src/main 非控制器 Java、且文件含 record 声明或位于 /dto/
 *     路径（API 形状载体），同批却没动 generated ⇒ 必须 OPENAPI_DRIFT_ACK=1 显式确认
 *     「本次无 API 形状变化」，或按 4736edee 配方重生成 shared-types。
 *   - warn：改了 src/main 非控制器 Java 但无形状信号 ⇒ 打印提醒不拦。
 * 噪声实测：origin/dev 近 60 提交 FIRE=11/WARN=3/不触发=46（约每 5-6 笔一次 ACK）。
 *
 * 纯函数、零副作用；readFile 注入以便测试。
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

export function analyzeOpenApiSurfaceRisk(names, opts = {}) {
  const root = opts.root ?? process.cwd();
  const readFile = opts.readFile ?? ((p) => readFileSync(p, 'utf8'));
  const generatedChanged = names.some((n) => n.includes('packages/shared-types/src/generated'));
  const nonControllerTradeMain = names.filter(
    (n) => /^services\/trade-service\/src\/main\/.*\.java$/.test(n) && !/Controller\.java$/.test(n)
  );
  if (generatedChanged || nonControllerTradeMain.length === 0) {
    return { level: 'none', files: [] };
  }
  const shapeFiles = [];
  const plainFiles = [];
  for (const n of nonControllerTradeMain) {
    let src = null;
    try {
      src = readFile(resolve(root, n));
    } catch {
      // 已删除的文件读不到内容：按无形状信号处理（warn 档），不误拦删码批
      plainFiles.push(n);
      continue;
    }
    if (/\/dto\//.test(n) || /\brecord\s+[A-Z]/.test(src)) shapeFiles.push(n);
    else plainFiles.push(n);
  }
  if (shapeFiles.length > 0) return { level: 'fire', files: shapeFiles };
  return { level: 'warn', files: plainFiles };
}
