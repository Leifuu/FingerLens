import type { MergedDisplayRow } from './display-compare';

export interface FontDisplayRow {
  key: string;
  fileName: MergedDisplayRow;
  ttcIndex: MergedDisplayRow;
  weight?: MergedDisplayRow;
  slant?: MergedDisplayRow;
  localeTags?: MergedDisplayRow;
  variationSettings?: MergedDisplayRow;
  state: MergedDisplayRow['state'];
}

// 业务块：沿用原始数组序号，文件名与 TTC 索引不作去重；完整字段决定整行差异。
export function fontDisplayRows(rows: MergedDisplayRow[]): FontDisplayRow[] | undefined {
  const groups = new Map<string, MergedDisplayRow[]>();
  for (const row of rows) {
    const match = /^\[(\d+)\]\./.exec(row.label);
    if (!match) return undefined;
    const fields = groups.get(match[1]) || [];
    fields.push(row);
    groups.set(match[1], fields);
  }
  const result: FontDisplayRow[] = [];
  for (const [key, fields] of groups) {
    const field = (name: string) => fields.find(row => row.label === `[${key}].${name}`);
    const fileName = field('fileName'), ttcIndex = field('ttcIndex');
    if (!fileName || !ttcIndex) return undefined;
    const left = fields.some(row => row.left !== null), right = fields.some(row => row.right !== null);
    const state = !left ? 'right-only' : !right ? 'left-only' : fields.some(row => row.state !== 'same') ? 'changed' : 'same';
    result.push({ key, fileName, ttcIndex, weight: field('weight'), slant: field('slant'), localeTags: field('localeTags'), variationSettings: field('variationSettings'), state });
  }
  return result.length ? result : undefined;
}
