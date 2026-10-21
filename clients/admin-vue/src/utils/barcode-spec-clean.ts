/**
 * 「查官方资料」规格轻度清洗（CB-025 追加决策，2026-10-12 用户拍板）。
 *
 * 编码中心登记的规格常带包装形态英文前缀（如 CAN310mL/罐、BOT550ml/瓶）：
 * - 前缀（CAN/BOT/…）与表单既有「单位」字段语义重复且可读性差，去掉；
 * - 尾缀「/罐」式单位也去掉——单位是独立字段（由 officialUnitTail 提取后单独带出），
 *   不在规格里重复；超过 2 字的尾缀（如「/浓缩液」）不是单位词，保留官方原文。
 *
 * 用白名单而非「大写字母段」泛匹配：避免误删 PH5.5（pH 值）这类
 * 大写缩写紧贴数字的非包装前缀。只清洗自动填充值，手工输入不经过此函数。
 *
 * 2026-10-21 追加名称/规格展示规范化：moveLeadingSpecToEnd（名称首部规格挪到末尾）、
 * toEnglishSpecUnit（规格计量单位统一英文）；同批用于列表展示、CSV 与保存入库。
 */
const PACKAGE_PREFIX_RE = /^(CAN|BOT|BTL|BOX|BAG|PET|TIN|JAR|CUP)\s*(?=\d)/;
const UNIT_TAIL_RE = /\/[^\s/]{1,2}$/;

/**
 * 规格单位英文缩写（2026-10-21 用户要求）：规格里的计量单位统一用英文，
 * 如「330毫升」→「330ml」、「1.5升」→「1.5L」、「500克」→「500g」。
 * 长单位必须排在短单位前（毫升 先于 升、千克/公斤 先于 克、平方米 先于 米），
 * 拉丁单位按大小写不敏感匹配后统一输出（330ML → 330ml、1.5l → 1.5L）。
 */
const UNIT_TO_EN: Record<string, string> = {
  毫升: 'ml',
  升: 'L',
  克: 'g',
  千克: 'kg',
  公斤: 'kg',
  毫克: 'mg',
  厘米: 'cm',
  毫米: 'mm',
  米: 'm',
  平方米: 'm²',
  立方米: 'm³',
  ml: 'ml',
  l: 'L',
  g: 'g',
  kg: 'kg',
  mg: 'mg',
  cm: 'cm',
  mm: 'mm',
  m: 'm'
};
const UNIT_ALT = '毫升|千克|公斤|毫克|平方米|立方米|厘米|毫米|升|克|米|ml|kg|mg|cm|mm|g|l|m';
/** 只转换「数字 + 单位」里的单位，避免误伤纯文字（如「升级装」） */
const NUMBER_UNIT_RE = new RegExp(`(\\d+(?:\\.\\d+)?)\\s*(${UNIT_ALT})`, 'gi');
/**
 * 「1L装牛奶」「330ml罐装可乐」里的包装后缀必须随规格一起挪走，
 * 否则名称会以「装牛奶」开头。长词排前，避免「罐装」只被「装」吃掉。
 */
const LEADING_PACK_ALT =
  '罐装|瓶装|盒装|袋装|桶装|包装|箱装|提装|排装|支装|片装|粒装|枚装|听装|杯装|条装|装';
/**
 * 首位规格单独排除单字母 m：`3M口罩` / `3m胶带` 是型号，挪走会变成「M口罩 3m」；
 * 以 m 结尾的多字母单位（mm/cm/ml/mg）不受影响。代价是「5m跳绳」不再搬移，
 * 首部单字母 m 歧义过大，宁可漏搬。
 */
const LEADING_UNIT_ALT = '毫升|千克|公斤|毫克|平方米|立方米|厘米|毫米|升|克|米|ml|kg|mg|cm|mm|g|l';
/** 官方名称常把规格写在最前（330ml雪碧柠檬味汽水）→ 识别「数字+单位(+包装后缀)」前缀 */
const LEADING_SPEC_RE = new RegExp(
  `^(\\d+(?:\\.\\d+)?)\\s*(${LEADING_UNIT_ALT})\\s*(${LEADING_PACK_ALT})?\\s*(\\S.*)$`,
  'i'
);

/** 规格单位中文化 → 英文缩写；无单位或单位不在白名单时原样返回 */
export function toEnglishSpecUnit(spec: string): string {
  return spec
    .trim()
    .replace(
      NUMBER_UNIT_RE,
      (_all, num: string, unit: string) => `${num}${UNIT_TO_EN[unit.toLowerCase()] ?? unit}`
    );
}

/**
 * 规格挪到商品名称末尾（2026-10-21 用户要求）：
 * 「330ml雪碧柠檬味汽水」→「雪碧柠檬味汽水 330ml」，与种子数据既有惯例
 * （「可口可乐 330ml」）对齐。只处理名称最前面的「数字+单位(+包装后缀)」；
 * 名称本身不含前缀时原样返回。
 */
export function moveLeadingSpecToEnd(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return trimmed;
  const m = trimmed.match(LEADING_SPEC_RE);
  if (!m) return trimmed;
  const spec = `${toEnglishSpecUnit(`${m[1]}${m[2]}`)}${m[3] ?? ''}`;
  const rest = m[4]!.trim();
  if (!rest || rest === spec) return trimmed;
  return `${rest} ${spec}`;
}

export function cleanOfficialSpec(spec: string): string {
  const trimmed = spec.trim();
  if (!trimmed) return trimmed;
  return trimmed.replace(PACKAGE_PREFIX_RE, '').replace(UNIT_TAIL_RE, '');
}

/**
 * 从官方规格尾缀提取单位暗示（如「CAN310mL/罐」→「罐」）。
 * 只负责提取「/」后的短尾缀；是否采纳由调用方对照单位字典（sku_unit）白名单决定，
 * 字典未登记的尾缀（如「/提」「/箱」）自然落空、不强行映射。
 */
export function officialUnitTail(spec: string): string | null {
  const m = spec.trim().match(/\/([^\s/]{1,2})$/);
  return m ? m[1] : null;
}
