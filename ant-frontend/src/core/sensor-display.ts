import type { MergedDisplayRow } from './display-compare';

export interface SensorDisplayRow {
  key: string;
  name: MergedDisplayRow;
  vendor: MergedDisplayRow;
  type: MergedDisplayRow;
  stringType?: MergedDisplayRow;
  version?: MergedDisplayRow;
  resolution?: MergedDisplayRow;
  maximumRange?: MergedDisplayRow;
  power?: MergedDisplayRow;
  reportingMode?: MergedDisplayRow;
  wakeUp?: MergedDisplayRow;
  dynamic?: MergedDisplayRow;
  state: MergedDisplayRow['state'];
}

// 业务块：沿用原始数组序号，保留重复传感器；全部字段共同决定概览差异。
export function sensorDisplayRows(rows: MergedDisplayRow[]): SensorDisplayRow[] | undefined {
  const groups = new Map<string, MergedDisplayRow[]>();
  for (const row of rows) {
    const match = /^\[(\d+)\]\./.exec(row.label);
    if (!match) return undefined;
    const fields = groups.get(match[1]) || [];
    fields.push(row);
    groups.set(match[1], fields);
  }
  const result: SensorDisplayRow[] = [];
  for (const [key, fields] of groups) {
    const field = (name: string) => fields.find(row => row.label === `[${key}].${name}`);
    const name = field('name'), vendor = field('vendor'), type = field('type');
    if (!name || !vendor || !type) return undefined;
    const present = (side: 'left' | 'right') => fields.some(row => row[side] !== null);
    const left = present('left'), right = present('right');
    for (const side of ['left', 'right'] as const) {
      if (present(side) && [name, vendor, type].some(row => row[side] === null)) return undefined;
    }
    result.push({ key, name, vendor, type, stringType: field('stringType'), version: field('version'), resolution: field('resolution'), maximumRange: field('maximumRange'), power: field('power'), reportingMode: field('reportingMode'), wakeUp: field('wakeUp'), dynamic: field('dynamic'),
      state: !left ? 'right-only' : !right ? 'left-only' : fields.some(row => row.state !== 'same') ? 'changed' : 'same' });
  }
  return result.length ? result : undefined;
}
