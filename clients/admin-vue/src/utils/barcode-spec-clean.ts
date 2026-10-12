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
 */
const PACKAGE_PREFIX_RE = /^(CAN|BOT|BTL|BOX|BAG|PET|TIN|JAR|CUP)\s*(?=\d)/;
const UNIT_TAIL_RE = /\/[^\s/]{1,2}$/;

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
