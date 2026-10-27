import type { Json, Row } from './types';

export type SearchScope = 'all' | 'name' | 'field' | 'source' | 'content';

const contentCache = new WeakMap<Row, string>();

function flatten(value: Json, path: string, output: string[], budget: { size: number }) {
  if (budget.size >= 8 * 1024 * 1024) return;
  output.push(path); budget.size += path.length + 1;
  if (value === null || typeof value !== 'object') {
    const text = String(value); output.push(text); budget.size += text.length + 1;
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((entry, index) => flatten(entry, `${path}[${index}]`, output, budget));
    return;
  }
  Object.entries(value).forEach(([key, entry]) => flatten(entry, `${path}.${key}`, output, budget));
}

function rowContent(row: Row): string {
  const cached = contentCache.get(row);
  if (cached !== undefined) return cached;
  const output: string[] = [];
  const budget = { size: 0 };
  for (const item of [row.left, row.right]) {
    if (!item) continue;
    flatten(item.value, item.key, output, budget);
    output.push(item.name, item.source.api);
    if (item.installer) {
      output.push(item.installer.key, item.installer.name, item.installer.source.api);
      flatten(item.installer.value, item.installer.key, output, budget);
    }
  }
  const result = output.join('\n').toLocaleLowerCase();
  contentCache.set(row, result);
  return result;
}

export function matchesSearch(row: Row, scope: SearchScope, query: string): boolean {
  const needle = query.trim().toLocaleLowerCase();
  if (!needle) return true;
  const source = [row.left, row.right].flatMap(item => item ? [item.source.api, item.installer?.source.api || ''] : []).join(' ').toLocaleLowerCase();
  if (scope === 'name') return row.name.toLocaleLowerCase().includes(needle);
  if (scope === 'field') return row.key.toLocaleLowerCase().includes(needle);
  if (scope === 'source') return source.includes(needle);
  if (scope === 'content') return rowContent(row).includes(needle);
  return `${row.name} ${row.key} ${source}`.toLocaleLowerCase().includes(needle);
}
