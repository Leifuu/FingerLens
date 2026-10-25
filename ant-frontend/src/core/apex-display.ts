import type { MergedDisplayRow } from './display-compare';

export interface ApexDisplayRow {
  key: string;
  packageName: MergedDisplayRow;
  versionCode: MergedDisplayRow;
  versionName?: MergedDisplayRow;
  state: MergedDisplayRow['state'];
}

// 业务块：沿用原数组序号，保留重复包名；所有字段（含未知字段）共同决定整行差异。
export function apexDisplayRows(rows: MergedDisplayRow[]): ApexDisplayRow[] | undefined {
  const groups = new Map<string, MergedDisplayRow[]>();
  for (const row of rows) {
    const match = /^\[(\d+)\]\./.exec(row.label);
    if (!match) return undefined;
    const fields = groups.get(match[1]) || [];
    fields.push(row);
    groups.set(match[1], fields);
  }
  const result: ApexDisplayRow[] = [];
  for (const [key, fields] of groups) {
    const field = (name: string) => fields.find(row => row.label === `[${key}].${name}`);
    const packageName = field('packageName'), versionCode = field('versionCode');
    if (!packageName || !versionCode) return undefined;
    const left = fields.some(row => row.left !== null), right = fields.some(row => row.right !== null);
    for (const side of ['left', 'right'] as const) {
      if (fields.some(row => row[side] !== null) && (packageName[side] === null || versionCode[side] === null)) return undefined;
    }
    const state = !left ? 'right-only' : !right ? 'left-only' : fields.some(row => row.state !== 'same') ? 'changed' : 'same';
    result.push({ key, packageName, versionCode, versionName: field('versionName'), state });
  }
  return result.length ? result : undefined;
}
