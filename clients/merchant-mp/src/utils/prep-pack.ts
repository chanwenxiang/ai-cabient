/**
 * 件装换算文案（CB-072）：把「支/瓶」数量换算成仓库实际出货的「X 件 + Y 支」。
 * 整件或拆零为 0 时省略对应段；两者皆 0 时回落「0 单位」，避免空白。
 * 首页备货清单、备货单页共用同一条规则，别再各写一份。
 */
export function prepPackText(row: {
  cases?: number | null;
  remainUnits?: number | null;
  unit?: string | null;
}): string {
  const unit = String(row.unit || '支');
  const cases = Number(row.cases) || 0;
  const remain = Number(row.remainUnits) || 0;
  const parts: string[] = [];
  if (cases > 0) parts.push(`${cases} 件`);
  if (remain > 0) parts.push(`${remain} ${unit}`);
  return parts.length ? parts.join(' + ') : `0 ${unit}`;
}
