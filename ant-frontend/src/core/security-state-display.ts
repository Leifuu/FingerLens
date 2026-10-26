import { differences } from './compare';
import type { Json } from './types';
import type { MergedDisplayRow } from './display-compare';

interface SecurityStateEntry { index: number; value: string | null; raw: Record<string, Json> }
export interface SecurityStateRow {
  key: string;
  name: string;
  label: string;
  left?: SecurityStateEntry;
  right?: SecurityStateEntry;
  state: MergedDisplayRow['state'];
}

const names = new Map([['system_spl', '系统安全补丁'], ['vendor_spl', '厂商安全补丁'], ['kernel_version', '平台返回的内核版本']]);
const object = (value: Json | undefined): value is Record<string, Json> => value !== null && typeof value === 'object' && !Array.isArray(value);
export const securityStateText = (value: string | null) => value === null ? 'null（空值）' : value === '' ? '（空字符串）' : value;

// 业务块：最多128项/侧按原始键和重复序号配对；保留位置、未知字段与类型差异，不改写输入。
export function securityStateRows(left: Json | undefined, right: Json | undefined): SecurityStateRow[] | undefined {
  const result = new Map<string, SecurityStateRow>();
  for (const [side, value] of [['left', left], ['right', right]] as const) {
    if (value === undefined) continue;
    if (!object(value) || !Array.isArray(value.entries) || value.entries.length > 128) return undefined;
    const occurrences = new Map<string, number>();
    for (const [index, entry] of value.entries.entries()) {
      if (!object(entry) || typeof entry.key !== 'string' || !Object.hasOwn(entry, 'value') || entry.value !== null && typeof entry.value !== 'string') return undefined;
      const occurrence = occurrences.get(entry.key) ?? 0;
      occurrences.set(entry.key, occurrence + 1);
      const key = JSON.stringify([entry.key, occurrence]);
      const row = result.get(key) ?? { key, name: entry.key, label: names.get(entry.key) ?? '组件版本', state: 'same' };
      row[side] = { index, value: entry.value, raw: entry };
      result.set(key, row);
    }
  }
  if (left === undefined && right === undefined) return undefined;
  return [...result.values()].map(row => ({ ...row, state: !row.left ? 'right-only' : !row.right ? 'left-only' : row.left.index !== row.right.index || differences(row.left.raw, row.right.raw).length ? 'changed' : 'same' }));
}
