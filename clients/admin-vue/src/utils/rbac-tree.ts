export interface PermRow {
  permissionId: number;
  parentId: number;
  permCode: string;
  permName: string;
  permType: string;
  path?: string | null;
  sortOrder?: number;
  status?: string;
  label?: string;
  children?: PermRow[];
}

export function permTypeLabel(t: string) {
  return t === 'M' ? '目录' : t === 'C' ? '菜单' : t === 'F' ? '按钮' : t;
}

export function buildPermTree(flat: PermRow[]): PermRow[] {
  const map = new Map<number, PermRow>();
  flat.forEach((p) =>
    map.set(p.permissionId, {
      ...p,
      label: `[${permTypeLabel(p.permType)}] ${p.permName}`,
      children: []
    })
  );
  const roots: PermRow[] = [];
  map.forEach((node) => {
    if (node.parentId && map.has(node.parentId)) {
      map.get(node.parentId)!.children!.push(node);
    } else {
      roots.push(node);
    }
  });
  const sortRec = (nodes: PermRow[]) => {
    nodes.sort(
      (a, b) =>
        (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || (a.permissionId ?? 0) - (b.permissionId ?? 0)
    );
    nodes.forEach((n) => n.children?.length && sortRec(n.children));
  };
  sortRec(roots);
  return roots;
}

/** 扁平化为 parent 下拉选项（排除自身及子孙） */
export function flattenForParentSelect(nodes: PermRow[], excludeId?: number): PermRow[] {
  const out: PermRow[] = [];
  const walk = (list: PermRow[], skip = false) => {
    list.forEach((n) => {
      const selfSkip = skip || n.permissionId === excludeId;
      if (!selfSkip) out.push(n);
      if (n.children?.length) walk(n.children, selfSkip);
    });
  };
  walk(nodes);
  return out;
}
