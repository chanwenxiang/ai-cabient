/** 菜单/快捷项线性图标：/static/menu/{name}.png（由 scripts/render-mp-assets.mjs 渲染） */
export function menuIcon(name?: string | null): string {
  const key = (name || 'default').trim() || 'default';
  return `/static/menu/${key}.png`;
}
