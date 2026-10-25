import type { MergedDisplayRow } from './display-compare';

export interface RootPathDisplayRow {
  key: string;
  path: MergedDisplayRow;
  status?: MergedDisplayRow;
  errno?: MergedDisplayRow;
  type?: MergedDisplayRow;
  state: MergedDisplayRow['state'];
}

// 业务块：只压缩展示，沿用原始数组序号对齐；重复路径和未知字段的差异仍参与状态判断。
export function rootPathDisplayRows(rows: MergedDisplayRow[]): RootPathDisplayRow[] | undefined {
  const groups = new Map<string, MergedDisplayRow[]>();
  for (const row of rows) {
    const match = /^\[(\d+)\]\./.exec(row.label);
    if (!match) return undefined;
    const group = groups.get(match[1]) || [];
    group.push(row);
    groups.set(match[1], group);
  }
  const result: RootPathDisplayRow[] = [];
  for (const [key, fields] of groups) {
    const field = (name: string) => fields.find(row => row.label === `[${key}].${name}`);
    const path = field('path');
    if (!path) return undefined;
    const left = fields.some(row => row.left !== null), right = fields.some(row => row.right !== null);
    const state = !left ? 'right-only' : !right ? 'left-only' : fields.some(row => row.state !== 'same') ? 'changed' : 'same';
    result.push({ key, path, status: field('status'), errno: field('errno'), type: field('type'), state });
  }
  return result.length ? result : undefined;
}
