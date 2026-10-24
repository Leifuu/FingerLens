import type { DisplaySection } from '../display';

export interface MergedDisplayRow {
  key: string;
  label: string;
  left: string | null;
  right: string | null;
  state: 'same' | 'changed' | 'left-only' | 'right-only';
}

export interface MergedDisplaySection {
  key: string;
  title: string;
  table: boolean;
  rows: MergedDisplayRow[];
}

export function mergeDisplaySections(left: DisplaySection | undefined, right: DisplaySection | undefined): MergedDisplaySection {
  const rows = new Map<string, { label: string; left: string | null; right: string | null }>();
  for (const [side, section] of [['left', left], ['right', right]] as const) {
    const occurrences = new Map<string, number>();
    for (const value of section?.rows || []) {
      const occurrence = occurrences.get(value.key) || 0;
      occurrences.set(value.key, occurrence + 1);
      const key = JSON.stringify([value.key, occurrence]);
      const existing = rows.get(key) || { label: value.label, left: null, right: null };
      existing[side] = value.value;
      rows.set(key, existing);
    }
  }
  const merged = [...rows.entries()].map(([key, value]) => ({ key, ...value, state: value.left === null ? 'right-only' : value.right === null ? 'left-only' : value.left === value.right ? 'same' : 'changed' } as MergedDisplayRow));
  return { key: left?.key || right?.key || 'section', title: left?.title || right?.title || '采集值', table: !!(left?.table || right?.table), rows: merged };
}
